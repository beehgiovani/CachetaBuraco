package com.brunogiovani.cachetaburaco.data.network

import com.brunogiovani.cachetaburaco.data.online.OnlineRoomCode
import com.brunogiovani.cachetaburaco.data.online.OnlineCompletedMatch
import com.brunogiovani.cachetaburaco.data.online.OnlineFailureCategory
import com.brunogiovani.cachetaburaco.data.online.OnlineRoomDataSource
import com.brunogiovani.cachetaburaco.data.online.OnlineRoomSession
import com.brunogiovani.cachetaburaco.data.online.OnlineRoomSummary
import com.brunogiovani.cachetaburaco.data.online.SupabaseOnlineRoomDataSource
import com.brunogiovani.cachetaburaco.domain.models.MatchConfig
import com.brunogiovani.cachetaburaco.domain.repositories.ConnectionStatus
import com.brunogiovani.cachetaburaco.domain.repositories.DiscoveredRoom
import com.brunogiovani.cachetaburaco.domain.repositories.LocalNetworkRepository
import com.brunogiovani.cachetaburaco.domain.repositories.NetworkMessage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Adaptador do online para o mesmo contrato usado pelo Wi-Fi e pela maquina.
 *
 * O banco e a fonte de verdade da sala. Aqui ficam somente o ciclo da conexao,
 * a conversao para DiscoveredRoom e uma segunda barreira de deduplicacao antes
 * de entregar mensagens ao MatchViewModel.
 */
class OnlineNetworkRepository(
    private val dataSource: OnlineRoomDataSource = SupabaseOnlineRoomDataSource(),
    private val playerNameProvider: () -> String = { "Jogador" },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : LocalNetworkRepository {
    override val requiresClientReadyHandshake: Boolean = true
    override val isOnlineTransport: Boolean = true
    override val authenticatedPlayerId: String?
        get() = currentSession?.playerId

    private val _discoveredRooms = MutableStateFlow<List<DiscoveredRoom>>(emptyList())
    override val discoveredRooms: StateFlow<List<DiscoveredRoom>> = _discoveredRooms

    private val _connectedClientsCount = MutableStateFlow(0)
    override val connectedClientsCount: StateFlow<Int> = _connectedClientsCount

    private val _incomingMessages = MutableSharedFlow<NetworkMessage>(extraBufferCapacity = 128)
    override val incomingMessages: SharedFlow<NetworkMessage> = _incomingMessages

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.IDLE)
    override val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus

    private val roomMutex = Mutex()
    private val eventPublishMutex = Mutex()
    private val operationVersion = AtomicLong(0L)
    private val seenMessageIds = LinkedHashSet<String>()
    private val recordedResultKeys = LinkedHashSet<String>()
    private val playerSeatsBySenderId = ConcurrentHashMap<String, Int>()
    private val lastSequenceByRoom = ConcurrentHashMap<String, Long>()

    @Volatile
    private var currentSession: OnlineRoomSession? = null

    @Volatile
    private var localPlayerName: String = "Jogador"

    @Volatile
    private var reconnectRoomCode: String? = null

    @Volatile
    private var identityResetPending: Boolean = false

    @Volatile
    private var currentRoundId: String? = null

    @Volatile
    private var currentRoundIsActive: Boolean = false

    private var discoveryJob: Job? = null
    private var eventJob: Job? = null
    private var presenceJob: Job? = null
    private var heartbeatJob: Job? = null
    private var hadCompleteRoom = false

    override fun startHosting(playerName: String, port: Int, config: MatchConfig?) {
        val matchConfig = config ?: MatchConfig()
        localPlayerName = normalizedPlayerName(playerName)
        reconnectRoomCode = null
        stopDiscovery()
        cancelSessionObservers()
        resetVisibleConnectionState()

        val version = operationVersion.incrementAndGet()
        scope.launch {
            roomMutex.withLock {
                if (version != operationVersion.get()) return@withLock
                closeCurrentSession()
                if (version != operationVersion.get()) return@withLock

                try {
                    resetIdentityIfNeeded()
                    val session = dataSource.createRoom(
                        playerName = localPlayerName,
                        roomCode = OnlineRoomCode.create(localPlayerName),
                        config = matchConfig
                    )
                    if (version != operationVersion.get()) {
                        safelyClose(session)
                        return@withLock
                    }
                    reconnectRoomCode = session.room.roomCode
                    installSession(session)
                    _discoveredRooms.value = listOf(session.room.toDiscoveredRoom())
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    _connectionStatus.value = ConnectionStatus.ERROR
                }
            }
        }
    }

    override fun stopHosting() {
        val version = operationVersion.incrementAndGet()
        cancelSessionObservers()
        resetVisibleConnectionState()
        reconnectRoomCode = null
        scope.launch {
            roomMutex.withLock {
                if (version == operationVersion.get()) closeCurrentSession()
            }
        }
    }

    override fun startDiscovery() {
        discoveryJob?.cancel()
        _discoveredRooms.value = emptyList()
        _connectionStatus.value = ConnectionStatus.IDLE
        localPlayerName = normalizedPlayerName(playerNameProvider())

        discoveryJob = scope.launch {
            try {
                roomMutex.withLock { resetIdentityIfNeeded() }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                _connectionStatus.value = ConnectionStatus.ERROR
                return@launch
            }
            dataSource.observeWaitingRooms(localPlayerName)
                .catch {
                    _discoveredRooms.value = emptyList()
                    _connectionStatus.value = ConnectionStatus.ERROR
                }
                .collect { rooms ->
                    _discoveredRooms.value = rooms.map { room -> room.toDiscoveredRoom() }
                }
        }
    }

    override fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        _discoveredRooms.value = emptyList()
    }

    override fun connectToRoom(host: String, port: Int) {
        val roomCode = host.trim().uppercase()
        if (roomCode.isBlank()) {
            _connectionStatus.value = ConnectionStatus.ERROR
            return
        }

        stopDiscovery()
        cancelSessionObservers()
        resetVisibleConnectionState()
        localPlayerName = normalizedPlayerName(playerNameProvider())
        reconnectRoomCode = roomCode
        val version = operationVersion.incrementAndGet()

        scope.launch {
            roomMutex.withLock {
                if (version != operationVersion.get()) return@withLock
                closeCurrentSession()
                if (version != operationVersion.get()) return@withLock

                try {
                    resetIdentityIfNeeded()
                    val session = dataSource.joinRoom(localPlayerName, roomCode)
                    if (version != operationVersion.get()) {
                        safelyLeave(session)
                        return@withLock
                    }
                    installSession(session)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    _connectionStatus.value = ConnectionStatus.ERROR
                }
            }
        }
    }

    override fun reconnect(): Boolean {
        val roomCode = reconnectRoomCode ?: currentSession?.room?.roomCode ?: return false
        connectToRoom(roomCode, port = 0)
        return true
    }

    override fun disconnect() {
        val version = operationVersion.incrementAndGet()
        stopDiscovery()
        cancelSessionObservers()
        resetVisibleConnectionState()
        reconnectRoomCode = null
        scope.launch {
            roomMutex.withLock {
                if (version == operationVersion.get()) closeCurrentSession()
            }
        }
    }

    override fun clearAuthenticatedSession() {
        identityResetPending = true
        val version = operationVersion.incrementAndGet()
        stopDiscovery()
        cancelSessionObservers()
        resetVisibleConnectionState()
        reconnectRoomCode = null
        scope.launch {
            roomMutex.withLock {
                if (version != operationVersion.get()) return@withLock
                closeCurrentSession()
                try {
                    resetIdentityIfNeeded()
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    _connectionStatus.value = ConnectionStatus.ERROR
                }
            }
        }
    }

    override fun sendMessage(message: NetworkMessage) {
        val recipientSeat = if (currentSession?.isHost == false && message.type in CLIENT_TO_HOST_TYPES) 0 else null
        publish(message = message, recipientSeat = recipientSeat)
    }

    override fun sendMessageConfirmed(message: NetworkMessage, onResult: (Boolean) -> Unit) {
        val recipientSeat = if (currentSession?.isHost == false && message.type in CLIENT_TO_HOST_TYPES) 0 else null
        publishConfirmed(message = message, recipientSeat = recipientSeat, onResult = onResult)
    }

    override fun sendMessageToClient(clientIndex: Int, message: NetworkMessage): Boolean {
        if (clientIndex < 0) return false
        return sendMessageToSeat(clientIndex + 1, message)
    }

    override fun sendMessageToSeat(seat: Int, message: NetworkMessage): Boolean {
        val session = currentSession ?: return false
        if (seat !in 1 until session.room.config.maxPlayers) return false
        return publish(message = message, recipientSeat = seat)
    }

    override fun sendMessageToPlayer(playerId: String, message: NetworkMessage): Boolean {
        val recipientSeat = playerSeatsBySenderId[playerId] ?: return false
        return publish(message = message, recipientSeat = recipientSeat)
    }

    override fun sendMessageToSeatConfirmed(
        seat: Int,
        message: NetworkMessage,
        onResult: (Boolean) -> Unit
    ) {
        val session = currentSession
        if (session == null || seat !in 1 until session.room.config.maxPlayers) {
            onResult(false)
            return
        }
        publishConfirmed(message, seat, onResult)
    }

    override fun sendMessageToPlayerConfirmed(
        playerId: String,
        message: NetworkMessage,
        onResult: (Boolean) -> Unit
    ) {
        val recipientSeat = playerSeatsBySenderId[playerId]
        if (recipientSeat == null) {
            onResult(false)
            return
        }
        publishConfirmed(message, recipientSeat, onResult)
    }

    override fun resetConnectionStatus() {
        _connectionStatus.value = when {
            currentSession == null -> ConnectionStatus.IDLE
            hadCompleteRoom -> ConnectionStatus.CONNECTED
            else -> ConnectionStatus.IDLE
        }
    }

    override fun requestServerDeal(onResult: (String?) -> Unit) {
        val session = currentSession
        if (session == null) {
            onResult(null)
            return
        }
        scope.launch {
            val result = try {
                dataSource.startRound(session)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                reportFailureTelemetry(OnlineFailureCategory.DEAL_REQUEST_FAILED, session.room.roomId)
                null
            }
            onResult(result)
        }
    }

    override fun requestServerDraw(seat: Int, onResult: (String?) -> Unit) {
        val session = currentSession
        if (session == null) {
            onResult(null)
            return
        }
        scope.launch {
            val result = try {
                dataSource.drawDeckCard(session, seat)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                reportFailureTelemetry(OnlineFailureCategory.DRAW_REQUEST_FAILED, session.room.roomId)
                null
            }
            onResult(result)
        }
    }

    private fun installSession(session: OnlineRoomSession) {
        currentSession = session
        currentRoundId = null
        currentRoundIsActive = false
        hadCompleteRoom = false
        playerSeatsBySenderId.clear()
        synchronized(recordedResultKeys) { recordedResultKeys.clear() }
        val initialSeats = if (session.isHost) {
            setOf(0)
        } else {
            setOf(0, session.seat)
        }
        updatePresence(session, initialSeats)
        if (session.isHost && _connectionStatus.value == ConnectionStatus.IDLE) {
            _connectionStatus.value = ConnectionStatus.ROOM_READY
        }
        observeSession(session)
    }

    private fun observeSession(session: OnlineRoomSession) {
        cancelSessionObservers()
        val roomId = session.room.roomId
        val afterSequence = lastSequenceByRoom[roomId] ?: 0L

        eventJob = scope.launch {
            try {
                dataSource.observeEvents(session, afterSequence).collect { event ->
                    lastSequenceByRoom.merge(roomId, event.sequence, ::maxOf)
                    // Evento de jogo sem autor autenticado nao entra na mesa. Confiar no senderId
                    // escrito pelo cliente permitiria que ele se passasse por outro assento.
                    val actorPlayerId = event.actorPlayerId ?: return@collect
                    val authenticatedMessage = event.message.copy(
                        senderId = actorPlayerId,
                        senderSeat = event.actorSeat
                    )
                    event.actorSeat?.let { seat -> playerSeatsBySenderId[actorPlayerId] = seat }
                    if (actorPlayerId == session.playerId) return@collect
                    val acceptedMessage = acceptIncomingRound(authenticatedMessage) ?: return@collect
                    if (markMessageAsNew(acceptedMessage.messageId)) {
                        _incomingMessages.emit(acceptedMessage)
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                reportSessionFailure(session, stopObservers = true)
            }
        }

        presenceJob = scope.launch {
            try {
                dataSource.observeConnectedSeats(session).collect { connectedSeats ->
                    updatePresence(session, connectedSeats)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                reportSessionFailure(session, stopObservers = true)
            }
        }

        heartbeatJob = scope.launch {
            var consecutiveFailures = 0
            while (true) {
                val refreshed = try {
                    dataSource.touchPresence(session)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Throwable) {
                    false
                }
                if (refreshed) {
                    consecutiveFailures = 0
                } else {
                    consecutiveFailures++
                    if (consecutiveFailures >= MAX_HEARTBEAT_FAILURES) {
                        reportSessionFailure(session, stopObservers = false, category = OnlineFailureCategory.HEARTBEAT_FAILED)
                    }
                }
                delay(PRESENCE_HEARTBEAT_MS)
            }
        }
    }

    private fun updatePresence(session: OnlineRoomSession, connectedSeats: Set<Int>) {
        val validSeats = connectedSeats.filterTo(linkedSetOf()) { seat ->
            seat in 0 until session.room.config.maxPlayers
        }
        val playerCount = validSeats.size
        _connectedClientsCount.value = (playerCount - 1).coerceAtLeast(0)
        val roomIsComplete = playerCount >= session.room.config.maxPlayers

        _connectionStatus.value = when {
            roomIsComplete -> {
                hadCompleteRoom = true
                ConnectionStatus.CONNECTED
            }
            hadCompleteRoom && session.isHost -> ConnectionStatus.OPPONENT_DISCONNECTED
            hadCompleteRoom && 0 !in validSeats -> ConnectionStatus.HOST_DISCONNECTED
            hadCompleteRoom -> ConnectionStatus.OPPONENT_DISCONNECTED
            session.isHost -> ConnectionStatus.ROOM_READY
            else -> ConnectionStatus.CONNECTED
        }
    }

    private fun publish(message: NetworkMessage, recipientSeat: Int?): Boolean {
        val session = currentSession ?: return false
        val preparedMessage = prepareOutgoingMessage(message)
        val completedMatch = parseCompletedMatch(session, preparedMessage)
        // Inicio sem despacho para colocar cada envio na fila na mesma ordem em
        // que a jogada aconteceu. Isso evita REQ_PICK_MORTO chegar antes do MELD.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                val delivered = eventPublishMutex.withLock {
                    dataSource.publishEvent(session, preparedMessage, recipientSeat)
                }
                if (!delivered) {
                    reportSessionFailure(session, stopObservers = false, category = OnlineFailureCategory.PUBLISH_FAILED)
                    return@launch
                }
                completedMatch?.let { recordCompletedMatch(session, it) }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                reportSessionFailure(session, stopObservers = false, category = OnlineFailureCategory.PUBLISH_FAILED)
            }
        }
        return true
    }

    private fun parseCompletedMatch(
        session: OnlineRoomSession,
        message: NetworkMessage
    ): OnlineCompletedMatch? {
        if (!session.isHost || message.type != "ROUND_SUMMARY") return null
        val json = runCatching { Json.parseToJsonElement(message.payload).jsonObject }.getOrNull()
            ?: return null
        val isMatchOver = runCatching {
            json["isMatchOver"]?.jsonPrimitive?.booleanOrNull
        }.getOrNull()
        if (isMatchOver != true) return null
        val winnerTeam = runCatching {
            json["winnerTeam"]?.jsonPrimitive?.intOrNull
        }.getOrNull()
            ?.takeIf { it in 0..1 }
            ?: return null
        val teamScores = runCatching {
            json["teamScores"]?.jsonArray?.mapNotNull { it.jsonPrimitive.intOrNull }
        }.getOrNull()?.takeIf { it.size == 2 } ?: return null

        return OnlineCompletedMatch(
            resultKey = message.messageId,
            winnerTeam = winnerTeam,
            teamScores = teamScores,
            breakdown = runCatching {
                json["breakdown"]?.jsonPrimitive?.contentOrNull
            }.getOrNull().orEmpty()
        )
    }

    private suspend fun recordCompletedMatch(
        session: OnlineRoomSession,
        result: OnlineCompletedMatch
    ) {
        if (synchronized(recordedResultKeys) { result.resultKey in recordedResultKeys }) return

        var recorded = false
        for (attempt in 0 until CONFIRMED_SEND_ATTEMPTS) {
            recorded = try {
                dataSource.recordCompletedMatch(session, result)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                false
            }
            if (recorded) break
            if (attempt + 1 < CONFIRMED_SEND_ATTEMPTS) {
                delay(CONFIRMED_SEND_RETRY_MS * (attempt + 1L))
            }
        }
        if (recorded) synchronized(recordedResultKeys) { recordedResultKeys.add(result.resultKey) }
    }

    private fun publishConfirmed(
        message: NetworkMessage,
        recipientSeat: Int?,
        onResult: (Boolean) -> Unit
    ) {
        val session = currentSession
        if (session == null) {
            onResult(false)
            return
        }
        val preparedMessage = prepareOutgoingMessage(message)
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val delivered = eventPublishMutex.withLock {
                var confirmed = false
                for (attempt in 0 until CONFIRMED_SEND_ATTEMPTS) {
                    confirmed = runCatching {
                        dataSource.publishEvent(session, preparedMessage, recipientSeat)
                    }.getOrDefault(false)
                    if (confirmed) break
                    if (attempt + 1 < CONFIRMED_SEND_ATTEMPTS) {
                        delay(CONFIRMED_SEND_RETRY_MS * (attempt + 1L))
                    }
                }
                confirmed
            }
            if (!delivered) reportSessionFailure(session, stopObservers = false, category = OnlineFailureCategory.PUBLISH_FAILED)
            onResult(delivered)
        }
    }

    private suspend fun closeCurrentSession() {
        val session = currentSession ?: return
        currentSession = null
        currentRoundId = null
        currentRoundIsActive = false
        if (session.isHost) safelyClose(session) else safelyLeave(session)
    }

    private suspend fun resetIdentityIfNeeded() {
        if (!identityResetPending) return
        dataSource.signOut()
        identityResetPending = false
    }

    private suspend fun safelyClose(session: OnlineRoomSession) {
        runCatching { dataSource.closeRoom(session) }
    }

    private suspend fun safelyLeave(session: OnlineRoomSession) {
        runCatching { dataSource.leaveRoom(session) }
    }

    private fun cancelSessionObservers() {
        eventJob?.cancel()
        presenceJob?.cancel()
        heartbeatJob?.cancel()
        eventJob = null
        presenceJob = null
        heartbeatJob = null
    }

    private fun reportSessionFailure(
        session: OnlineRoomSession,
        stopObservers: Boolean,
        category: OnlineFailureCategory = OnlineFailureCategory.SESSION_ERROR
    ) {
        if (currentSession?.room?.roomId != session.room.roomId) return
        _connectionStatus.value = ConnectionStatus.ERROR
        if (stopObservers) cancelSessionObservers()
        reportFailureTelemetry(category, session.room.roomId)
    }

    // Fogo-e-esqueço de proposito: telemetria nunca pode virar um novo motivo
    // de falha em cascata, entao qualquer erro aqui e so ignorado.
    private fun reportFailureTelemetry(category: OnlineFailureCategory, roomId: String?) {
        scope.launch {
            try {
                dataSource.reportFailure(category, roomId)
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                // Nao ha nada de util a fazer se a propria telemetria falhar.
            }
        }
    }

    private fun resetVisibleConnectionState() {
        _connectedClientsCount.value = 0
        _discoveredRooms.value = emptyList()
        _connectionStatus.value = ConnectionStatus.IDLE
        hadCompleteRoom = false
        playerSeatsBySenderId.clear()
        currentRoundId = null
        currentRoundIsActive = false
    }

    /**
     * Carimba a rodada no momento em que a mensagem entra na fila de envio.
     * Assim uma troca de rodada nao consegue alterar um evento que ja estava aguardando ACK.
     */
    private fun prepareOutgoingMessage(message: NetworkMessage): NetworkMessage {
        val payloadRoundId = roundIdFromPayload(message.payload)
        if (message.type == "GAME_START") {
            (message.roundId ?: payloadRoundId)?.let { roundId ->
                currentRoundId = roundId
                currentRoundIsActive = true
            }
        }

        val effectiveRoundId = when {
            message.type in ROUND_UNSCOPED_TYPES -> message.roundId
            message.roundId != null -> message.roundId
            payloadRoundId != null -> payloadRoundId
            currentRoundIsActive || message.type == "NEXT_ROUND" -> currentRoundId
            else -> null
        }
        val prepared = if (message.roundId == effectiveRoundId) {
            message
        } else {
            message.copy(roundId = effectiveRoundId)
        }

        if (message.type == "NEXT_ROUND" && effectiveRoundId != null) {
            currentRoundIsActive = false
        }
        return prepared
    }

    /**
     * O GAME_START e a reconexao abrem a rodada no aparelho. Depois disso,
     * mensagem sem token, de outra rodada ou recebida entre rodadas e descartada.
     */
    private fun acceptIncomingRound(message: NetworkMessage): NetworkMessage? {
        if (message.type in ROUND_UNSCOPED_TYPES) return message

        if (message.type == "GAME_START" || message.type == "RECONNECT_STATE") {
            val payloadRoundId = roundIdFromPayload(message.payload)
            if (message.roundId != null && payloadRoundId != null && message.roundId != payloadRoundId) {
                return null
            }
            val receivedRoundId = message.roundId ?: payloadRoundId
            if (receivedRoundId == null) {
                return message.takeIf { currentRoundId == null }
            }
            currentRoundId = receivedRoundId
            currentRoundIsActive = true
            return if (message.roundId == receivedRoundId) message else message.copy(roundId = receivedRoundId)
        }

        val expectedRoundId = currentRoundId
        if (expectedRoundId == null) return message.takeIf { message.roundId == null }
        if (!currentRoundIsActive || message.roundId != expectedRoundId) return null
        if (message.type == "NEXT_ROUND") currentRoundIsActive = false
        return message
    }

    private fun roundIdFromPayload(payload: String): String? {
        return runCatching {
            Json.parseToJsonElement(payload)
                .jsonObject["roundId"]
                ?.jsonPrimitive
                ?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun markMessageAsNew(messageId: String): Boolean = synchronized(seenMessageIds) {
        if (!seenMessageIds.add(messageId)) return@synchronized false
        while (seenMessageIds.size > MAX_SEEN_MESSAGES) {
            val oldest = seenMessageIds.iterator()
            oldest.next()
            oldest.remove()
        }
        true
    }

    private fun normalizedPlayerName(value: String): String {
        return value.trim().takeIf { it.length >= 2 } ?: "Jogador"
    }

    private fun OnlineRoomSummary.toDiscoveredRoom(): DiscoveredRoom {
        return DiscoveredRoom(
            serviceName = roomCode,
            host = roomCode,
            port = 0,
            config = config
        )
    }

    private companion object {
        const val MAX_SEEN_MESSAGES = 2_048
        const val CONFIRMED_SEND_ATTEMPTS = 3
        const val CONFIRMED_SEND_RETRY_MS = 250L
        const val PRESENCE_HEARTBEAT_MS = 10_000L
        const val MAX_HEARTBEAT_FAILURES = 3
        val ROUND_UNSCOPED_TYPES = setOf("CLIENT_READY", "REQ_RECONNECT")
        val CLIENT_TO_HOST_TYPES = setOf(
            "CLIENT_READY",
            "DISCARD",
            "DRAW_DISCARD",
            "MELD",
            "WIN_ROUND",
            "REQ_DRAW_DECK",
            "REQ_PICK_MORTO",
            "REPLY_WIN_ROUND",
            "REPLY_COUNT_ROUND",
            "REQ_NEXT_ROUND",
            "REPLY_RESTART",
            "REQ_RECONNECT"
        )
    }
}

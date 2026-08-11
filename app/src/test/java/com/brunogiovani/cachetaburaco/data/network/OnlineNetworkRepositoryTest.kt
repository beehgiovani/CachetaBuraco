package com.brunogiovani.cachetaburaco.data.network

import com.brunogiovani.cachetaburaco.data.online.OnlineRoomDataSource
import com.brunogiovani.cachetaburaco.data.online.OnlineChatMessage
import com.brunogiovani.cachetaburaco.data.online.OnlineCompletedMatch
import com.brunogiovani.cachetaburaco.data.online.OnlineFailureCategory
import com.brunogiovani.cachetaburaco.data.online.OnlineRoomSession
import com.brunogiovani.cachetaburaco.data.online.OnlineRoomStatus
import com.brunogiovani.cachetaburaco.data.online.OnlineRoomSummary
import com.brunogiovani.cachetaburaco.data.online.OnlineRuleRejectedException
import com.brunogiovani.cachetaburaco.data.online.OnlineStoredEvent
import com.brunogiovani.cachetaburaco.domain.models.GameType
import com.brunogiovani.cachetaburaco.domain.models.MatchConfig
import com.brunogiovani.cachetaburaco.domain.repositories.ConnectionStatus
import com.brunogiovani.cachetaburaco.domain.repositories.NetworkMessage
import com.brunogiovani.cachetaburaco.domain.repositories.RoomChatMessage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject

@OptIn(ExperimentalCoroutinesApi::class)
class OnlineNetworkRepositoryTest {

    @Test
    fun `hosting confirms ready room and becomes connected when every seat is present`() = runTest {
        val dataSource = FakeOnlineRoomDataSource()
        val config = MatchConfig(gameType = GameType.TRANCA, maxPlayers = 2)
        val repository = repository(dataSource)

        repository.startHosting(playerName = "Bruno", config = config)
        runCurrent()

        assertEquals("Bruno", dataSource.createdPlayerName)
        assertEquals(config, dataSource.createdConfig)
        assertEquals(1, repository.discoveredRooms.value.size)
        assertTrue(repository.discoveredRooms.value.single().serviceName.startsWith("BRU"))
        assertEquals(ConnectionStatus.ROOM_READY, repository.connectionStatus.value)
        assertEquals(0, repository.connectedClientsCount.value)
        assertEquals("auth-host", repository.authenticatedPlayerId)

        dataSource.connectedSeats.value = setOf(0, 1)
        runCurrent()

        assertEquals(ConnectionStatus.CONNECTED, repository.connectionStatus.value)
        assertEquals(1, repository.connectedClientsCount.value)
    }

    @Test
    fun `host reports opponent disconnect only after room was complete`() = runTest {
        val dataSource = FakeOnlineRoomDataSource()
        val repository = repository(dataSource)
        repository.startHosting("Mesa", config = MatchConfig(maxPlayers = 2))
        runCurrent()

        dataSource.connectedSeats.value = setOf(0, 1)
        runCurrent()
        dataSource.connectedSeats.value = setOf(0)
        runCurrent()

        assertEquals(ConnectionStatus.OPPONENT_DISCONNECTED, repository.connectionStatus.value)
        assertEquals(0, repository.connectedClientsCount.value)
    }

    @Test
    fun `stop hosting closes remote room and clears public state`() = runTest {
        val dataSource = FakeOnlineRoomDataSource()
        val repository = repository(dataSource)
        repository.startHosting("Mesa", config = MatchConfig())
        runCurrent()

        repository.stopHosting()
        runCurrent()

        assertEquals(1, dataSource.closedSessions.size)
        assertTrue(dataSource.leftSessions.isEmpty())
        assertTrue(repository.discoveredRooms.value.isEmpty())
        assertEquals(ConnectionStatus.IDLE, repository.connectionStatus.value)
        assertNull(repository.authenticatedPlayerId)
    }

    @Test
    fun `discovery follows waiting room updates and uses code as online address`() = runTest {
        val dataSource = FakeOnlineRoomDataSource()
        val repository = repository(dataSource, playerName = "Cliente")
        repository.startDiscovery()
        runCurrent()

        val room = dataSource.roomSummary(
            roomCode = "ABC23456",
            config = MatchConfig(gameType = GameType.BURACO)
        )
        dataSource.waitingRooms.value = listOf(room)
        runCurrent()

        val discovered = repository.discoveredRooms.value.single()
        assertEquals("Cliente", dataSource.discoveryPlayerName)
        assertEquals("ABC23456", discovered.serviceName)
        assertEquals("ABC23456", discovered.host)
        assertEquals(0, discovered.port)
        assertEquals(room.config, discovered.config)

        repository.stopDiscovery()
        assertTrue(repository.discoveredRooms.value.isEmpty())
    }

    @Test
    fun `client joins room and detects host disconnect`() = runTest {
        val dataSource = FakeOnlineRoomDataSource()
        dataSource.waitingRooms.value = listOf(
            dataSource.roomSummary(roomCode = "JOIN2345", connectedPlayers = 1)
        )
        val repository = repository(dataSource, playerName = "Convidado")

        repository.connectToRoom(host = "join2345", port = 0)
        runCurrent()

        assertEquals("Convidado", dataSource.joinedPlayerName)
        assertEquals("JOIN2345", dataSource.joinedRoomCode)
        assertEquals(ConnectionStatus.CONNECTED, repository.connectionStatus.value)
        assertEquals("auth-client", repository.authenticatedPlayerId)

        dataSource.connectedSeats.value = setOf(1)
        runCurrent()
        assertEquals(ConnectionStatus.HOST_DISCONNECTED, repository.connectionStatus.value)
    }

    @Test
    fun `client in four player room distinguishes another opponent from host`() = runTest {
        val dataSource = FakeOnlineRoomDataSource()
        dataSource.waitingRooms.value = listOf(
            dataSource.roomSummary(
                roomCode = "TEAM2345",
                config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 4)
            )
        )
        val repository = repository(dataSource)
        repository.connectToRoom("TEAM2345", 0)
        runCurrent()

        dataSource.connectedSeats.value = setOf(0, 1, 3)
        runCurrent()

        assertEquals(ConnectionStatus.OPPONENT_DISCONNECTED, repository.connectionStatus.value)
        assertEquals(2, repository.connectedClientsCount.value)
    }

    @Test
    fun `failed join exposes error and reconnect is unavailable before a room code exists`() = runTest {
        val dataSource = FakeOnlineRoomDataSource().apply { failJoin = true }
        val repository = repository(dataSource)

        assertFalse(repository.reconnect())
        repository.connectToRoom("FAIL2345", 0)
        runCurrent()

        assertEquals(ConnectionStatus.ERROR, repository.connectionStatus.value)
    }

    @Test
    fun `reconnect keeps remote session and joins same code again`() = runTest {
        val dataSource = FakeOnlineRoomDataSource()
        dataSource.waitingRooms.value = listOf(dataSource.roomSummary(roomCode = "REC23456"))
        val repository = repository(dataSource)
        repository.connectToRoom("REC23456", 0)
        runCurrent()

        assertTrue(repository.reconnect())
        runCurrent()

        assertEquals(2, dataSource.joinCalls)
        assertTrue(dataSource.leftSessions.isEmpty())
        assertTrue(dataSource.closedSessions.isEmpty())
        assertEquals(ConnectionStatus.CONNECTED, repository.connectionStatus.value)
    }

    @Test
    fun `reconnect preserves active round identity`() = runTest {
        val dataSource = FakeOnlineRoomDataSource()
        dataSource.waitingRooms.value = listOf(dataSource.roomSummary(roomCode = "ROUND234"))
        val repository = repository(dataSource)
        repository.connectToRoom("ROUND234", 0)
        runCurrent()
        val roundId = "38d0d17f-b889-4dc6-a7f4-9204592f9a84"
        repository.markRoundActive(roundId)

        assertTrue(repository.reconnect())
        runCurrent()
        repository.sendMessage(NetworkMessage("client", "REQ_DRAW_DECK", "{}", "draw-after-reconnect"))
        runCurrent()

        val published = dataSource.published.single { it.message.messageId == "draw-after-reconnect" }
        assertEquals(roundId, published.message.roundId)
        assertEquals(0, published.recipientSeat)
    }

    @Test
    fun `incoming stream ignores own events and duplicate message ids`() = runTest {
        val dataSource = FakeOnlineRoomDataSource()
        dataSource.waitingRooms.value = listOf(dataSource.roomSummary(roomCode = "EVT23456"))
        val repository = repository(dataSource)
        repository.connectToRoom("EVT23456", 0)
        runCurrent()

        val received = mutableListOf<NetworkMessage>()
        val collector = launch { repository.incomingMessages.collect { received += it } }
        runCurrent()
        val remoteMessage = NetworkMessage("host-domain", "PUBLIC_STATE", "{}", "event-1")
        dataSource.emitEvent(
            OnlineStoredEvent(1, "auth-host", 0, remoteMessage)
        )
        dataSource.emitEvent(
            OnlineStoredEvent(2, "auth-host", 0, remoteMessage)
        )
        dataSource.emitEvent(
            OnlineStoredEvent(
                sequence = 3,
                actorPlayerId = "auth-client",
                actorSeat = 1,
                message = NetworkMessage("client-domain", "PING", "", "own-1")
            )
        )
        runCurrent()

        assertEquals(1, received.size)
        assertEquals("auth-host", received.single().senderId)
        assertEquals(0, received.single().senderSeat)
        assertEquals(remoteMessage.messageId, received.single().messageId)
        assertEquals(remoteMessage.type, received.single().type)
        collector.cancel()
    }

    @Test
    fun `private responses use sender seat learned from incoming event`() = runTest {
        val dataSource = FakeOnlineRoomDataSource()
        dataSource.waitingRooms.value = listOf(dataSource.roomSummary(roomCode = "PRV23456"))
        val repository = repository(dataSource)
        repository.connectToRoom("PRV23456", 0)
        runCurrent()

        dataSource.emitEvent(
            OnlineStoredEvent(
                sequence = 1,
                actorPlayerId = "auth-host",
                actorSeat = 0,
                message = NetworkMessage("host-domain", "REQ_DRAW_DECK", "0", "request-1")
            )
        )
        runCurrent()
        val privateMessage = NetworkMessage("client-domain", "SERVE_CARD", "ACE_SPADES_BLUE", "serve-1")

        assertTrue(repository.sendMessageToPlayer("auth-host", privateMessage))
        runCurrent()

        assertEquals(privateMessage, dataSource.published.single().message)
        assertEquals(0, dataSource.published.single().recipientSeat)
        assertFalse(repository.sendMessageToPlayer("unknown", privateMessage))
    }

    @Test
    fun `host private send maps client index to seat and broadcast has no recipient`() = runTest {
        val dataSource = FakeOnlineRoomDataSource()
        val repository = repository(dataSource)
        repository.startHosting("Host", config = MatchConfig())
        runCurrent()
        val privateMessage = NetworkMessage("host", "GAME_START", "private", "start-1")
        val publicMessage = NetworkMessage("host", "PUBLIC_STATE", "{}", "state-1")

        assertTrue(repository.sendMessageToClient(0, privateMessage))
        assertTrue(repository.sendMessageToSeat(1, privateMessage.copy(messageId = "start-seat-1")))
        repository.sendMessage(publicMessage)
        assertFalse(repository.sendMessageToClient(-1, privateMessage))
        assertFalse(repository.sendMessageToSeat(2, privateMessage))
        runCurrent()

        assertEquals(listOf(1, 1, null), dataSource.published.map { it.recipientSeat })
    }

    @Test
    fun `client ready handshake is delivered only to host seat`() = runTest {
        val dataSource = FakeOnlineRoomDataSource()
        dataSource.waitingRooms.value = listOf(dataSource.roomSummary(roomCode = "RDY23456"))
        val repository = repository(dataSource)
        repository.connectToRoom("RDY23456", 0)
        runCurrent()

        val ready = NetworkMessage("client-domain", "CLIENT_READY", "", "ready-1")
        repository.sendMessage(ready)
        runCurrent()

        assertEquals(1, dataSource.published.size)
        assertEquals(ready, dataSource.published.single().message)
        assertEquals(0, dataSource.published.single().recipientSeat)
    }

    @Test
    fun `outgoing match events preserve the order chosen by the player`() = runTest {
        val dataSource = FakeOnlineRoomDataSource().apply {
            publishDelayMsByMessageId["meld-first"] = 100L
        }
        val repository = repository(dataSource)
        repository.startHosting("Host", config = MatchConfig())
        runCurrent()

        repository.sendMessage(NetworkMessage("host", "MELD", "{}", "meld-first"))
        repository.sendMessage(NetworkMessage("host", "REQ_PICK_MORTO", "{}", "morto-second"))
        runCurrent()
        advanceTimeBy(100)
        runCurrent()

        assertEquals(
            listOf("meld-first", "morto-second"),
            dataSource.published.map { it.message.messageId }
        )
    }

    @Test
    fun `game start stamps following events and next round closes that identity`() = runTest {
        val dataSource = FakeOnlineRoomDataSource()
        val repository = repository(dataSource)
        repository.startHosting("Host", config = MatchConfig())
        runCurrent()
        val roundId = "38d0d17f-b889-4dc6-a7f4-9204592f9a84"

        repository.sendMessageToSeat(
            1,
            NetworkMessage(
                "host",
                "GAME_START",
                JSONObject().put("roundId", roundId).toString(),
                "start-round-a"
            )
        )
        repository.sendMessage(NetworkMessage("host", "PUBLIC_STATE", "{}", "state-round-a"))
        repository.sendMessage(NetworkMessage("host", "RESTART_MATCH", "CANCEL", "cancel-round-a"))
        repository.sendMessage(NetworkMessage("host", "NEXT_ROUND", "", "next-round-a"))
        repository.sendMessage(NetworkMessage("host", "PUBLIC_STATE", "{}", "between-rounds"))
        runCurrent()

        assertEquals(roundId, dataSource.published.first { it.message.messageId == "start-round-a" }.message.roundId)
        assertEquals(roundId, dataSource.published.first { it.message.messageId == "state-round-a" }.message.roundId)
        assertEquals(roundId, dataSource.published.first { it.message.messageId == "cancel-round-a" }.message.roundId)
        assertEquals(roundId, dataSource.published.first { it.message.messageId == "next-round-a" }.message.roundId)
        assertNull(dataSource.published.first { it.message.messageId == "between-rounds" }.message.roundId)
    }

    @Test
    fun `incoming stream ignores old round after next round event`() = runTest {
        val dataSource = FakeOnlineRoomDataSource()
        dataSource.waitingRooms.value = listOf(dataSource.roomSummary(roomCode = "RND23456"))
        val repository = repository(dataSource)
        repository.connectToRoom("RND23456", 0)
        runCurrent()
        val roundA = "38d0d17f-b889-4dc6-a7f4-9204592f9a84"
        val roundB = "52ed73ac-7833-4388-9455-e0ecce0eaa44"
        val received = mutableListOf<NetworkMessage>()
        val collector = launch { repository.incomingMessages.collect { received += it } }
        runCurrent()

        dataSource.emitEvent(storedHostEvent(1, "start-a", "GAME_START", roundA, gamePayloadRoundId = roundA))
        dataSource.emitEvent(storedHostEvent(2, "state-a", "PUBLIC_STATE", roundA))
        dataSource.emitEvent(storedHostEvent(3, "next-a", "NEXT_ROUND", roundA))
        dataSource.emitEvent(storedHostEvent(4, "late-a", "PUBLIC_STATE", roundA))
        dataSource.emitEvent(storedHostEvent(5, "start-b", "GAME_START", roundB, gamePayloadRoundId = roundB))
        dataSource.emitEvent(storedHostEvent(6, "older-a", "PUBLIC_STATE", roundA))
        dataSource.emitEvent(storedHostEvent(7, "state-b", "PUBLIC_STATE", roundB))
        runCurrent()

        assertEquals(
            listOf("start-a", "state-a", "next-a", "start-b", "state-b"),
            received.map { it.messageId }
        )
        collector.cancel()
    }

    @Test
    fun `confirmed send retries transient failures with the same message id`() = runTest {
        val dataSource = FakeOnlineRoomDataSource().apply { publishFailuresRemaining = 2 }
        val repository = repository(dataSource)
        repository.startHosting("Host", config = MatchConfig())
        runCurrent()
        val message = NetworkMessage("host", "GAME_START", "private", "stable-start")
        var delivered: Boolean? = null

        repository.sendMessageToSeatConfirmed(1, message) { delivered = it }
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(true, delivered)
        assertEquals(3, dataSource.publishAttempts.count { it.message.messageId == message.messageId })
        assertEquals(1, dataSource.published.count { it.message.messageId == message.messageId })
    }

    @Test
    fun `confirmed public event retries without losing its round identity`() = runTest {
        val dataSource = FakeOnlineRoomDataSource()
        val repository = repository(dataSource)
        repository.startHosting("Host", config = MatchConfig())
        runCurrent()
        val roundId = "38d0d17f-b889-4dc6-a7f4-9204592f9a84"
        repository.sendMessageToSeat(
            1,
            NetworkMessage(
                "host",
                "GAME_START",
                JSONObject().put("roundId", roundId).toString(),
                "start-before-next"
            )
        )
        runCurrent()
        dataSource.publishFailuresRemaining = 2
        var delivered: Boolean? = null

        repository.sendMessageConfirmed(
            NetworkMessage("host", "NEXT_ROUND", "", "confirmed-next")
        ) { delivered = it }
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(true, delivered)
        val attempts = dataSource.publishAttempts.filter { it.message.messageId == "confirmed-next" }
        assertEquals(3, attempts.size)
        assertTrue(attempts.all { it.message.roundId == roundId })
    }

    @Test
    fun `confirmed retry accepts event already stored after response was lost`() = runTest {
        val dataSource = FakeOnlineRoomDataSource().apply { loseFirstPublishResponse = true }
        val repository = repository(dataSource)
        repository.startHosting("Host", config = MatchConfig())
        runCurrent()
        val message = NetworkMessage("host", "SERVE_MORTO", "private", "stable-morto")
        var delivered: Boolean? = null

        repository.sendMessageToSeatConfirmed(1, message) { delivered = it }
        advanceTimeBy(500)
        runCurrent()

        assertEquals(true, delivered)
        assertEquals(2, dataSource.publishAttempts.count { it.message.messageId == message.messageId })
        assertEquals(1, dataSource.published.count { it.message.messageId == message.messageId })
    }

    @Test
    fun `confirmed send reports failure after exhausting retries`() = runTest {
        val dataSource = FakeOnlineRoomDataSource().apply { failPublish = true }
        val repository = repository(dataSource)
        repository.startHosting("Host", config = MatchConfig())
        runCurrent()
        val message = NetworkMessage("host", "SERVE_CARD", "private", "failed-card")
        var delivered: Boolean? = null

        repository.sendMessageToSeatConfirmed(1, message) { delivered = it }
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(false, delivered)
        assertEquals(3, dataSource.publishAttempts.count { it.message.messageId == message.messageId })
        assertEquals(ConnectionStatus.ERROR, repository.connectionStatus.value)
    }

    @Test
    fun `active online session renews presence and stops after disconnect`() = runTest {
        val dataSource = FakeOnlineRoomDataSource()
        dataSource.waitingRooms.value = listOf(dataSource.roomSummary(roomCode = "BEAT2345"))
        val repository = repository(dataSource)
        repository.connectToRoom("BEAT2345", 0)
        runCurrent()

        assertEquals(1, dataSource.presenceTouches)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(2, dataSource.presenceTouches)

        repository.disconnect()
        runCurrent()
        val touchesAfterDisconnect = dataSource.presenceTouches
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(touchesAfterDisconnect, dataSource.presenceTouches)
    }

    @Test
    fun `send helpers reject messages when repository has no active session`() = runTest {
        val dataSource = FakeOnlineRoomDataSource()
        val repository = repository(dataSource)
        val message = NetworkMessage("host", "PING", "", "ping-1")

        repository.sendMessage(message)

        assertFalse(repository.sendMessageToClient(0, message))
        assertFalse(repository.sendMessageToPlayer("player", message))
        assertTrue(dataSource.published.isEmpty())
    }

    @Test
    fun `publish failure changes active session status to error`() = runTest {
        val dataSource = FakeOnlineRoomDataSource()
        val repository = repository(dataSource)
        repository.startHosting("Host", config = MatchConfig())
        runCurrent()
        dataSource.failPublish = true

        repository.sendMessage(NetworkMessage("host", "PING", "", "failed-publish"))
        runCurrent()

        assertEquals(ConnectionStatus.ERROR, repository.connectionStatus.value)
        // Telemetria sem mao/token/dado privado: so a categoria fechada da falha.
        assertEquals(listOf(OnlineFailureCategory.PUBLISH_FAILED), dataSource.reportedFailures)
    }

    @Test
    fun `rule rejection on publish does not change connection status and reports action rejection instead`() = runTest {
        // Achado real: antes disso, uma jogada recusada pelo banco (RPC/trigger
        // de validacao estrutural, ex.: CARD_NOT_IN_HAND) caia no mesmo catch
        // generico de falha de rede e virava ConnectionStatus.ERROR -- a UI
        // mostrava "conexao falhou" pra uma jogada simplesmente invalida.
        val dataSource = FakeOnlineRoomDataSource()
        val repository = repository(dataSource)
        repository.startHosting("Host", config = MatchConfig())
        runCurrent()
        dataSource.ruleRejectionOnPublish = "CARD_NOT_IN_HAND"
        val rejections = mutableListOf<String>()
        val collector = launch { repository.actionRejections.collect { rejections += it } }
        runCurrent()

        repository.sendMessage(NetworkMessage("host", "DISCARD", "", "rejected-discard"))
        runCurrent()

        assertEquals(listOf("CARD_NOT_IN_HAND"), rejections)
        assertEquals(ConnectionStatus.ROOM_READY, repository.connectionStatus.value)
        assertTrue(dataSource.reportedFailures.isEmpty())
        collector.cancel()
    }

    @Test
    fun `rule rejection on confirmed publish stops after first attempt without retrying`() = runTest {
        val dataSource = FakeOnlineRoomDataSource()
        val repository = repository(dataSource)
        repository.startHosting("Host", config = MatchConfig())
        runCurrent()
        dataSource.ruleRejectionOnPublish = "DISCARD_TOP_MISMATCH"
        val rejections = mutableListOf<String>()
        val collector = launch { repository.actionRejections.collect { rejections += it } }
        runCurrent()

        var confirmedResult: Boolean? = null
        repository.sendMessageConfirmed(NetworkMessage("host", "DISCARD", "", "rejected-confirmed")) {
            confirmedResult = it
        }
        runCurrent()

        assertEquals(false, confirmedResult)
        assertEquals(listOf("DISCARD_TOP_MISMATCH"), rejections)
        assertEquals(ConnectionStatus.ROOM_READY, repository.connectionStatus.value)
        assertTrue(dataSource.reportedFailures.isEmpty())
        // So uma tentativa: regra recusada e permanente, tentar de novo daria o mesmo erro.
        assertEquals(1, dataSource.publishAttempts.count { it.message.messageId == "rejected-confirmed" })
        collector.cancel()
    }

    @Test
    fun `realtime stream failure changes active session status to error`() = runTest {
        val dataSource = FakeOnlineRoomDataSource().apply { failEventStream = true }
        val repository = repository(dataSource)

        repository.startHosting("Host", config = MatchConfig())
        runCurrent()

        assertEquals(ConnectionStatus.ERROR, repository.connectionStatus.value)
        assertEquals(listOf(OnlineFailureCategory.SESSION_ERROR), dataSource.reportedFailures)
    }

    @Test
    fun `server deal request failure reports telemetry and returns null without crashing`() = runTest {
        val dataSource = FakeOnlineRoomDataSource().apply { failStartRound = true }
        val repository = repository(dataSource)
        repository.startHosting("Host", config = MatchConfig())
        runCurrent()

        var result: String? = "not called"
        repository.requestServerDeal { result = it }
        runCurrent()
        advanceTimeBy(251)
        runCurrent()

        assertEquals(null, result)
        assertEquals(listOf(OnlineFailureCategory.DEAL_REQUEST_FAILED), dataSource.reportedFailures)
    }

    @Test
    fun `server deal retry reuses the same idempotency key`() = runTest {
        val dataSource = FakeOnlineRoomDataSource().apply {
            startRoundResult = "{\"status\":\"OK\"}"
            startRoundFailuresRemaining = 1
        }
        val repository = repository(dataSource)
        repository.startHosting("Host", config = MatchConfig())
        runCurrent()

        var result: String? = null
        repository.requestServerDeal { result = it }
        runCurrent()
        advanceTimeBy(251)
        runCurrent()

        assertEquals(dataSource.startRoundResult, result)
        assertEquals(2, dataSource.startRoundRequestIds.size)
        assertEquals(1, dataSource.startRoundRequestIds.distinct().size)
    }

    @Test
    fun `server draw retry reuses the same idempotency key`() = runTest {
        val dataSource = FakeOnlineRoomDataSource().apply {
            drawDeckCardResult = "{\"status\":\"OK\",\"card\":\"ACE_SPADES_BLACK\"}"
            drawDeckFailuresRemaining = 1
        }
        val repository = repository(dataSource)
        repository.startHosting("Host", config = MatchConfig())
        runCurrent()

        var result: String? = null
        repository.requestServerDraw(0) { result = it }
        runCurrent()
        advanceTimeBy(251)
        runCurrent()

        assertEquals(dataSource.drawDeckCardResult, result)
        assertEquals(listOf(0, 0), dataSource.drawDeckCardCalls)
        assertEquals(1, dataSource.drawDeckRequestIds.distinct().size)
    }

    @Test
    fun `server morto retry preserves request key and indirect mode`() = runTest {
        val dataSource = FakeOnlineRoomDataSource().apply {
            takeMortoResult = "{\"status\":\"OK\",\"hand\":[]}"
            takeMortoFailuresRemaining = 1
        }
        val repository = repository(dataSource)
        repository.startHosting("Host", config = MatchConfig())
        runCurrent()

        var result: String? = null
        repository.requestServerMorto(1, indirect = true) { result = it }
        runCurrent()
        advanceTimeBy(251)
        runCurrent()

        assertEquals(dataSource.takeMortoResult, result)
        assertEquals(listOf(1, 1), dataSource.takeMortoCalls)
        assertEquals(listOf(true, true), dataSource.takeMortoIndirectCalls)
        assertEquals(1, dataSource.takeMortoRequestIds.distinct().size)
    }

    @Test
    fun `server morto rule rejection is returned without disconnecting the room`() = runTest {
        val dataSource = FakeOnlineRoomDataSource().apply {
            takeMortoRuleRejection = "INVALID_MORTO_HAND"
        }
        val repository = repository(dataSource)
        repository.startHosting("Host", config = MatchConfig())
        runCurrent()
        val rejections = mutableListOf<String>()
        val collector = launch { repository.actionRejections.collect { rejections += it } }
        runCurrent()

        var result: String? = null
        repository.requestServerMorto(1, indirect = false) { result = it }
        runCurrent()

        val response = JSONObject(result.orEmpty())
        assertEquals("REJECTED", response.getString("status"))
        assertEquals("INVALID_MORTO_HAND", response.getString("reason"))
        assertEquals(listOf("INVALID_MORTO_HAND"), rejections)
        assertEquals(listOf(1), dataSource.takeMortoCalls)
        assertEquals(ConnectionStatus.ROOM_READY, repository.connectionStatus.value)
        assertTrue(dataSource.reportedFailures.isEmpty())
        collector.cancel()
    }

    @Test
    fun `disconnect leaves client and removes reconnect target`() = runTest {
        val dataSource = FakeOnlineRoomDataSource()
        dataSource.waitingRooms.value = listOf(dataSource.roomSummary(roomCode = "OUT23456"))
        val repository = repository(dataSource)
        repository.connectToRoom("OUT23456", 0)
        runCurrent()

        repository.disconnect()
        runCurrent()

        assertEquals(1, dataSource.leftSessions.size)
        assertEquals(ConnectionStatus.IDLE, repository.connectionStatus.value)
        assertNull(repository.authenticatedPlayerId)
        assertFalse(repository.reconnect())
    }

    @Test
    fun `clearing account leaves room and signs out online identity`() = runTest {
        val dataSource = FakeOnlineRoomDataSource()
        dataSource.waitingRooms.value = listOf(dataSource.roomSummary(roomCode = "ACC23456"))
        val repository = repository(dataSource)
        repository.connectToRoom("ACC23456", 0)
        runCurrent()
        assertEquals("auth-client", repository.authenticatedPlayerId)

        repository.clearAuthenticatedSession()
        runCurrent()

        assertEquals(1, dataSource.leftSessions.size)
        assertEquals(1, dataSource.signOutCalls)
        assertNull(repository.authenticatedPlayerId)
        assertEquals(ConnectionStatus.IDLE, repository.connectionStatus.value)
    }

    @Test
    fun `reset status reflects whether current room reached full capacity`() = runTest {
        val dataSource = FakeOnlineRoomDataSource()
        val repository = repository(dataSource)
        repository.startHosting("Host", config = MatchConfig())
        runCurrent()

        repository.resetConnectionStatus()
        assertEquals(ConnectionStatus.IDLE, repository.connectionStatus.value)

        dataSource.connectedSeats.value = setOf(0, 1)
        runCurrent()
        repository.resetConnectionStatus()
        assertEquals(ConnectionStatus.CONNECTED, repository.connectionStatus.value)
    }

    @Test
    fun `host records final round summary only once`() = runTest {
        val dataSource = FakeOnlineRoomDataSource()
        val repository = repository(dataSource)
        repository.startHosting("Host", config = MatchConfig(gameType = GameType.TRANCA))
        runCurrent()

        val summary = NetworkMessage(
            senderId = "local-profile-id",
            type = "ROUND_SUMMARY",
            payload = completedSummaryPayload(
                winnerTeam = 1,
                teamScores = listOf(320, 610),
                breakdown = "Equipe 1 venceu."
            ),
            messageId = "result-1"
        )
        repository.sendMessage(summary)
        runCurrent()
        repository.sendMessage(summary)
        runCurrent()

        assertEquals(1, dataSource.completedMatches.size)
        assertEquals(
            OnlineCompletedMatch(
                resultKey = "result-1",
                winnerTeam = 1,
                teamScores = listOf(320, 610),
                breakdown = "Equipe 1 venceu."
            ),
            dataSource.completedMatches.single()
        )
    }

    @Test
    fun `round summary that has not finished match does not update ranking`() = runTest {
        val dataSource = FakeOnlineRoomDataSource()
        val repository = repository(dataSource)
        repository.startHosting("Host", config = MatchConfig())
        runCurrent()

        repository.sendMessage(
            NetworkMessage(
                senderId = "local-profile-id",
                type = "ROUND_SUMMARY",
                payload = completedSummaryPayload(
                    winnerTeam = 0,
                    teamScores = listOf(120, -20),
                    isMatchOver = false
                ),
                messageId = "round-only"
            )
        )
        runCurrent()

        assertTrue(dataSource.completedMatches.isEmpty())
    }

    @Test
    fun `ranking recording retries without breaking active match`() = runTest {
        val dataSource = FakeOnlineRoomDataSource().apply { resultFailuresRemaining = 2 }
        val repository = repository(dataSource)
        repository.startHosting("Host", config = MatchConfig())
        runCurrent()

        repository.sendMessage(
            NetworkMessage(
                senderId = "local-profile-id",
                type = "ROUND_SUMMARY",
                payload = completedSummaryPayload(0, listOf(1000, 850)),
                messageId = "result-retry"
            )
        )
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(3, dataSource.resultAttempts)
        assertEquals(1, dataSource.completedMatches.size)
        assertEquals(ConnectionStatus.ROOM_READY, repository.connectionStatus.value)
    }

    @Test
    fun `room chat message sent is delivered back marked as self`() = runTest {
        val dataSource = FakeOnlineRoomDataSource()
        dataSource.waitingRooms.value = listOf(dataSource.roomSummary(roomCode = "CHAT2345"))
        val repository = repository(dataSource)
        repository.connectToRoom("CHAT2345", 0)
        runCurrent()

        val received = mutableListOf<RoomChatMessage>()
        val collector = launch { repository.roomChatMessages.collect { received += it } }
        runCurrent()

        var sendResult: Boolean? = null
        repository.sendRoomChatMessage("oi pessoal") { sendResult = it }
        runCurrent()

        assertEquals(true, sendResult)
        assertEquals(listOf("oi pessoal"), dataSource.sentChatMessages)
        assertEquals(1, received.size)
        assertEquals("oi pessoal", received.single().body)
        assertEquals(1, received.single().senderSeat)
        assertTrue(received.single().isSelf)
        collector.cancel()
    }

    @Test
    fun `room chat message from another session is not marked as self`() = runTest {
        val dataSource = FakeOnlineRoomDataSource()
        dataSource.waitingRooms.value = listOf(dataSource.roomSummary(roomCode = "CHAT3456"))
        val repository = repository(dataSource)
        repository.connectToRoom("CHAT3456", 0)
        runCurrent()

        val received = mutableListOf<RoomChatMessage>()
        val collector = launch { repository.roomChatMessages.collect { received += it } }
        runCurrent()

        dataSource.emitChatMessage(senderId = "auth-host", senderSeat = 0, body = "oi do host")
        runCurrent()

        assertEquals(1, received.size)
        assertEquals("oi do host", received.single().body)
        assertFalse(received.single().isSelf)
        collector.cancel()
    }

    @Test
    fun `sending room chat without an active session fails immediately`() = runTest {
        val dataSource = FakeOnlineRoomDataSource()
        val repository = repository(dataSource)

        var sendResult: Boolean? = null
        repository.sendRoomChatMessage("ninguem vai ver isso") { sendResult = it }
        runCurrent()

        assertEquals(false, sendResult)
        assertTrue(dataSource.sentChatMessages.isEmpty())
    }

    private fun completedSummaryPayload(
        winnerTeam: Int,
        teamScores: List<Int>,
        breakdown: String = "",
        isMatchOver: Boolean = true
    ): String {
        return JSONObject()
            .put("isMatchOver", isMatchOver)
            .put("winnerTeam", winnerTeam)
            .put("teamScores", JSONArray(teamScores))
            .put("breakdown", breakdown)
            .toString()
    }

    private fun storedHostEvent(
        sequence: Long,
        messageId: String,
        type: String,
        roundId: String,
        gamePayloadRoundId: String? = null
    ): OnlineStoredEvent {
        val payload = gamePayloadRoundId
            ?.let { JSONObject().put("roundId", it).toString() }
            ?: "{}"
        return OnlineStoredEvent(
            sequence = sequence,
            actorPlayerId = "auth-host",
            actorSeat = 0,
            message = NetworkMessage(
                senderId = "host-domain",
                type = type,
                payload = payload,
                messageId = messageId,
                roundId = roundId
            )
        )
    }

    private fun kotlinx.coroutines.test.TestScope.repository(
        dataSource: FakeOnlineRoomDataSource,
        playerName: String = "Cliente"
    ): OnlineNetworkRepository {
        return OnlineNetworkRepository(
            dataSource = dataSource,
            playerNameProvider = { playerName },
            scope = backgroundScope
        )
    }
}

private data class PublishedEvent(
    val session: OnlineRoomSession,
    val message: NetworkMessage,
    val recipientSeat: Int?
)

private class FakeOnlineRoomDataSource : OnlineRoomDataSource {
    val waitingRooms = MutableStateFlow<List<OnlineRoomSummary>>(emptyList())
    val connectedSeats = MutableStateFlow<Set<Int>>(setOf(0))
    val published = mutableListOf<PublishedEvent>()
    val closedSessions = mutableListOf<OnlineRoomSession>()
    val leftSessions = mutableListOf<OnlineRoomSession>()
    val completedMatches = mutableListOf<OnlineCompletedMatch>()

    var createdPlayerName: String? = null
    var createdConfig: MatchConfig? = null
    var createdPassword: String? = null
    var discoveryPlayerName: String? = null
    var joinedPlayerName: String? = null
    var joinedRoomCode: String? = null
    var joinedPassword: String? = null
    var joinCalls: Int = 0
    var failJoin: Boolean = false
    var failPublish: Boolean = false
    var failEventStream: Boolean = false
    var publishFailuresRemaining: Int = 0
    var ruleRejectionOnPublish: String? = null
    var loseFirstPublishResponse: Boolean = false
    var presenceTouches: Int = 0
    var resultFailuresRemaining: Int = 0
    var resultAttempts: Int = 0
    var signOutCalls: Int = 0
    val publishDelayMsByMessageId = mutableMapOf<String, Long>()
    val sentChatMessages = mutableListOf<String>()

    private val events = MutableSharedFlow<OnlineStoredEvent>(extraBufferCapacity = 32)
    private val chatMessages = MutableSharedFlow<OnlineChatMessage>(extraBufferCapacity = 32)
    private val publishedById = mutableMapOf<String, PublishedEvent>()
    val publishAttempts = mutableListOf<PublishedEvent>()

    override suspend fun createRoom(
        playerName: String,
        roomCode: String,
        config: MatchConfig,
        password: String?
    ): OnlineRoomSession {
        createdPlayerName = playerName
        createdConfig = config
        createdPassword = password
        connectedSeats.value = setOf(0)
        return OnlineRoomSession(
            room = roomSummary(
                roomCode = roomCode,
                config = config,
                connectedPlayers = 1
            ),
            playerId = "auth-host",
            seat = 0,
            isHost = true
        )
    }

    override suspend fun listWaitingRooms(playerName: String): List<OnlineRoomSummary> {
        discoveryPlayerName = playerName
        return waitingRooms.value
    }

    override fun observeWaitingRooms(playerName: String): Flow<List<OnlineRoomSummary>> {
        discoveryPlayerName = playerName
        return waitingRooms
    }

    override suspend fun joinRoom(playerName: String, roomCode: String, password: String?): OnlineRoomSession {
        joinedPlayerName = playerName
        joinedRoomCode = roomCode
        joinedPassword = password
        joinCalls += 1
        if (failJoin) error("join failed")
        val room = waitingRooms.value.firstOrNull { it.roomCode == roomCode }
            ?: roomSummary(roomCode = roomCode)
        connectedSeats.value = (0 until room.config.maxPlayers).toSet()
        return OnlineRoomSession(
            room = room.copy(connectedPlayers = room.config.maxPlayers),
            playerId = "auth-client",
            seat = 1,
            isHost = false
        )
    }

    override suspend fun leaveRoom(session: OnlineRoomSession) {
        leftSessions += session
    }

    override suspend fun closeRoom(session: OnlineRoomSession) {
        closedSessions += session
    }

    override suspend fun signOut() {
        signOutCalls++
    }

    override suspend fun publishEvent(
        session: OnlineRoomSession,
        message: NetworkMessage,
        recipientSeat: Int?
    ): Boolean {
        delay(publishDelayMsByMessageId[message.messageId] ?: 0L)
        val event = PublishedEvent(session, message, recipientSeat)
        publishAttempts += event
        ruleRejectionOnPublish?.let { throw OnlineRuleRejectedException(it) }
        if (failPublish) error("publish failed")
        if (publishFailuresRemaining > 0) {
            publishFailuresRemaining--
            error("transient publish failure")
        }
        val stored = publishedById[message.messageId]
        if (stored != null) return stored == event
        publishedById[message.messageId] = event
        published += event
        if (loseFirstPublishResponse) {
            loseFirstPublishResponse = false
            error("response lost after insert")
        }
        return true
    }

    override suspend fun recordCompletedMatch(
        session: OnlineRoomSession,
        result: OnlineCompletedMatch
    ): Boolean {
        resultAttempts++
        if (resultFailuresRemaining > 0) {
            resultFailuresRemaining--
            error("transient result failure")
        }
        val existing = completedMatches.firstOrNull { it.resultKey == result.resultKey }
        if (existing != null) return existing == result
        completedMatches += result
        return true
    }

    override suspend fun touchPresence(session: OnlineRoomSession): Boolean {
        presenceTouches++
        return true
    }

    override fun observeEvents(
        session: OnlineRoomSession,
        afterSequence: Long
    ): Flow<OnlineStoredEvent> {
        if (failEventStream) return flow { error("realtime failed") }
        return events.filter { it.sequence > afterSequence }
    }

    override fun observeConnectedSeats(session: OnlineRoomSession): Flow<Set<Int>> = connectedSeats

    override suspend fun sendRoomChatMessage(session: OnlineRoomSession, body: String): Boolean {
        sentChatMessages += body
        chatMessages.emit(
            OnlineChatMessage(
                id = sentChatMessages.size.toLong(),
                senderId = session.playerId,
                senderSeat = session.seat,
                body = body,
                createdAt = "2026-01-01T00:00:00Z"
            )
        )
        return true
    }

    override fun observeRoomChat(session: OnlineRoomSession): Flow<OnlineChatMessage> = chatMessages

    var startRoundResult: String = "{}"
    var startRoundCalls: Int = 0
    var failStartRound: Boolean = false
    var startRoundFailuresRemaining: Int = 0
    val startRoundRequestIds = mutableListOf<String>()

    override suspend fun startRound(session: OnlineRoomSession, requestId: String): String {
        startRoundCalls++
        startRoundRequestIds += requestId
        if (startRoundFailuresRemaining > 0) {
            startRoundFailuresRemaining--
            error("start round response lost")
        }
        if (failStartRound) error("start round failed")
        return startRoundResult
    }

    var drawDeckCardResult: String = "{}"
    var drawDeckCardCalls: MutableList<Int> = mutableListOf()
    var failDrawDeckCard: Boolean = false
    var drawDeckFailuresRemaining: Int = 0
    val drawDeckRequestIds = mutableListOf<String>()

    override suspend fun drawDeckCard(session: OnlineRoomSession, seat: Int, requestId: String): String {
        drawDeckCardCalls += seat
        drawDeckRequestIds += requestId
        if (drawDeckFailuresRemaining > 0) {
            drawDeckFailuresRemaining--
            error("draw response lost")
        }
        if (failDrawDeckCard) error("draw deck card failed")
        return drawDeckCardResult
    }

    var takeMortoResult: String = "{}"
    var takeMortoCalls: MutableList<Int> = mutableListOf()
    var failTakeMorto: Boolean = false
    var takeMortoFailuresRemaining: Int = 0
    var takeMortoRuleRejection: String? = null
    val takeMortoRequestIds = mutableListOf<String>()
    val takeMortoIndirectCalls = mutableListOf<Boolean>()

    override suspend fun takeMorto(
        session: OnlineRoomSession,
        seat: Int,
        indirect: Boolean,
        requestId: String
    ): String {
        takeMortoCalls += seat
        takeMortoRequestIds += requestId
        takeMortoIndirectCalls += indirect
        takeMortoRuleRejection?.let { throw OnlineRuleRejectedException(it) }
        if (takeMortoFailuresRemaining > 0) {
            takeMortoFailuresRemaining--
            error("morto response lost")
        }
        if (failTakeMorto) error("take morto failed")
        return takeMortoResult
    }

    var remoteHandResult: String = "{}"
    val remoteHandCalls = mutableListOf<Int>()

    override suspend fun loadRemoteHand(session: OnlineRoomSession, seat: Int): String {
        remoteHandCalls += seat
        return remoteHandResult
    }

    val reportedFailures = mutableListOf<OnlineFailureCategory>()

    override suspend fun reportFailure(category: OnlineFailureCategory, roomId: String?) {
        reportedFailures += category
    }

    fun emitEvent(event: OnlineStoredEvent) {
        assertTrue(events.tryEmit(event))
    }

    fun emitChatMessage(senderId: String?, senderSeat: Int?, body: String) {
        assertTrue(
            chatMessages.tryEmit(
                OnlineChatMessage(
                    id = sentChatMessages.size.toLong() + 1,
                    senderId = senderId,
                    senderSeat = senderSeat,
                    body = body,
                    createdAt = "2026-01-01T00:00:00Z"
                )
            )
        )
    }

    fun roomSummary(
        roomCode: String,
        config: MatchConfig = MatchConfig(maxPlayers = 2),
        connectedPlayers: Int = 1
    ): OnlineRoomSummary {
        return OnlineRoomSummary(
            roomId = "room-$roomCode",
            roomCode = roomCode,
            hostPlayerId = "auth-host",
            config = config,
            status = OnlineRoomStatus.WAITING,
            connectedPlayers = connectedPlayers
        )
    }
}

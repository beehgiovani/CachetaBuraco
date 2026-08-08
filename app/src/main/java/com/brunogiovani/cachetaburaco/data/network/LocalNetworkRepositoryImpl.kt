package com.brunogiovani.cachetaburaco.data.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import com.brunogiovani.cachetaburaco.domain.models.MatchConfig
import com.brunogiovani.cachetaburaco.domain.repositories.ConnectionStatus
import com.brunogiovani.cachetaburaco.domain.repositories.DiscoveredRoom
import com.brunogiovani.cachetaburaco.domain.repositories.LocalNetworkRepository
import com.brunogiovani.cachetaburaco.domain.repositories.NetworkMessage
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * Transporte de rede local via NSD + socket TCP.
 *
 * Esta classe cuida só da parte de conexão: anunciar sala, descobrir mesas na rede,
 * manter socket aberto, enviar JSON e transformar linhas recebidas em NetworkMessage.
 * Regra de Cacheta, Buraco e Tranca fica fora daqui.
 *
 * Quando entrar o online, ele deve nascer em outra implementação da interface,
 * reaproveitando o mesmo protocolo para não quebrar o MatchViewModel.
 */
class LocalNetworkRepositoryImpl(private val context: Context) : LocalNetworkRepository {
    override val requiresClientReadyHandshake: Boolean = true

    private val sendExecutor = Executors.newSingleThreadExecutor()

    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val serviceType = "_cachetaburaco._tcp."
    private var serviceName = "CachetaRoom"

    private val _discoveredRooms = MutableStateFlow<List<DiscoveredRoom>>(emptyList())
    override val discoveredRooms: StateFlow<List<DiscoveredRoom>> = _discoveredRooms.asStateFlow()

    private val _connectedClientsCount = MutableStateFlow(0)
    override val connectedClientsCount: StateFlow<Int> = _connectedClientsCount.asStateFlow()

    // Buffer extra para rajadas do mesmo turno, como DISCARD + REQ_PICK_MORTO.
    private val _incomingMessages = MutableSharedFlow<NetworkMessage>(extraBufferCapacity = 64)
    override val incomingMessages: SharedFlow<NetworkMessage> = _incomingMessages.asSharedFlow()

    private val _connectionStatus = MutableStateFlow(ConnectionStatus.IDLE)
    override val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private var clientSocket: Socket? = null
    private val connectedClients = CopyOnWriteArrayList<Socket>()
    private val playerSockets = ConcurrentHashMap<String, Socket>()
    private val clientSeats = ConcurrentHashMap<Socket, Int>()
    private val rememberedPlayerSeats = ConcurrentHashMap<String, Int>()
    private val seatLock = Any()
    @Volatile private var hostedMaxClients = 1
    private var hostJob: Job? = null
    private var clientJob: Job? = null
    private var heartbeatJob: Job? = null
    private val nsdLock = Any()
    @Volatile private var isDiscovering = false
    private var activeDiscoveryListener: NsdManager.DiscoveryListener? = null

    private val coroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 5000L
        private const val MAX_MESSAGE_LENGTH = 64_000
        private const val TAG = "Network"
    }

    // --- HOST (SERVER) ---
    private val registrationListener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(nsdServiceInfo: NsdServiceInfo) {
            serviceName = nsdServiceInfo.serviceName
            Log.d("NSD", "Service registered: $serviceName")
            _connectionStatus.value = if (connectedClients.isEmpty()) {
                ConnectionStatus.ROOM_READY
            } else {
                ConnectionStatus.CONNECTED
            }
        }
        override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
            Log.e("NSD", "Registration failed: $errorCode")
            _connectionStatus.value = ConnectionStatus.ERROR
        }
        override fun onServiceUnregistered(arg0: NsdServiceInfo) {}
        override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
    }

    override fun startHosting(playerName: String, port: Int, config: MatchConfig?) {
        stopHosting()
        hostedMaxClients = ((config?.maxPlayers ?: 2) - 1).coerceIn(1, 3)

        hostJob = coroutineScope.launch {
            try {
                serverSocket = ServerSocket(port)
                registerService(playerName, port, config)
                _connectionStatus.value = ConnectionStatus.IDLE

                while (isActive) {
                    val socket = serverSocket?.accept() ?: break
                    val assignedSeat = synchronized(seatLock) {
                        (1..hostedMaxClients).firstOrNull { candidate -> candidate !in clientSeats.values }
                    }
                    if (assignedSeat == null) {
                        socket.close()
                        continue
                    }
                    connectedClients.add(socket)
                    clientSeats[socket] = assignedSeat
                    _connectedClientsCount.value = connectedClients.size
                    _connectionStatus.value = ConnectionStatus.CONNECTED
                    handleClientConnection(socket)
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Server Error: ${e.message}")
                    _connectionStatus.value = ConnectionStatus.ERROR
                }
            }
        }
    }

    private fun handleClientConnection(socket: Socket) {
        coroutineScope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                while (isActive) {
                    val line = reader.readLine() ?: break // null = client desconectou
                    
                    // Ignora heartbeats
                    if (line.trim() == "PING") continue
                    
                    val parsed = parseNetworkMessage(line) ?: continue
                    val provisionalSeat = clientSeats[socket] ?: continue
                    val trustedSeat = bindClientSeat(socket, parsed.senderId, provisionalSeat) ?: run {
                        Log.w(TAG, "Closing duplicate or conflicting player connection")
                        break
                    }
                    val msg = parsed.copy(senderSeat = trustedSeat)
                    playerSockets[msg.senderId] = socket
                    _incomingMessages.emit(msg)
                    // Pedido de cliente vai somente ao host. Depois da validacao, o host publica
                    // o PUBLIC_STATE canonico para todos e evita espalhar uma jogada forjada.
                }
            } catch (e: Exception) {
                if (isActive) Log.e(TAG, "Client read error: ${e.message}")
            } finally {
                connectedClients.remove(socket)
                clientSeats.remove(socket)
                playerSockets.entries.removeIf { it.value == socket }
                _connectedClientsCount.value = connectedClients.size
                socket.close()
                
                // Notifica apenas se ainda há jogo em andamento (serverSocket ativo)
                if (serverSocket?.isClosed == false) {
                    Log.w(TAG, "Opponent disconnected from server")
                    _connectionStatus.value = ConnectionStatus.OPPONENT_DISCONNECTED
                }
            }
        }
    }

    /**
     * Depois da primeira mensagem, o socket fica preso ao assento daquela identidade.
     * Na reconexao eu reaproveito o assento antigo, mas nunca permito dois sockets no mesmo lugar.
     */
    private fun bindClientSeat(socket: Socket, playerId: String, provisionalSeat: Int): Int? {
        return synchronized(seatLock) {
            val previousSocket = playerSockets[playerId]
            if (previousSocket != null && previousSocket !== socket && !previousSocket.isClosed) {
                return@synchronized null
            }

            val desiredSeat = rememberedPlayerSeats[playerId] ?: provisionalSeat
            val occupiedByAnotherSocket = clientSeats.entries.any { (otherSocket, seat) ->
                otherSocket !== socket && seat == desiredSeat && !otherSocket.isClosed
            }
            if (occupiedByAnotherSocket) return@synchronized null

            clientSeats[socket] = desiredSeat
            rememberedPlayerSeats.putIfAbsent(playerId, desiredSeat)
            desiredSeat
        }
    }

    private fun registerService(playerName: String, port: Int, config: MatchConfig?) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "Room_$playerName"
            serviceType = this@LocalNetworkRepositoryImpl.serviceType
            setPort(port)
            config?.serialize()?.let { setAttribute("cfg", it) }
        }
        nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
    }

    override fun presetRememberedSeats(seats: Map<String, Int>) {
        rememberedPlayerSeats.putAll(seats)
    }

    override fun stopHosting() {
        try { nsdManager.unregisterService(registrationListener) } catch (_: Exception) {}
        hostJob?.cancel()
        heartbeatJob?.cancel()
        serverSocket?.close()
        connectedClients.forEach { it.close() }
        connectedClients.clear()
        playerSockets.clear()
        clientSeats.clear()
        rememberedPlayerSeats.clear()
        serverSocket = null
        hostJob = null
        heartbeatJob = null
        _connectedClientsCount.value = 0
        _connectionStatus.value = ConnectionStatus.IDLE
    }

    // --- CLIENT (DISCOVERY & CONNECT) ---

    private fun resolveService(service: NsdServiceInfo) {
        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e("NSD", "Resolve failed: $errorCode")
            }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val hostAddress = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    serviceInfo.hostAddresses.firstOrNull()?.hostAddress
                } else {
                    @Suppress("DEPRECATION")
                    serviceInfo.host?.hostAddress
                } ?: return

                val room = DiscoveredRoom(
                    serviceName = serviceInfo.serviceName,
                    host = hostAddress,
                    port = serviceInfo.port,
                    config = serviceInfo.attributes["cfg"]
                        ?.toString(Charsets.UTF_8)
                        ?.let { runCatching { MatchConfig.deserialize(it) }.getOrNull() }
                )
                val currentList = _discoveredRooms.value.toMutableList()
                val existingIndex = currentList.indexOfFirst { it.serviceName == room.serviceName }
                if (existingIndex >= 0) {
                    currentList[existingIndex] = room
                    _discoveredRooms.value = currentList
                } else {
                    currentList.add(room)
                    _discoveredRooms.value = currentList
                }
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val executor = Executor { command -> command.run() }
            @Suppress("DEPRECATION")
            nsdManager.resolveService(service, executor, resolveListener)
        } else {
            @Suppress("DEPRECATION")
            nsdManager.resolveService(service, resolveListener)
        }
    }

    private fun createDiscoveryListener(): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d("NSD", "Discovery started: $regType")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                if (service.serviceType.contains(serviceType.trimEnd('.'))) {
                    resolveService(service)
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                val currentList = _discoveredRooms.value.toMutableList()
                currentList.removeAll { it.serviceName == service.serviceName }
                _discoveredRooms.value = currentList
            }

            override fun onDiscoveryStopped(serviceType: String) {
                markDiscoveryStopped(this)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e("NSD", "Discovery start failed: $errorCode")
                try { nsdManager.stopServiceDiscovery(this) } catch (_: Exception) {}
                markDiscoveryStopped(this)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e("NSD", "Discovery stop failed: $errorCode")
                markDiscoveryStopped(this)
            }
        }
    }

    private fun markDiscoveryStopped(listener: NsdManager.DiscoveryListener) {
        synchronized(nsdLock) {
            if (activeDiscoveryListener === listener) {
                activeDiscoveryListener = null
                isDiscovering = false
            }
        }
    }

    override fun startDiscovery() {
        val listener = synchronized(nsdLock) {
            if (isDiscovering) {
                Log.d("NSD", "Discovery already running; ignoring duplicate start")
                return
            }
            createDiscoveryListener().also {
                activeDiscoveryListener = it
                isDiscovering = true
            }
        }

        _discoveredRooms.value = emptyList()
        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        } catch (e: IllegalArgumentException) {
            Log.w("NSD", "Discovery listener rejected: ${e.message}")
            markDiscoveryStopped(listener)
        } catch (e: Exception) {
            Log.e("NSD", "Discovery start error: ${e.message}")
            markDiscoveryStopped(listener)
        }
    }

    override fun stopDiscovery() {
        val listener = synchronized(nsdLock) {
            activeDiscoveryListener.also {
                activeDiscoveryListener = null
                isDiscovering = false
            }
        } ?: return

        try { nsdManager.stopServiceDiscovery(listener) } catch (_: Exception) {}
    }

    private var lastConnectedHost: String? = null
    private var lastConnectedPort: Int? = null

    override fun reconnect(): Boolean {
        val h = lastConnectedHost ?: return false
        val p = lastConnectedPort ?: return false
        connectToRoom(h, p)
        return true
    }

    override fun connectToRoom(host: String, port: Int) {
        lastConnectedHost = host
        lastConnectedPort = port
        clientJob = coroutineScope.launch {
            try {
                clientSocket = Socket(host, port)
                _connectionStatus.value = ConnectionStatus.CONNECTED
                startHeartbeat()
                
                val socket = clientSocket ?: return@launch
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                while (isActive) {
                    val line = reader.readLine() ?: break // null = host desconectou
                    
                    if (line.trim() == "PING") continue
                    
                    val msg = parseNetworkMessage(
                        line = line,
                        acceptSenderSeatFromHost = true,
                        defaultSenderSeat = 0
                    ) ?: continue
                    _incomingMessages.emit(msg)
                }
                
                // Se chegou aqui, o host fechou a conexão
                if (isActive) {
                    Log.w(TAG, "Host disconnected (stream ended)")
                    _connectionStatus.value = ConnectionStatus.HOST_DISCONNECTED
                }
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "Client connect error: ${e.message}")
                    _connectionStatus.value = ConnectionStatus.HOST_DISCONNECTED
                }
            } finally {
                heartbeatJob?.cancel()
            }
        }
    }

    /** Envia PING periódico para manter a conexão TCP viva e detectar queda. */
    private fun startHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = coroutineScope.launch {
            while (isActive) {
                delay(HEARTBEAT_INTERVAL_MS)
                try {
                    val socket = clientSocket ?: break
                    val writer = PrintWriter(socket.getOutputStream(), true)
                    writer.println("PING")
                } catch (e: Exception) {
                    break // Conexão caiu
                }
            }
        }
    }

    override fun disconnect() {
        heartbeatJob?.cancel()
        clientJob?.cancel()
        clientSocket?.close()
        heartbeatJob = null
        clientJob = null
        clientSocket = null
        _connectionStatus.value = ConnectionStatus.IDLE
    }

    override fun sendMessage(message: NetworkMessage) {
        val json = message.toNetworkJson()
        if (json.length > MAX_MESSAGE_LENGTH) {
            Log.w(TAG, "Blocking oversized outbound network message")
            return
        }

        sendExecutor.execute {
            if (serverSocket?.isClosed == false) {
                broadcastMessage(json, null)
            } else {
                val socket = clientSocket?.takeUnless { it.isClosed } ?: return@execute
                try {
                    val writer = PrintWriter(socket.getOutputStream(), true)
                    writer.println(json)
                } catch (_: Exception) {}
            }
        }
    }

    override fun sendMessageToClient(clientIndex: Int, message: NetworkMessage): Boolean {
        val socket = connectedClients.getOrNull(clientIndex) ?: return false
        return sendJsonToSocket(socket, message)
    }

    override fun sendMessageToSeat(seat: Int, message: NetworkMessage): Boolean {
        if (seat !in 1..hostedMaxClients) return false
        val socket = clientSeats.entries.firstOrNull { (candidate, assignedSeat) ->
            assignedSeat == seat && !candidate.isClosed
        }?.key ?: return false
        return sendJsonToSocket(socket, message)
    }

    override fun sendMessageToPlayer(playerId: String, message: NetworkMessage): Boolean {
        val socket = playerSockets[playerId] ?: return false
        return sendJsonToSocket(socket, message)
    }

    override fun sendMessageToSeatConfirmed(
        seat: Int,
        message: NetworkMessage,
        onResult: (Boolean) -> Unit
    ) {
        val socket = clientSeats.entries.firstOrNull { (candidate, assignedSeat) ->
            assignedSeat == seat && !candidate.isClosed
        }?.key
        if (socket == null) {
            onResult(false)
            return
        }
        sendJsonToSocketConfirmed(socket, message, onResult)
    }

    override fun sendMessageToPlayerConfirmed(
        playerId: String,
        message: NetworkMessage,
        onResult: (Boolean) -> Unit
    ) {
        val socket = playerSockets[playerId]
        if (socket == null) {
            onResult(false)
            return
        }
        sendJsonToSocketConfirmed(socket, message, onResult)
    }

    private fun sendJsonToSocket(socket: Socket, message: NetworkMessage): Boolean {
        val json = message.toNetworkJson()
        if (json.length > MAX_MESSAGE_LENGTH) {
            Log.w(TAG, "Blocking oversized outbound private network message")
            return false
        }

        sendExecutor.execute {
            try {
                val writer = PrintWriter(socket.getOutputStream(), true)
                writer.println(json)
            } catch (e: Exception) {
                Log.w(TAG, "Private message failed: ${e.message}")
            }
        }
        return true
    }

    private fun sendJsonToSocketConfirmed(
        socket: Socket,
        message: NetworkMessage,
        onResult: (Boolean) -> Unit
    ) {
        val json = message.toNetworkJson()
        if (json.length > MAX_MESSAGE_LENGTH || socket.isClosed) {
            onResult(false)
            return
        }
        sendExecutor.execute {
            val delivered = runCatching {
                val writer = PrintWriter(socket.getOutputStream(), true)
                writer.println(json)
                !writer.checkError()
            }.getOrDefault(false)
            onResult(delivered)
        }
    }

    override fun resetConnectionStatus() {
        _connectionStatus.value = ConnectionStatus.CONNECTED
    }

    private fun parseNetworkMessage(
        line: String,
        acceptSenderSeatFromHost: Boolean = false,
        defaultSenderSeat: Int? = null
    ): NetworkMessage? {
        if (line.length > MAX_MESSAGE_LENGTH) {
            Log.w(TAG, "Ignoring oversized network message")
            return null
        }

        return runCatching {
            val json = JSONObject(line)
            val senderId = json.optString("senderId")
            val type = json.optString("type")
            val messageId = json.optString("messageId")
            if (senderId.isBlank() || type.isBlank() || messageId.isBlank()) return null
            val envelopeSeat = json.optInt("senderSeat", -1).takeIf { it >= 0 }
            val roundId = json.optString("roundId").takeIf { it.isNotBlank() }

            NetworkMessage(
                senderId = senderId,
                type = type,
                payload = json.optString("payload"),
                messageId = messageId,
                senderSeat = if (acceptSenderSeatFromHost) envelopeSeat ?: defaultSenderSeat else defaultSenderSeat,
                roundId = roundId
            )
        }.getOrElse {
            Log.w(TAG, "Ignoring malformed network message: ${it.message}")
            null
        }
    }

    private fun broadcastMessage(json: String, excludeSocket: Socket?) {
        connectedClients.forEach { socket ->
            if (socket != excludeSocket) {
                try {
                    val writer = PrintWriter(socket.getOutputStream(), true)
                    writer.println(json)
                } catch (_: Exception) {}
            }
        }
    }

    private fun NetworkMessage.toNetworkJson(): String =
        JSONObject().apply {
            put("senderId", senderId)
            put("type", type)
            put("payload", payload)
            put("messageId", messageId)
            senderSeat?.let { put("senderSeat", it) }
            roundId?.let { put("roundId", it) }
        }.toString()
}

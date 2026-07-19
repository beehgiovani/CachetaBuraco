package com.brunogiovani.cachetaburaco.domain.repositories

import com.brunogiovani.cachetaburaco.domain.models.MatchConfig
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

data class NetworkMessage(
    val senderId: String,
    val type: String,
    val payload: String,
    val messageId: String = UUID.randomUUID().toString()
)

data class DiscoveredRoom(
    val serviceName: String,
    val host: String,
    val port: Int,
    val config: MatchConfig? = null
)

enum class ConnectionStatus {
    IDLE,
    CONNECTED,
    OPPONENT_DISCONNECTED,  // Oponente caiu
    HOST_DISCONNECTED,       // Host caiu (cliente perdeu conexão)
    ERROR
}

interface LocalNetworkRepository {
    val discoveredRooms: StateFlow<List<DiscoveredRoom>>
    val connectedClientsCount: StateFlow<Int>
    val incomingMessages: StateFlow<NetworkMessage?>
    val connectionStatus: StateFlow<ConnectionStatus>

    fun startHosting(playerName: String, port: Int = 9090, config: MatchConfig? = null)
    fun stopHosting()
    
    fun startDiscovery()
    fun stopDiscovery()
    
    fun connectToRoom(host: String, port: Int)
    fun disconnect()
    
    fun sendMessage(message: NetworkMessage)
    fun sendMessageToClient(clientIndex: Int, message: NetworkMessage): Boolean
    fun sendMessageToPlayer(playerId: String, message: NetworkMessage): Boolean
    fun resetConnectionStatus()
}

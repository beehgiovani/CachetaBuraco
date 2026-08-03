package com.brunogiovani.cachetaburaco.domain.repositories

import com.brunogiovani.cachetaburaco.domain.models.MatchConfig
import kotlinx.coroutines.flow.SharedFlow
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

/**
 * Contrato de transporte da mesa.
 *
 * O ViewModel conversa com esta interface sem saber se a partida está usando
 * Wi-Fi local, máquina ou futuro servidor online. As mensagens de domínio ficam
 * iguais em todos os modos: GAME_START, REQ_DRAW_DECK, SERVE_CARD, MELD, DISCARD,
 * PICK_MORTO e as respostas de contagem/reconexão.
 *
 * Aqui não entra regra de jogo; só conexão, descoberta de sala e entrega de
 * NetworkMessage. O messageId segue junto para deduplicar eventos repetidos.
 */
interface LocalNetworkRepository {
    val discoveredRooms: StateFlow<List<DiscoveredRoom>>
    val connectedClientsCount: StateFlow<Int>
    // Buffer pequeno para segurar rajadas no mesmo turno, como DISCARD + REQ_PICK_MORTO.
    val incomingMessages: SharedFlow<NetworkMessage>
    val connectionStatus: StateFlow<ConnectionStatus>

    fun startHosting(playerName: String, port: Int = 9090, config: MatchConfig? = null)
    fun stopHosting()
    
    fun startDiscovery()
    fun stopDiscovery()
    
    fun connectToRoom(host: String, port: Int)
    fun reconnect(): Boolean
    fun disconnect()
    
    fun sendMessage(message: NetworkMessage)
    fun sendMessageToClient(clientIndex: Int, message: NetworkMessage): Boolean
    fun sendMessageToPlayer(playerId: String, message: NetworkMessage): Boolean
    fun resetConnectionStatus()
}

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
 * Contrato unico de comunicacao da mesa.
 *
 * Eu mantenho o MatchViewModel dependente desta interface para que o jogo nao saiba
 * se esta falando com rede local, maquina ou servidor online. Quando eu for colocar
 * o online, a ideia e criar outro implementation aqui, por exemplo
 * OnlineNetworkRepository, mantendo as mesmas mensagens de dominio:
 * GAME_START, REQ_DRAW_DECK, SERVE_CARD, MELD, DISCARD, PICK_MORTO etc.
 *
 * Responsabilidade desta camada:
 * - descobrir/criar sala quando o transporte precisar disso;
 * - enviar e receber NetworkMessage sem aplicar regra de jogo;
 * - expor status de conexao para a UI;
 * - preservar messageId para o ViewModel deduplicar eventos.
 */
interface LocalNetworkRepository {
    val discoveredRooms: StateFlow<List<DiscoveredRoom>>
    val connectedClientsCount: StateFlow<Int>
    // SharedFlow garante que NENHUMA mensagem seja descartada,
    // mesmo quando chegam em rajada (ex: DISCARD + REQ_PICK_MORTO).
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

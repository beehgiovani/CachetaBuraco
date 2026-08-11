package com.brunogiovani.cachetaburaco.domain.repositories

import com.brunogiovani.cachetaburaco.domain.models.MatchConfig
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

data class NetworkMessage(
    val senderId: String,
    val type: String,
    val payload: String,
    val messageId: String = UUID.randomUUID().toString(),
    // Preenchido pelo transporte com o assento que ele autenticou. Nunca vem do payload do jogador.
    val senderSeat: Int? = null,
    // Separa eventos de rodadas diferentes quando a mesma sala e reutilizada.
    val roundId: String? = null
)

// Chat de sala (so online, migration 0032) -- mensagens somem quando a
// partida encerra, entao nao ha necessidade de historico persistente aqui.
data class RoomChatMessage(
    val senderSeat: Int?,
    val body: String,
    val isSelf: Boolean
)

data class DiscoveredRoom(
    val serviceName: String,
    val host: String,
    val port: Int,
    val config: MatchConfig? = null
)

enum class ConnectionStatus {
    IDLE,
    ROOM_READY,             // Sala criada e pronta para receber jogadores.
    CONNECTED,
    OPPONENT_DISCONNECTED,  // Oponente caiu.
    HOST_DISCONNECTED,      // Host caiu, cliente perdeu conexao.
    ERROR
}

/**
 * Contrato de transporte da mesa.
 *
 * O ViewModel conversa com esta interface sem saber se a partida esta usando
 * Wi-Fi local, maquina ou futuro servidor online. As mensagens de dominio ficam
 * iguais em todos os modos: GAME_START, REQ_DRAW_DECK, SERVE_CARD, MELD,
 * DISCARD, PICK_MORTO e as respostas de contagem/reconexao.
 *
 * Aqui nao entra regra de jogo; so conexao, descoberta de sala e entrega de
 * NetworkMessage. O messageId segue junto para deduplicar eventos repetidos.
 */
interface LocalNetworkRepository {
    val requiresClientReadyHandshake: Boolean get() = false

    // No online, esta e a identidade autenticada que o servidor coloca nos eventos.
    // Wi-Fi local e maquina continuam usando o id salvo no aparelho.
    val authenticatedPlayerId: String? get() = null

    // Identifica o transporte da maquina sem depender do nome da classe, que o
    // R8/ProGuard pode renomear em builds de release.
    val isBotRepository: Boolean get() = false

    // Diferencia sala online (Supabase/internet) de sala Wi-Fi local, para a UI
    // nao dizer "mesma rede Wi-Fi" enquanto procura uma sala online.
    val isOnlineTransport: Boolean get() = false

    val discoveredRooms: StateFlow<List<DiscoveredRoom>>
    val connectedClientsCount: StateFlow<Int>

    // Buffer pequeno para segurar rajadas no mesmo turno, como DISCARD + REQ_PICK_MORTO.
    val incomingMessages: SharedFlow<NetworkMessage>
    val connectionStatus: StateFlow<ConnectionStatus>

    // So o online popula isso de verdade: o servidor recusou uma acao PROPRIA
    // por regra (RPC/trigger de validacao estrutural), nao por queda de rede --
    // sinal deliberadamente separado de connectionStatus pra "sua jogada foi
    // recusada" nunca virar "a conexao caiu" (achado real: antes das duas
    // coisas ca(i)am no mesmo catch generico e a UI mostrava o dialogo de
    // reconexao pra uma jogada simplesmente invalida). Wi-Fi local e maquina
    // nunca emitem aqui -- ficam com este padrao vazio.
    val actionRejections: SharedFlow<String> get() = MutableSharedFlow()

    // So o online popula isso de verdade: o config de uma sala com senha so e
    // conhecido depois do join_match_room responder (sala privada nao aparece
    // na lista de descoberta, entao quem entra por codigo nao tem o config
    // antecipado como no fluxo normal de "Entrar" a partir de uma sala
    // listada). Wi-Fi local e maquina continuam com o padrao (null) porque ja
    // conhecem o config antes de conectar.
    val joinedRoomConfig: StateFlow<MatchConfig?> get() = MutableStateFlow(null)

    // So o online popula isso de verdade: chat de sala (migration 0032).
    // Wi-Fi local e maquina nao tem chat nesta leva -- ficam com o padrao vazio.
    val roomChatMessages: SharedFlow<RoomChatMessage> get() = MutableSharedFlow()

    fun sendRoomChatMessage(body: String, onResult: (Boolean) -> Unit = {}) {
        onResult(false)
    }

    // `password` so o transporte online usa (sala privada) -- Wi-Fi local e
    // maquina ignoram, ja que nao tem conceito de sala com senha.
    fun startHosting(playerName: String, port: Int = 9090, config: MatchConfig? = null, password: String? = null)
    fun stopHosting()

    // Só o transporte Wi-Fi local implementa isto de verdade: reaplica, antes de
    // aceitar a primeira conexão, o assento que cada playerId tinha antes de um
    // reinício do app -- sem isso, "Continuar Partida Salva" reabriria a sala mas
    // o primeiro cliente a reconectar sempre cairia no assento 1, embaralhando
    // quem senta onde numa partida de 4.
    fun presetRememberedSeats(seats: Map<String, Int>) = Unit

    fun startDiscovery()
    fun stopDiscovery()

    fun connectToRoom(host: String, port: Int, password: String? = null)
    fun reconnect(): Boolean
    fun disconnect()
    fun clearAuthenticatedSession() = Unit

    fun sendMessage(message: NetworkMessage)
    fun sendMessageConfirmed(message: NetworkMessage, onResult: (Boolean) -> Unit) {
        sendMessage(message)
        onResult(true)
    }
    fun sendMessageToClient(clientIndex: Int, message: NetworkMessage): Boolean
    fun sendMessageToSeat(seat: Int, message: NetworkMessage): Boolean {
        if (seat <= 0) return false
        return sendMessageToClient(seat - 1, message)
    }
    fun sendMessageToPlayer(playerId: String, message: NetworkMessage): Boolean
    fun sendMessageToSeatConfirmed(
        seat: Int,
        message: NetworkMessage,
        onResult: (Boolean) -> Unit
    ) {
        onResult(sendMessageToSeat(seat, message))
    }
    fun sendMessageToPlayerConfirmed(
        playerId: String,
        message: NetworkMessage,
        onResult: (Boolean) -> Unit
    ) {
        onResult(sendMessageToPlayer(playerId, message))
    }
    fun resetConnectionStatus()

    // So o transporte online implementa isto de verdade: pede pro servidor
    // embaralhar e distribuir a rodada (RPC start_online_round) em vez do host
    // fazer isso sozinho no aparelho. Wi-Fi local e maquina nao tem servidor,
    // entao ficam com o padrao (null) e o ViewModel continua com a distribuicao
    // local de sempre. O JSON bruto e repassado pro ViewModel interpretar,
    // igual qualquer outro payload de NetworkMessage.
    fun requestServerDeal(onResult: (String?) -> Unit) {
        onResult(null)
    }

    // So o online implementa isto de verdade. Bug real encontrado: quando a
    // distribuicao virou responsabilidade do servidor (RPC start_online_round,
    // que publica o GAME_START direto via SQL), o host parou de mandar esse
    // evento ele mesmo -- e so quem manda GAME_START pelo caminho antigo
    // (prepareOutgoingMessage) e quem recebe um GAME_START de outro remetente
    // (acceptIncomingRound) marcavam a rodada como ativa dentro do
    // OnlineNetworkRepository. O host nunca faz nenhum dos dois (nao manda
    // mais, e descarta o proprio evento pra nao processar ele mesmo), entao
    // `currentRoundId` do repositorio ficava sempre null -- e qualquer
    // mensagem do cliente pro host (REQ_DRAW_DECK, DISCARD, MELD, etc.) era
    // silenciosamente descartada em acceptIncomingRound, porque o roundId
    // carimbado nela nunca batia com um "esperado" nulo. O ViewModel chama
    // isto logo depois de aplicar a distribuicao do servidor, com o mesmo
    // roundId que a RPC devolveu, pra sincronizar o estado do repositorio com
    // o que o servidor de fato usou.
    fun markRoundActive(roundId: String) = Unit

    // Mesma ideia do requestServerDeal, mas pra cada compra do monte durante a
    // rodada (RPC online_draw_deck_card): so o online implementa de verdade.
    // Wi-Fi local e maquina continuam com o padrao (null) e o ViewModel
    // segue com o monte local de sempre.
    fun requestServerDraw(seat: Int, onResult: (String?) -> Unit) {
        onResult(null)
    }

    // Mesma ideia, mas pro pedido explicito de time pegar o morto inteiro
    // (RPC online_take_morto): so o online implementa de verdade. `indirect`
    // reflete se quem pegou o morto foi indiretamente esvaziado (ex.: parceiro
    // baixou o resto da mao dele), o que muda de quem e a vez em seguida.
    fun requestServerMorto(seat: Int, indirect: Boolean = false, onResult: (String?) -> Unit) {
        onResult(null)
    }

    // Depois de um morto solicitado diretamente pelo cliente, o host busca a
    // mao canonica no schema privado para continuar validando as jogadas.
    fun requestServerRemoteHand(seat: Int, onResult: (String?) -> Unit) {
        onResult(null)
    }
}

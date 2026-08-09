package com.brunogiovani.cachetaburaco.data.online

import com.brunogiovani.cachetaburaco.domain.models.MatchConfig
import com.brunogiovani.cachetaburaco.domain.repositories.NetworkMessage
import kotlinx.coroutines.flow.Flow

enum class OnlineRoomStatus {
    WAITING,
    PLAYING,
    FINISHED,
    CANCELLED
}

// Lista fechada de propósito (nem uma passa mensagem de exceção crua) -- é
// exatamente pra não correr o risco de vazar mão, token ou payload privado
// dentro de um campo de telemetria "livre". Ver migration
// 0024_client_failure_telemetry.sql: o banco também rejeita qualquer valor
// fora desta lista.
/**
 * O servidor recusou uma acao PROPRIA por regra (RPC/trigger de validacao
 * estrutural, sempre `errcode = 'P0001'` nas migrations 0013+), nao por
 * queda de rede. `SupabaseOnlineRoomDataSource` traduz `PostgrestRestException`
 * pra isto exatamente pra `OnlineNetworkRepository` nao precisar depender do
 * supabase-kt so pra distinguir os dois casos.
 */
class OnlineRuleRejectedException(message: String) : Exception(message)

enum class OnlineFailureCategory(val wireValue: String) {
    SESSION_ERROR("ONLINE_SESSION_ERROR"),
    HEARTBEAT_FAILED("ONLINE_HEARTBEAT_FAILED"),
    PUBLISH_FAILED("ONLINE_PUBLISH_FAILED"),
    DEAL_REQUEST_FAILED("ONLINE_DEAL_REQUEST_FAILED"),
    DRAW_REQUEST_FAILED("ONLINE_DRAW_REQUEST_FAILED"),
    MORTO_REQUEST_FAILED("ONLINE_MORTO_REQUEST_FAILED")
}

data class OnlineRoomSummary(
    val roomId: String,
    val roomCode: String,
    val hostPlayerId: String,
    val config: MatchConfig,
    val status: OnlineRoomStatus,
    val connectedPlayers: Int
)

data class OnlineRoomSession(
    val room: OnlineRoomSummary,
    val playerId: String,
    val seat: Int,
    val isHost: Boolean
)

data class OnlineStoredEvent(
    val sequence: Long,
    val actorPlayerId: String?,
    val actorSeat: Int?,
    val message: NetworkMessage
)

data class OnlineCompletedMatch(
    val resultKey: String,
    val winnerTeam: Int,
    val teamScores: List<Int>,
    val breakdown: String
)

data class OnlineChatMessage(
    val id: Long,
    val senderId: String?,
    val senderSeat: Int?,
    val body: String,
    val createdAt: String
)

/**
 * Operacoes remotas usadas pelo transporte online.
 *
 * A implementacao Supabase cuida de Auth, RLS, persistencia e Realtime. O
 * OnlineNetworkRepository continua cuidando apenas do contrato esperado pela
 * mesa e da deduplicacao das mensagens recebidas.
 */
interface OnlineRoomDataSource {
    suspend fun createRoom(
        playerName: String,
        roomCode: String,
        config: MatchConfig,
        password: String? = null
    ): OnlineRoomSession

    suspend fun listWaitingRooms(playerName: String): List<OnlineRoomSummary>

    fun observeWaitingRooms(playerName: String): Flow<List<OnlineRoomSummary>>

    suspend fun joinRoom(
        playerName: String,
        roomCode: String,
        password: String? = null
    ): OnlineRoomSession

    suspend fun leaveRoom(session: OnlineRoomSession)

    suspend fun closeRoom(session: OnlineRoomSession)

    suspend fun signOut()

    suspend fun publishEvent(
        session: OnlineRoomSession,
        message: NetworkMessage,
        recipientSeat: Int? = null
    ): Boolean

    suspend fun recordCompletedMatch(
        session: OnlineRoomSession,
        result: OnlineCompletedMatch
    ): Boolean

    /**
     * Renova a presenca do jogador e permite que o servidor expire conexoes abandonadas.
     * O retorno falso indica que a sessao deixou de ser reconhecida pela sala.
     */
    suspend fun touchPresence(session: OnlineRoomSession): Boolean = true

    fun observeEvents(
        session: OnlineRoomSession,
        afterSequence: Long = 0L
    ): Flow<OnlineStoredEvent>

    fun observeConnectedSeats(session: OnlineRoomSession): Flow<Set<Int>>

    /**
     * Pede pro servidor embaralhar e distribuir a rodada inteira (RPC
     * `start_online_round`), em vez do host decidir isso sozinho no aparelho.
     * So o host pode chamar -- o banco rejeita se quem chamou nao for o
     * assento 0 da sala. Devolvo o jsonb bruto como texto porque quem
     * interpreta esse payload e o MatchViewModel, do mesmo jeito que qualquer
     * outro NetworkMessage.payload.
     */
    suspend fun startRound(session: OnlineRoomSession): String

    /**
     * Pede pro servidor decidir a proxima carta do monte (RPC
     * `online_draw_deck_card`), incluindo reciclar o lixo (Cacheta) ou virar
     * um morto em monte novo (Buraco/Tranca) quando o monte esgota. So o host
     * chama -- o assento que esta comprando (proprio ou de um cliente
     * remoto) vai no parametro `seat`, mas quem decide qual carta sai e o
     * banco, nunca o processo do host.
     */
    suspend fun drawDeckCard(session: OnlineRoomSession, seat: Int): String

    /**
     * Pede pro servidor entregar o morto inteiro como mao pro assento que
     * zerou a mao (RPC `online_take_morto`), incluindo extrair e repor os 3
     * vermelho automaticos da Tranca. So o host chama -- o banco decide o
     * conteudo do morto, nunca o processo do host.
     */
    suspend fun takeMorto(session: OnlineRoomSession, seat: Int, indirect: Boolean = false): String

    /**
     * Registra uma falha do transporte online sem guardar mao, token ou
     * qualquer dado privado (migration 0024). Deixei com corpo padrao vazio
     * pra nao quebrar dublês de teste que ainda nao implementam isso.
     */
    suspend fun reportFailure(category: OnlineFailureCategory, roomId: String? = null) = Unit

    /**
     * Envia uma mensagem no chat da sala. Insert direto via Postgrest -- RLS
     * (`room_chat_messages_insert_member`) ja garante que so quem esta na
     * sala consegue escrever, sem precisar de RPC dedicada (mesmo padrao
     * simples ja usado em `avatar_photo_reports`).
     */
    suspend fun sendRoomChatMessage(session: OnlineRoomSession, body: String): Boolean

    /**
     * Observa o chat da sala: uma carga inicial (cobre quem entra no meio da
     * partida ou reconecta) seguida das mensagens novas via Realtime. A
     * tabela e apagada quando a partida encerra (migration 0032), entao nao
     * ha necessidade de paginacao aqui -- o historico de uma sala e sempre
     * pequeno.
     */
    fun observeRoomChat(session: OnlineRoomSession): Flow<OnlineChatMessage>
}

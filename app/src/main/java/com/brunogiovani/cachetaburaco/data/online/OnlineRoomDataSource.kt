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
        config: MatchConfig
    ): OnlineRoomSession

    suspend fun listWaitingRooms(playerName: String): List<OnlineRoomSummary>

    fun observeWaitingRooms(playerName: String): Flow<List<OnlineRoomSummary>>

    suspend fun joinRoom(
        playerName: String,
        roomCode: String
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
}

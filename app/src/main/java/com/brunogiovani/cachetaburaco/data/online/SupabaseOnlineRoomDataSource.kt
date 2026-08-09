package com.brunogiovani.cachetaburaco.data.online

import com.brunogiovani.cachetaburaco.domain.models.MatchConfig
import com.brunogiovani.cachetaburaco.domain.repositories.NetworkMessage
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.exception.PostgrestRestException
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import java.util.Locale
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Implementacao remota das salas do Carteado BR.
 *
 * O primeiro acesso cria uma sessao anonima do Supabase e garante o perfil do
 * jogador. Criacao e entrada na sala passam por funcoes SQL atomicas para que
 * dois aparelhos nunca ocupem o mesmo assento.
 */
class SupabaseOnlineRoomDataSource(
    private val client: SupabaseClient = SupabaseClientProvider.client
) : OnlineRoomDataSource {
    private val identity = SupabaseIdentity(client)

    override suspend fun createRoom(
        playerName: String,
        roomCode: String,
        config: MatchConfig,
        password: String?
    ): OnlineRoomSession {
        val playerId = ensureIdentity(playerName)
        val row = client.postgrest.rpc(
            function = "create_match_room",
            parameters = buildJsonObject {
                put("p_room_code", roomCode)
                put("p_game_type", config.gameType.name)
                put("p_config", config.toOnlineRoomConfigJson())
                put("p_max_players", config.maxPlayers)
                password?.trim()?.takeIf { it.isNotEmpty() }?.let { put("p_password", it) }
            }
        ).decodeSingle<RoomSessionRow>()
        return row.toSession(localPlayerId = playerId)
    }

    override suspend fun listWaitingRooms(playerName: String): List<OnlineRoomSummary> {
        ensureIdentity(playerName)
        return fetchWaitingRooms()
    }

    override fun observeWaitingRooms(playerName: String): Flow<List<OnlineRoomSummary>> = channelFlow {
        val realtimeChannel = client.channel("waiting-match-rooms")
        val refreshes = Channel<Unit>(capacity = Channel.CONFLATED)
        val changes = realtimeChannel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "match_rooms"
        }
        val changeCollector = launch {
            changes.collect { refreshes.trySend(Unit) }
        }

        try {
            ensureIdentity(playerName)
            realtimeChannel.subscribe(blockUntilSubscribed = true)
            refreshes.trySend(Unit)
            for (ignored in refreshes) {
                send(fetchWaitingRooms())
            }
        } finally {
            changeCollector.cancel()
            refreshes.close()
            realtimeChannel.unsubscribe()
        }
    }.distinctUntilChanged()

    override suspend fun joinRoom(
        playerName: String,
        roomCode: String,
        password: String?
    ): OnlineRoomSession {
        val playerId = ensureIdentity(playerName)
        val row = client.postgrest.rpc(
            function = "join_match_room",
            parameters = buildJsonObject {
                put("p_room_code", roomCode)
                password?.trim()?.takeIf { it.isNotEmpty() }?.let { put("p_password", it) }
            }
        ).decodeSingle<RoomSessionRow>()
        return row.toSession(localPlayerId = playerId)
    }

    override suspend fun leaveRoom(session: OnlineRoomSession) {
        client.postgrest.rpc(
            function = "leave_match_room",
            parameters = buildJsonObject { put("p_room_id", session.room.roomId) }
        )
    }

    override suspend fun closeRoom(session: OnlineRoomSession) {
        require(session.isHost) { "Somente o host pode encerrar a sala online." }
        client.postgrest.rpc(
            function = "close_match_room",
            parameters = buildJsonObject { put("p_room_id", session.room.roomId) }
        )
    }

    override suspend fun signOut() {
        identity.signOut()
    }

    override suspend fun publishEvent(
        session: OnlineRoomSession,
        message: NetworkMessage,
        recipientSeat: Int?
    ): Boolean {
        val payload = Json.parseToJsonElement(OnlineEventCodec.encode(message)).jsonObject
        return try {
            client.postgrest.rpc(
                function = "append_match_event",
                parameters = buildJsonObject {
                    put("p_room_id", session.room.roomId)
                    put("p_message_id", message.messageId)
                    put("p_event_type", message.type)
                    put("p_payload", payload)
                    recipientSeat?.let { put("p_recipient_seat", it) }
                }
            ).decodeAs<Boolean>()
        } catch (error: PostgrestRestException) {
            // P0001 e sempre um `raise exception` nosso (regra recusada pela
            // validacao estrutural nas RPCs/triggers) -- traduzo pra um tipo
            // sem depender do supabase-kt, pra distinguir isso de queda de
            // rede de verdade la em cima, em OnlineNetworkRepository.
            if (error.code == "P0001") {
                throw OnlineRuleRejectedException(error.message ?: "Jogada recusada pelo servidor.")
            }
            throw error
        }
    }

    override suspend fun recordCompletedMatch(
        session: OnlineRoomSession,
        result: OnlineCompletedMatch
    ): Boolean {
        require(session.isHost) { "Somente o host pode registrar o resultado online." }
        return client.postgrest.rpc(
            function = "complete_match",
            parameters = buildJsonObject {
                put("p_room_id", session.room.roomId)
                put("p_result_key", result.resultKey)
                put("p_winner_team", result.winnerTeam)
                put("p_scores", buildJsonObject {
                    put("teamScores", buildJsonArray {
                        result.teamScores.forEach { score -> add(JsonPrimitive(score)) }
                    })
                })
                put("p_breakdown", buildJsonObject { put("text", result.breakdown) })
            }
        ).decodeAs<Boolean>()
    }

    override suspend fun touchPresence(session: OnlineRoomSession): Boolean {
        return client.postgrest.rpc(
            function = "touch_match_room_presence",
            parameters = buildJsonObject { put("p_room_id", session.room.roomId) }
        ).decodeAs<Boolean>()
    }

    override suspend fun startRound(session: OnlineRoomSession): String {
        return client.postgrest.rpc(
            function = "start_online_round",
            parameters = buildJsonObject { put("p_room_id", session.room.roomId) }
        ).decodeAs<JsonObject>().toString()
    }

    override suspend fun drawDeckCard(session: OnlineRoomSession, seat: Int): String {
        return client.postgrest.rpc(
            function = "online_draw_deck_card",
            parameters = buildJsonObject {
                put("p_room_id", session.room.roomId)
                put("p_seat", seat)
            }
        ).decodeAs<JsonObject>().toString()
    }

    override suspend fun takeMorto(session: OnlineRoomSession, seat: Int, indirect: Boolean): String {
        return client.postgrest.rpc(
            function = "online_take_morto",
            parameters = buildJsonObject {
                put("p_room_id", session.room.roomId)
                put("p_seat", seat)
                put("p_indirect", indirect)
            }
        ).decodeAs<JsonObject>().toString()
    }

    override suspend fun reportFailure(category: OnlineFailureCategory, roomId: String?) {
        client.postgrest.rpc(
            function = "report_client_failure",
            parameters = buildJsonObject {
                put("p_category", category.wireValue)
                roomId?.let { put("p_room_id", it) }
            }
        )
    }

    override fun observeEvents(
        session: OnlineRoomSession,
        afterSequence: Long
    ): Flow<OnlineStoredEvent> = channelFlow {
        val roomId = session.room.roomId
        val realtimeChannel = client.channel("match-events-$roomId")
        val liveRows = Channel<MatchEventRow>(capacity = Channel.UNLIMITED)
        val changes = realtimeChannel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
            table = "match_events"
            filter("room_id", FilterOperator.EQ, roomId)
        }
        val liveCollector = launch {
            changes.collect { change ->
                liveRows.send(change.decodeRecord())
            }
        }
        val emittedIds = linkedSetOf<String>()

        try {
            realtimeChannel.subscribe(blockUntilSubscribed = true)

            // A consulta inicial cobre reconexao. O canal ja esta inscrito para
            // nao perder um evento criado durante essa leitura.
            loadEvents(roomId = roomId, afterSequence = afterSequence).forEach { row ->
                row.toStoredEvent()?.let { event ->
                    if (emittedIds.addBounded(event.message.messageId)) send(event)
                }
            }

            for (row in liveRows) {
                row.toStoredEvent()?.let { event ->
                    if (event.sequence > afterSequence && emittedIds.addBounded(event.message.messageId)) {
                        send(event)
                    }
                }
            }
        } finally {
            liveCollector.cancel()
            liveRows.close()
            realtimeChannel.unsubscribe()
        }
    }

    override suspend fun sendRoomChatMessage(session: OnlineRoomSession, body: String): Boolean {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return false
        client.from("room_chat_messages").insert(
            RoomChatInsert(
                roomId = session.room.roomId,
                senderId = session.playerId,
                senderSeat = session.seat,
                body = trimmed
            )
        )
        return true
    }

    override fun observeRoomChat(session: OnlineRoomSession): Flow<OnlineChatMessage> = channelFlow {
        val roomId = session.room.roomId
        val realtimeChannel = client.channel("room-chat-$roomId")
        val liveRows = Channel<RoomChatMessageRow>(capacity = Channel.UNLIMITED)
        val changes = realtimeChannel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
            table = "room_chat_messages"
            filter("room_id", FilterOperator.EQ, roomId)
        }
        val liveCollector = launch {
            changes.collect { change ->
                liveRows.send(change.decodeRecord())
            }
        }
        val emittedIds = linkedSetOf<Long>()

        try {
            realtimeChannel.subscribe(blockUntilSubscribed = true)

            // A consulta inicial cobre quem abre o chat no meio da partida ou
            // reconecta. O canal ja esta inscrito antes disso pra nao perder
            // uma mensagem enviada durante essa leitura.
            loadRoomChatMessages(roomId).forEach { row ->
                if (emittedIds.add(row.id)) send(row.toChatMessage())
            }

            for (row in liveRows) {
                if (emittedIds.add(row.id)) send(row.toChatMessage())
            }
        } finally {
            liveCollector.cancel()
            liveRows.close()
            realtimeChannel.unsubscribe()
        }
    }

    private suspend fun loadRoomChatMessages(roomId: String): List<RoomChatMessageRow> {
        return client.from("room_chat_messages").select {
            filter {
                filter("room_id", FilterOperator.EQ, roomId)
            }
            order(column = "id", order = Order.ASCENDING)
        }.decodeList()
    }

    override fun observeConnectedSeats(session: OnlineRoomSession): Flow<Set<Int>> = channelFlow {
        val roomId = session.room.roomId
        val realtimeChannel = client.channel("room-presence-$roomId")
        val refreshes = Channel<Unit>(capacity = Channel.CONFLATED)
        val changes = realtimeChannel.postgresChangeFlow<PostgresAction>(schema = "public") {
            table = "room_players"
            filter("room_id", FilterOperator.EQ, roomId)
        }
        val changeCollector = launch {
            changes.collect { refreshes.trySend(Unit) }
        }

        try {
            realtimeChannel.subscribe(blockUntilSubscribed = true)
            refreshes.trySend(Unit)
            for (ignored in refreshes) {
                send(loadConnectedSeats(roomId))
            }
        } finally {
            changeCollector.cancel()
            refreshes.close()
            realtimeChannel.unsubscribe()
        }
    }.distinctUntilChanged()

    private suspend fun ensureIdentity(playerName: String): String {
        return identity.ensure(playerName)
    }

    private suspend fun fetchWaitingRooms(): List<OnlineRoomSummary> {
        return client.postgrest
            .rpc("list_waiting_match_rooms")
            .decodeList<RoomSummaryRow>()
            .map(RoomSummaryRow::toSummary)
    }

    private suspend fun loadEvents(roomId: String, afterSequence: Long): List<MatchEventRow> {
        return client.from("match_events").select {
            filter {
                filter("room_id", FilterOperator.EQ, roomId)
                filter("id", FilterOperator.GT, afterSequence)
            }
            order(column = "id", order = Order.ASCENDING)
        }.decodeList()
    }

    private suspend fun loadConnectedSeats(roomId: String): Set<Int> {
        return client.from("room_players").select {
            filter {
                filter("room_id", FilterOperator.EQ, roomId)
                filter("connected", FilterOperator.EQ, true)
            }
        }.decodeList<RoomPlayerPresenceRow>().mapTo(linkedSetOf()) { player -> player.seat }
    }

}

internal fun MatchConfig.toOnlineRoomConfigJson(): JsonObject = buildJsonObject {
    put("serialized", serialize())
    // Deixo as regras usadas pelo servidor em campos nomeados. O CSV continua
    // junto para manter a leitura das salas antigas sem depender da ordem dele.
    put("allowWildcards", allowWildcards)
    put("allowCharutos", allowCharutos)
    put("allowDrawFromDiscard", allowDrawFromDiscard)
    put("requireCleanCanastraToWin", requireCleanCanastraToWin)
    put("autoMeldTrancaRedThrees", autoMeldTrancaRedThrees)
    put("cardsPerPlayer", cardsPerPlayer)
}

private fun JsonObject.toMatchConfig(): MatchConfig {
    val serialized = get("serialized")?.jsonPrimitive?.contentOrNull
    return serialized?.let(MatchConfig::deserialize) ?: MatchConfig()
}

private fun String.toRoomStatus(): OnlineRoomStatus {
    return runCatching { OnlineRoomStatus.valueOf(uppercase(Locale.ROOT)) }
        .getOrDefault(OnlineRoomStatus.WAITING)
}

private fun RoomSummaryRow.toSummary(): OnlineRoomSummary {
    return OnlineRoomSummary(
        roomId = roomId,
        roomCode = roomCode,
        hostPlayerId = hostId,
        config = config.toMatchConfig(),
        status = status.toRoomStatus(),
        connectedPlayers = connectedPlayers
    )
}

private fun RoomSessionRow.toSession(localPlayerId: String): OnlineRoomSession {
    val summary = OnlineRoomSummary(
        roomId = roomId,
        roomCode = roomCode,
        hostPlayerId = hostId,
        config = config.toMatchConfig(),
        status = status.toRoomStatus(),
        connectedPlayers = connectedPlayers
    )
    return OnlineRoomSession(
        room = summary,
        playerId = localPlayerId,
        seat = seat,
        isHost = hostId == localPlayerId
    )
}

private fun MatchEventRow.toStoredEvent(): OnlineStoredEvent? {
    val message = OnlineEventCodec.decode(payload.toString()) ?: return null
    return OnlineStoredEvent(
        sequence = id,
        actorPlayerId = actorId,
        actorSeat = seat,
        message = message
    )
}

private fun LinkedHashSet<String>.addBounded(value: String): Boolean {
    if (!add(value)) return false
    while (size > 2_048) {
        val iterator = iterator()
        iterator.next()
        iterator.remove()
    }
    return true
}

@Serializable
private data class RoomSummaryRow(
    @SerialName("room_id") val roomId: String,
    @SerialName("room_code") val roomCode: String,
    @SerialName("host_id") val hostId: String,
    @SerialName("config") val config: JsonObject,
    @SerialName("status") val status: String,
    @SerialName("connected_players") val connectedPlayers: Int
)

@Serializable
private data class RoomSessionRow(
    @SerialName("room_id") val roomId: String,
    @SerialName("room_code") val roomCode: String,
    @SerialName("host_id") val hostId: String,
    @SerialName("config") val config: JsonObject,
    @SerialName("status") val status: String,
    @SerialName("connected_players") val connectedPlayers: Int,
    val seat: Int
)

@Serializable
private data class MatchEventRow(
    val id: Long,
    @SerialName("room_id") val roomId: String,
    @SerialName("actor_id") val actorId: String? = null,
    val seat: Int? = null,
    @SerialName("message_id") val messageId: String,
    @SerialName("event_type") val eventType: String,
    val payload: JsonObject
)

@Serializable
private data class RoomPlayerPresenceRow(
    @SerialName("profile_id") val profileId: String,
    val seat: Int,
    val connected: Boolean
)

@Serializable
private data class RoomChatInsert(
    @SerialName("room_id") val roomId: String,
    @SerialName("sender_id") val senderId: String,
    @SerialName("sender_seat") val senderSeat: Int,
    val body: String
)

@Serializable
private data class RoomChatMessageRow(
    val id: Long,
    @SerialName("sender_id") val senderId: String? = null,
    @SerialName("sender_seat") val senderSeat: Int? = null,
    val body: String,
    @SerialName("created_at") val createdAt: String
)

private fun RoomChatMessageRow.toChatMessage(): OnlineChatMessage {
    return OnlineChatMessage(
        id = id,
        senderId = senderId,
        senderSeat = senderSeat,
        body = body,
        createdAt = createdAt
    )
}

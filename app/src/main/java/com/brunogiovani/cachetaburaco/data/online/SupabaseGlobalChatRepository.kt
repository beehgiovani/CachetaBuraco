package com.brunogiovani.cachetaburaco.data.online

import com.brunogiovani.cachetaburaco.domain.repositories.GlobalChatEntry
import com.brunogiovani.cachetaburaco.domain.repositories.GlobalChatRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.decodeRecord
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Chat geral (fora de sala) via tabela + Realtime Postgres Changes (migration
 * 0036) -- mesmo padrao ja usado no chat de sala (`observeRoomChat` em
 * SupabaseOnlineRoomDataSource.kt), so que sem room_id/sender_seat (mensagem
 * publica, nao amarrada a nenhuma sala) e com retencao curta via trigger no
 * banco em vez de apagar no fim de uma partida. Quem abre o chat ve as
 * ultimas mensagens (contexto do assunto) antes de comecar a receber as
 * novas ao vivo. sender_name nunca vem do que o cliente manda no insert --
 * um trigger no banco sobrescreve sempre com o nickname real do perfil
 * autenticado, pra ninguem conseguir se apresentar com o nome de outro
 * jogador no chat publico.
 */
class SupabaseGlobalChatRepository(
    private val client: SupabaseClient = SupabaseClientProvider.client
) : GlobalChatRepository {
    private val identity = SupabaseIdentity(client)

    override suspend fun sendMessage(playerName: String, body: String): Boolean {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return false
        return try {
            val playerId = identity.ensure(playerName)
            client.from(GLOBAL_CHAT_TABLE).insert(GlobalChatInsert(senderId = playerId, body = trimmed))
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            false
        }
    }

    override fun observeMessages(playerName: String): Flow<GlobalChatEntry> = channelFlow {
        val playerId = identity.ensure(playerName)
        val realtimeChannel = client.channel("global-chat-realtime")
        val liveRows = Channel<GlobalChatMessageRow>(capacity = Channel.UNLIMITED)
        val changes = realtimeChannel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
            table = GLOBAL_CHAT_TABLE
        }
        val collector = launch {
            changes.collect { change -> liveRows.send(change.decodeRecord()) }
        }
        val emittedIds = linkedSetOf<Long>()

        try {
            realtimeChannel.subscribe(blockUntilSubscribed = true)

            // Backfill das ultimas mensagens pra quem abre o chat ter
            // contexto do assunto -- o canal ja esta inscrito antes disso
            // pra nao perder nenhuma mensagem enviada durante essa leitura.
            loadRecentMessages().forEach { row ->
                if (emittedIds.add(row.id)) send(row.toEntry(playerId))
            }

            for (row in liveRows) {
                if (emittedIds.add(row.id)) send(row.toEntry(playerId))
            }
        } finally {
            collector.cancel()
            liveRows.close()
            // Mesmo risco documentado em SupabaseOnlineRoomDataSource.kt:
            // unsubscribe() pode lancar se o flow for cancelado antes do
            // subscribe(blockUntilSubscribed = true) confirmar (socket ainda
            // nao inicializado).
            runCatching { realtimeChannel.unsubscribe() }
        }
    }

    private suspend fun loadRecentMessages(): List<GlobalChatMessageRow> {
        return client.from(GLOBAL_CHAT_TABLE).select {
            order(column = "id", order = Order.DESCENDING)
            limit(GLOBAL_CHAT_HISTORY_LIMIT)
        }.decodeList<GlobalChatMessageRow>().sortedBy { it.id }
    }

    private companion object {
        const val GLOBAL_CHAT_TABLE = "global_chat_messages"
        const val GLOBAL_CHAT_HISTORY_LIMIT = 10L
    }
}

@Serializable
private data class GlobalChatInsert(
    @SerialName("sender_id") val senderId: String,
    val body: String
)

@Serializable
private data class GlobalChatMessageRow(
    val id: Long,
    @SerialName("sender_id") val senderId: String? = null,
    @SerialName("sender_name") val senderName: String,
    val body: String
)

private fun GlobalChatMessageRow.toEntry(localPlayerId: String) = GlobalChatEntry(
    id = id,
    senderName = senderName,
    body = body,
    isSelf = senderId == localPlayerId
)

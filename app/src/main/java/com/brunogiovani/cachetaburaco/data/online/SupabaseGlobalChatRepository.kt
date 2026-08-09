package com.brunogiovani.cachetaburaco.data.online

import com.brunogiovani.cachetaburaco.domain.repositories.GlobalChatEntry
import com.brunogiovani.cachetaburaco.domain.repositories.GlobalChatRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.realtime.RealtimeChannel
import io.github.jan.supabase.realtime.broadcast
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.channel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Chat geral (fora de sala) via Realtime Broadcast puro -- sem tabela, sem
 * RLS, sem historico. `client.channel(topic)` e cacheado pelo proprio SDK por
 * topico (RealtimeImpl.channel devolve a mesma instancia se ja existir),
 * entao envio e observacao usam sempre o mesmo builder pra nao correr o risco
 * de uma chamada "ganhar" a config e a outra ficar com o padrao (o builder so
 * e aplicado na primeira vez que o topico e criado).
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
            globalChatChannel().broadcast(
                event = GLOBAL_CHAT_EVENT,
                message = GlobalChatWireMessage(
                    senderId = playerId,
                    senderName = normalizeOnlineNickname(playerName),
                    body = trimmed
                )
            )
            true
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            false
        }
    }

    override fun observeMessages(playerName: String): Flow<GlobalChatEntry> = channelFlow {
        val playerId = identity.ensure(playerName)
        val realtimeChannel = globalChatChannel()
        val liveMessages = Channel<GlobalChatWireMessage>(capacity = Channel.UNLIMITED)
        val collector = launch {
            realtimeChannel.broadcastFlow<GlobalChatWireMessage>(GLOBAL_CHAT_EVENT).collect { message ->
                liveMessages.send(message)
            }
        }
        try {
            realtimeChannel.subscribe(blockUntilSubscribed = true)
            for (message in liveMessages) {
                send(
                    GlobalChatEntry(
                        senderName = message.senderName,
                        body = message.body,
                        isSelf = message.senderId == playerId
                    )
                )
            }
        } finally {
            collector.cancel()
            liveMessages.close()
            // Se o flow for cancelado antes do subscribe(blockUntilSubscribed
            // = true) terminar (ex.: sai da tela rapido), o WebSocket
            // subjacente ainda nao foi inicializado -- unsubscribe() acessa
            // ele direto e lanca IllegalStateException("Websocket not yet
            // initialized"), derrubando o app com uma FATAL EXCEPTION so por
            // causa de uma limpeza que nao tinha nada real pra desfazer.
            runCatching { realtimeChannel.unsubscribe() }
        }
    }

    private fun globalChatChannel(): RealtimeChannel = client.channel(GLOBAL_CHAT_TOPIC) {
        broadcast { receiveOwnBroadcasts = true }
    }

    private companion object {
        const val GLOBAL_CHAT_TOPIC = "global-chat"
        const val GLOBAL_CHAT_EVENT = "message"
    }
}

@Serializable
private data class GlobalChatWireMessage(
    @SerialName("sender_id") val senderId: String,
    @SerialName("sender_name") val senderName: String,
    val body: String
)

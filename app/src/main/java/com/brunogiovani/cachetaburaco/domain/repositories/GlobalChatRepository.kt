package com.brunogiovani.cachetaburaco.domain.repositories

import kotlinx.coroutines.flow.Flow

// Chat geral (fora de sala): tabela + Realtime Postgres Changes (migration
// 0036), mesmo padrao do chat de sala -- mas com retencao curta (trigger
// mantem so as ultimas 200 linhas) em vez de apagar no fim de uma partida,
// ja que o chat geral nao tem esse gatilho. Quem entra ve as ultimas
// mensagens (contexto do assunto) e depois recebe as novas ao vivo.
data class GlobalChatEntry(
    val id: Long,
    val senderName: String,
    val body: String,
    val isSelf: Boolean
)

interface GlobalChatRepository {
    suspend fun sendMessage(playerName: String, body: String): Boolean
    fun observeMessages(playerName: String): Flow<GlobalChatEntry>
}

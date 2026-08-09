package com.brunogiovani.cachetaburaco.domain.repositories

import kotlinx.coroutines.flow.Flow

// Chat geral (fora de sala): Realtime Broadcast puro, sem tabela nem
// historico -- decisao deliberada pra nao criar mais uma superficie de
// moderacao/RLS pra algo sem escopo (mensagem global, qualquer um pode ver).
// Quem entra depois so ve mensagens novas, nada retroativo.
data class GlobalChatEntry(
    val senderName: String,
    val body: String,
    val isSelf: Boolean
)

interface GlobalChatRepository {
    suspend fun sendMessage(playerName: String, body: String): Boolean
    fun observeMessages(playerName: String): Flow<GlobalChatEntry>
}

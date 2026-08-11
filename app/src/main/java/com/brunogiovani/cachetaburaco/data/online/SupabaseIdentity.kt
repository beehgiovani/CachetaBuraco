package com.brunogiovani.cachetaburaco.data.online

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import kotlinx.serialization.Serializable

/** Mantem a sessao online e o perfil publico usando uma unica identidade. */
class SupabaseIdentity(
    private val client: SupabaseClient
) {
    suspend fun ensure(playerName: String): String {
        client.auth.awaitInitialization()
        if (client.auth.currentUserOrNull() == null) {
            client.auth.signInAnonymously()
        }
        val playerId = requireNotNull(client.auth.currentUserOrNull()?.id) {
            "O Supabase nao devolveu o usuario depois da autenticacao anonima."
        }
        client.from("profiles").upsert(
            value = OnlineProfileRow(
                id = playerId,
                nickname = normalizeOnlineNickname(playerName)
            )
        ) {
            onConflict = "id"
        }
        return playerId
    }

    suspend fun signOut() {
        client.auth.signOut()
    }
}

internal fun normalizeOnlineNickname(playerName: String): String {
    val normalized = playerName.trim().replace(Regex("\\s+"), " ").take(24)
    return normalized.takeIf { it.length >= 2 } ?: "Jogador"
}

@Serializable
private data class OnlineProfileRow(
    val id: String,
    val nickname: String
)

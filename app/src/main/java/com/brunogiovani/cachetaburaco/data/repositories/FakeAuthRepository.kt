package com.brunogiovani.cachetaburaco.data.repositories

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.brunogiovani.cachetaburaco.domain.models.Player
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Perfil local do jogador.
 *
 * Por enquanto o app trabalha em modo convidado e guarda nome, ID e ranking local
 * no próprio aparelho. Quando entrar login real, a troca fica concentrada aqui.
 */
object FakeAuthRepository {

    private const val PREFS_NAME = "cachetaburaco_prefs"
    private const val KEY_PLAYER_ID = "player_id"
    private const val KEY_PLAYER_NAME = "player_name"
    private const val KEY_LOCAL_RANKING = "local_ranking"

    private var currentPlayer: Player? = null
    private var prefs: SharedPreferences? = null

    data class LocalRankingEntry(
        val playerId: String,
        val playerName: String,
        val wins: Int
    )

    /** Carrega o perfil salvo logo na abertura do app. */
    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadSavedProfile()
    }

    private fun loadSavedProfile() {
        val savedId = prefs?.getString(KEY_PLAYER_ID, null)
        val savedName = prefs?.getString(KEY_PLAYER_NAME, null)
        if (savedId != null && savedName != null) {
            val player = Player(id = savedId, name = savedName)
            currentPlayer = player
            upsertRankingEntry(player)
        }
    }

    /** Cria um perfil local com apelido e ID próprio. */
    suspend fun login(nickname: String): Player {
        delay(400) // Pequeno respiro para a transição da tela não ficar seca.

        val player = Player(
            id = UUID.randomUUID().toString(),
            name = nickname.trim(),
            points = 0
        )
        currentPlayer = player
        persistProfile(player)
        upsertRankingEntry(player)
        return player
    }

    /** Atualiza o apelido do perfil existente sem gerar novo ID. */
    suspend fun updateNickname(newNickname: String): Player {
        val existing = currentPlayer ?: return login(newNickname)
        val updated = existing.copy(name = newNickname.trim())
        currentPlayer = updated
        persistProfile(updated)
        upsertRankingEntry(updated)
        return updated
    }

    private fun persistProfile(player: Player) {
        prefs?.edit {
            putString(KEY_PLAYER_ID, player.id)
            putString(KEY_PLAYER_NAME, player.name)
        }
    }

    fun getCurrentPlayer(): Player? = currentPlayer

    fun getLocalRanking(): List<LocalRankingEntry> {
        return readRanking()
            .sortedWith(
                compareByDescending<LocalRankingEntry> { it.wins }
                    .thenBy { it.playerName.lowercase() }
            )
    }

    fun recordCurrentPlayerVictory(): LocalRankingEntry? {
        val player = currentPlayer ?: return null
        val updatedRanking = readRanking().toMutableList()
        val index = updatedRanking.indexOfFirst { it.playerId == player.id }
        val updatedEntry = if (index >= 0) {
            val current = updatedRanking[index]
            current.copy(playerName = player.name, wins = current.wins + 1)
        } else {
            LocalRankingEntry(player.id, player.name, wins = 1)
        }

        if (index >= 0) updatedRanking[index] = updatedEntry else updatedRanking.add(updatedEntry)
        writeRanking(updatedRanking)
        return updatedEntry
    }

    /** Limpa o perfil salvo — equivalente a "trocar de conta" */
    fun logout() {
        currentPlayer = null
        prefs?.edit {
            remove(KEY_PLAYER_ID)
            remove(KEY_PLAYER_NAME)
        }
    }

    /** True se já existe um perfil salvo localmente */
    fun hasSavedProfile(): Boolean = currentPlayer != null

    private fun upsertRankingEntry(player: Player) {
        val ranking = readRanking().toMutableList()
        val index = ranking.indexOfFirst { it.playerId == player.id }
        val entry = if (index >= 0) {
            ranking[index].copy(playerName = player.name)
        } else {
            LocalRankingEntry(player.id, player.name, wins = 0)
        }
        if (index >= 0) ranking[index] = entry else ranking.add(entry)
        writeRanking(ranking)
    }

    private fun readRanking(): List<LocalRankingEntry> {
        val raw = prefs?.getString(KEY_LOCAL_RANKING, null) ?: return emptyList()
        val json = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return buildList {
            repeat(json.length()) { index ->
                val item = json.optJSONObject(index) ?: return@repeat
                val playerId = item.optString("playerId")
                val playerName = item.optString("playerName")
                if (playerId.isBlank() || playerName.isBlank()) return@repeat
                add(
                    LocalRankingEntry(
                        playerId = playerId,
                        playerName = playerName,
                        wins = item.optInt("wins", 0).coerceAtLeast(0)
                    )
                )
            }
        }
    }

    private fun writeRanking(ranking: List<LocalRankingEntry>) {
        val json = JSONArray().apply {
            ranking.forEach { entry ->
                put(
                    JSONObject()
                        .put("playerId", entry.playerId)
                        .put("playerName", entry.playerName)
                        .put("wins", entry.wins)
                )
            }
        }
        prefs?.edit {
            putString(KEY_LOCAL_RANKING, json.toString())
        }
    }

    fun forceSetForPreview(player: com.brunogiovani.cachetaburaco.domain.models.Player) {
        currentPlayer = player
    }
}

package com.brunogiovani.cachetaburaco.data.online

import com.brunogiovani.cachetaburaco.domain.models.OnlineRankingEntry
import com.brunogiovani.cachetaburaco.domain.models.OnlineRankingPeriod
import com.brunogiovani.cachetaburaco.domain.models.OnlineRankingSnapshot
import com.brunogiovani.cachetaburaco.domain.repositories.OnlineRankingRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.storage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val AVATAR_PHOTO_BUCKET = "avatar-photos"

/** Le o placar global sem participar do transporte de uma sala. */
class SupabaseOnlineRankingRepository(
    private val client: SupabaseClient = SupabaseClientProvider.client
) : OnlineRankingRepository {
    private val identity = SupabaseIdentity(client)

    override suspend fun loadRanking(
        playerName: String,
        period: OnlineRankingPeriod,
        limit: Int,
        periodOffset: Int
    ): OnlineRankingSnapshot {
        val localPlayerId = identity.ensure(playerName)
        val safeOffset = periodOffset.coerceAtMost(0)
        val request = buildRankingRpcRequest(period, limit, offset = safeOffset)
        val rows = client.postgrest.rpc(
            function = request.function,
            parameters = request.parameters
        ).decodeList<OnlineRankingRow>()
        // Periodo vazio ainda volta 1 linha-ancora so com period_start/end
        // (profileId nulo) -- filtro pra nao virar uma entrada fantasma no
        // ranking.
        val entries = rows.filter { it.profileId != null }.map { it.toDomain(::avatarPhotoUrl) }
        return OnlineRankingSnapshot(
            localPlayerId = localPlayerId,
            entries = entries,
            period = period,
            periodOffset = safeOffset,
            periodStart = rows.firstOrNull()?.periodStart,
            periodEnd = rows.firstOrNull()?.periodEnd
        )
    }

    private fun avatarPhotoUrl(path: String): String =
        client.storage.from(AVATAR_PHOTO_BUCKET).publicUrl(path)
}

internal data class RankingRpcRequest(
    val function: String,
    val parameters: kotlinx.serialization.json.JsonObject
)

internal fun buildRankingRpcRequest(period: OnlineRankingPeriod, limit: Int, offset: Int = 0): RankingRpcRequest {
    val safeLimit = limit.coerceIn(1, 100)
    return when (period) {
        OnlineRankingPeriod.OVERALL -> RankingRpcRequest(
            function = "list_global_ranking",
            parameters = buildJsonObject { put("p_limit", safeLimit) }
        )

        OnlineRankingPeriod.WEEKLY,
        OnlineRankingPeriod.MONTHLY -> RankingRpcRequest(
            function = "list_period_ranking",
            parameters = buildJsonObject {
                put("p_period", period.name)
                put("p_limit", safeLimit)
                put("p_offset", offset.coerceAtMost(0))
            }
        )
    }
}

@Serializable
private data class OnlineRankingRow(
    @SerialName("rank_position") val rankPosition: Long? = null,
    // profileId nulo so acontece na linha-ancora de um periodo sem nenhuma
    // partida (migration 0033 sempre devolve 1 linha, mesmo vazia, pra
    // period_start/period_end nao se perderem quando nao ha ranking).
    @SerialName("profile_id") val profileId: String? = null,
    val nickname: String? = null,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("avatar_photo_path") val avatarPhotoPath: String? = null,
    @SerialName("total_wins") val totalWins: Int? = null,
    @SerialName("total_matches") val totalMatches: Int? = null,
    @SerialName("cacheta_wins") val cachetaWins: Int? = null,
    @SerialName("buraco_wins") val buracoWins: Int? = null,
    @SerialName("tranca_wins") val trancaWins: Int? = null,
    @SerialName("best_streak") val bestStreak: Int? = null,
    @SerialName("current_streak") val currentStreak: Int? = null,
    val xp: Int? = null,
    @SerialName("last_match_at") val lastMatchAt: String? = null,
    @SerialName("period_start") val periodStart: String? = null,
    @SerialName("period_end") val periodEnd: String? = null
)

// So chamada depois de filtrar profileId != null, entao os demais campos
// sempre vem preenchidos de verdade (linha real de "ranked"); os "?: 0" so
// satisfazem o tipo, nunca disparam na pratica.
private fun OnlineRankingRow.toDomain(buildPhotoUrl: (String) -> String): OnlineRankingEntry {
    return OnlineRankingEntry(
        position = (rankPosition ?: 0L).coerceIn(1, Int.MAX_VALUE.toLong()).toInt(),
        playerId = requireNotNull(profileId),
        playerName = nickname.orEmpty(),
        avatarUrl = avatarUrl,
        totalWins = totalWins ?: 0,
        totalMatches = totalMatches ?: 0,
        cachetaWins = cachetaWins ?: 0,
        buracoWins = buracoWins ?: 0,
        trancaWins = trancaWins ?: 0,
        bestStreak = bestStreak ?: 0,
        currentStreak = currentStreak ?: 0,
        xp = xp ?: 0,
        lastMatchAt = lastMatchAt,
        avatarPhotoUrl = avatarPhotoPath?.let(buildPhotoUrl)
    )
}

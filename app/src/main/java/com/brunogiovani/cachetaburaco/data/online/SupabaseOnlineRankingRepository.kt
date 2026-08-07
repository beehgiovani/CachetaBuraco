package com.brunogiovani.cachetaburaco.data.online

import com.brunogiovani.cachetaburaco.domain.models.OnlineRankingEntry
import com.brunogiovani.cachetaburaco.domain.models.OnlineRankingPeriod
import com.brunogiovani.cachetaburaco.domain.models.OnlineRankingSnapshot
import com.brunogiovani.cachetaburaco.domain.repositories.OnlineRankingRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Le o placar global sem participar do transporte de uma sala. */
class SupabaseOnlineRankingRepository(
    private val client: SupabaseClient = SupabaseClientProvider.client
) : OnlineRankingRepository {
    private val identity = SupabaseIdentity(client)

    override suspend fun loadRanking(
        playerName: String,
        period: OnlineRankingPeriod,
        limit: Int
    ): OnlineRankingSnapshot {
        val localPlayerId = identity.ensure(playerName)
        val request = buildRankingRpcRequest(period, limit)
        val entries = client.postgrest.rpc(
            function = request.function,
            parameters = request.parameters
        ).decodeList<OnlineRankingRow>().map(OnlineRankingRow::toDomain)
        return OnlineRankingSnapshot(
            localPlayerId = localPlayerId,
            entries = entries,
            period = period
        )
    }
}

internal data class RankingRpcRequest(
    val function: String,
    val parameters: kotlinx.serialization.json.JsonObject
)

internal fun buildRankingRpcRequest(period: OnlineRankingPeriod, limit: Int): RankingRpcRequest {
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
            }
        )
    }
}

@Serializable
private data class OnlineRankingRow(
    @SerialName("rank_position") val rankPosition: Long,
    @SerialName("profile_id") val profileId: String,
    val nickname: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("total_wins") val totalWins: Int,
    @SerialName("total_matches") val totalMatches: Int,
    @SerialName("cacheta_wins") val cachetaWins: Int,
    @SerialName("buraco_wins") val buracoWins: Int,
    @SerialName("tranca_wins") val trancaWins: Int,
    @SerialName("best_streak") val bestStreak: Int,
    @SerialName("current_streak") val currentStreak: Int,
    val xp: Int,
    @SerialName("last_match_at") val lastMatchAt: String? = null
)

private fun OnlineRankingRow.toDomain(): OnlineRankingEntry {
    return OnlineRankingEntry(
        position = rankPosition.coerceIn(1, Int.MAX_VALUE.toLong()).toInt(),
        playerId = profileId,
        playerName = nickname,
        avatarUrl = avatarUrl,
        totalWins = totalWins,
        totalMatches = totalMatches,
        cachetaWins = cachetaWins,
        buracoWins = buracoWins,
        trancaWins = trancaWins,
        bestStreak = bestStreak,
        currentStreak = currentStreak,
        xp = xp,
        lastMatchAt = lastMatchAt
    )
}

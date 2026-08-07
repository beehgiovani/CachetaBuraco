package com.brunogiovani.cachetaburaco.domain.repositories

import com.brunogiovani.cachetaburaco.domain.models.OnlineRankingSnapshot
import com.brunogiovani.cachetaburaco.domain.models.OnlineRankingPeriod

interface OnlineRankingRepository {
    suspend fun loadRanking(
        playerName: String,
        period: OnlineRankingPeriod = OnlineRankingPeriod.OVERALL,
        limit: Int = 50
    ): OnlineRankingSnapshot
}

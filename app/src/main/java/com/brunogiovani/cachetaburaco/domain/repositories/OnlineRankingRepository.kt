package com.brunogiovani.cachetaburaco.domain.repositories

import com.brunogiovani.cachetaburaco.domain.models.OnlineRankingSnapshot
import com.brunogiovani.cachetaburaco.domain.models.OnlineRankingPeriod

interface OnlineRankingRepository {
    suspend fun loadRanking(
        playerName: String,
        period: OnlineRankingPeriod = OnlineRankingPeriod.OVERALL,
        limit: Int = 50,
        // So usado por WEEKLY/MONTHLY: 0 = periodo atual, negativo = temporada
        // anterior. OVERALL ignora -- ranking geral nao tem "periodo".
        periodOffset: Int = 0
    ): OnlineRankingSnapshot
}

package com.brunogiovani.cachetaburaco.presentation.ranking

import com.brunogiovani.cachetaburaco.domain.models.OnlineRankingPeriod
import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineRankingScreenLogicTest {
    @Test
    fun `ranking period labels stay short for compact screens`() {
        assertEquals("Geral", rankingPeriodLabel(OnlineRankingPeriod.OVERALL))
        assertEquals("Semana", rankingPeriodLabel(OnlineRankingPeriod.WEEKLY))
        assertEquals("Mês", rankingPeriodLabel(OnlineRankingPeriod.MONTHLY))
    }

    @Test
    fun `empty ranking message explains selected period`() {
        assertEquals(
            "O ranking abre assim que a primeira partida online terminar.",
            rankingEmptyMessage(OnlineRankingPeriod.OVERALL)
        )
        assertEquals(
            "Nenhuma partida online foi concluída nesta semana.",
            rankingEmptyMessage(OnlineRankingPeriod.WEEKLY)
        )
        assertEquals(
            "Nenhuma partida online foi concluída neste mês.",
            rankingEmptyMessage(OnlineRankingPeriod.MONTHLY)
        )
    }
}

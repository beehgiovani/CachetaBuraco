package com.brunogiovani.cachetaburaco.presentation.components

import org.junit.Assert.assertEquals
import org.junit.Test

class SafeAdBannerSlotTest {

    // Os IDs de verdade moraram pra strings.xml (admob_banner_menu/lobby/ranking);
    // aqui so garanto que cada AdPlacement roteia pro bloco certo.
    private val menu = "menu-id"
    private val lobby = "lobby-id"
    private val ranking = "ranking-id"

    @Test
    fun `production ad units use separated admob blocks by placement`() {
        assertEquals(menu, productionAdUnitFor(AdPlacement.MAIN_MENU, menu, lobby, ranking))
        assertEquals(lobby, productionAdUnitFor(AdPlacement.LOBBY, menu, lobby, ranking))
        assertEquals(ranking, productionAdUnitFor(AdPlacement.RANKING, menu, lobby, ranking))
        assertEquals(lobby, productionAdUnitFor(AdPlacement.RULES, menu, lobby, ranking))
        assertEquals(ranking, productionAdUnitFor(AdPlacement.ROUND_SUMMARY, menu, lobby, ranking))
    }
}

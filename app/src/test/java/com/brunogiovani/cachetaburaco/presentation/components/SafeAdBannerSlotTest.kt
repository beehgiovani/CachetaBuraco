package com.brunogiovani.cachetaburaco.presentation.components

import org.junit.Assert.assertEquals
import org.junit.Test

class SafeAdBannerSlotTest {

    @Test
    fun `production ad units use separated admob blocks by placement`() {
        assertEquals(
            "ca-app-pub-9473501958357317/7461912378",
            productionAdUnitFor(AdPlacement.MAIN_MENU)
        )
        assertEquals(
            "ca-app-pub-9473501958357317/8583422353",
            productionAdUnitFor(AdPlacement.LOBBY)
        )
        assertEquals(
            "ca-app-pub-9473501958357317/2018014003",
            productionAdUnitFor(AdPlacement.RANKING)
        )
    }
}

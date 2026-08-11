package com.brunogiovani.cachetaburaco.domain.models

import org.junit.Assert.assertEquals
import org.junit.Test

class RankTest {
    @Test
    fun `display label uses card symbols instead of enum names`() {
        val expected = listOf("A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K")

        assertEquals(expected, Rank.entries.map(Rank::displayLabel))
    }
}

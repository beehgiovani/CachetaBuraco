package com.brunogiovani.cachetaburaco.domain.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class OnlineRankingTest {
    @Test
    fun `win rate is zero before first completed match`() {
        assertEquals(0, entry(wins = 0, matches = 0).winRatePercent)
    }

    @Test
    fun `win rate uses completed matches without rounding above result`() {
        assertEquals(66, entry(wins = 2, matches = 3).winRatePercent)
    }

    @Test
    fun `snapshot locates authenticated player without relying on nickname`() {
        val first = entry(playerId = "first")
        val current = entry(playerId = "auth-player")
        val snapshot = OnlineRankingSnapshot(
            localPlayerId = "auth-player",
            entries = listOf(first, current)
        )

        assertSame(current, snapshot.localPlayer)
        assertNull(snapshot.copy(localPlayerId = "missing").localPlayer)
    }

    @Test
    fun `snapshot defaults to overall ranking and keeps selected period`() {
        val overall = OnlineRankingSnapshot(localPlayerId = "player", entries = emptyList())

        assertEquals(OnlineRankingPeriod.OVERALL, overall.period)
        assertEquals(OnlineRankingPeriod.WEEKLY, overall.copy(period = OnlineRankingPeriod.WEEKLY).period)
    }

    private fun entry(
        playerId: String = "player",
        wins: Int = 0,
        matches: Int = 0
    ): OnlineRankingEntry {
        return OnlineRankingEntry(
            position = 1,
            playerId = playerId,
            playerName = "Jogador",
            avatarUrl = null,
            totalWins = wins,
            totalMatches = matches,
            cachetaWins = 0,
            buracoWins = 0,
            trancaWins = 0,
            bestStreak = 0,
            currentStreak = 0,
            xp = 0,
            lastMatchAt = null
        )
    }
}

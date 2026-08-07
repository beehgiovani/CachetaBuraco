package com.brunogiovani.cachetaburaco.presentation.match

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatchScreenLogicTest {

    @Test
    fun `local victory follows team ids instead of presentation text`() {
        assertTrue(details(winnerName = "Oponente", winnerTeam = 1, localTeam = 1).isLocalMatchWinner())
        assertFalse(details(winnerName = "Você", winnerTeam = 0, localTeam = 1).isLocalMatchWinner())
    }

    @Test
    fun `round victory is not recorded before match is over`() {
        assertFalse(
            details(
                winnerName = "Você",
                winnerTeam = 0,
                localTeam = 0,
                isMatchOver = false
            ).isLocalMatchWinner()
        )
    }

    private fun details(
        winnerName: String,
        winnerTeam: Int,
        localTeam: Int,
        isMatchOver: Boolean = true
    ): RoundEndDetails {
        return RoundEndDetails(
            winnerName = winnerName,
            myRoundScore = 0,
            opponentRoundScore = 0,
            myNewTotal = 0,
            opponentNewTotal = 0,
            isMatchOver = isMatchOver,
            breakdown = "",
            winnerTeam = winnerTeam,
            localTeam = localTeam
        )
    }
}

package com.brunogiovani.cachetaburaco.presentation.championship

import org.junit.Assert.assertEquals
import org.junit.Test

class ChampionshipListScreenLogicTest {
    @Test
    fun createChampionshipFeedbackExplainsAdminOnlyRejection() {
        val message = createChampionshipFeedbackFor(IllegalStateException("ADMIN_REQUIRED"))

        assertEquals("Só o administrador do jogo pode criar campeonatos.", message)
    }

    @Test
    fun createChampionshipFeedbackFallsBackForUnexpectedErrors() {
        val message = createChampionshipFeedbackFor(IllegalStateException("NETWORK_TIMEOUT"))

        assertEquals("Não foi possível criar o campeonato agora.", message)
    }

    @Test
    fun joinChampionshipFeedbackExplainsLevelMismatch() {
        val message = joinChampionshipFeedbackFor(IllegalStateException("LEVEL_MISMATCH"))

        assertEquals("Esse campeonato é só pra um nível de jogador diferente do seu.", message)
    }

    @Test
    fun joinChampionshipFeedbackExplainsFinishedChampionship() {
        val message = joinChampionshipFeedbackFor(IllegalStateException("CHAMPIONSHIP_FINISHED"))

        assertEquals("Esse campeonato já foi encerrado.", message)
    }

    @Test
    fun joinChampionshipFeedbackFallsBackForInvalidCodeOrUnexpectedErrors() {
        val message = joinChampionshipFeedbackFor(IllegalStateException("CHAMPIONSHIP_NOT_FOUND"))

        assertEquals("Não foi possível entrar no campeonato. Confira o código.", message)
    }
}

package com.brunogiovani.cachetaburaco.domain.usecases

import com.brunogiovani.cachetaburaco.domain.models.BotDifficulty
import com.brunogiovani.cachetaburaco.domain.models.Card
import com.brunogiovani.cachetaburaco.domain.models.GameType
import com.brunogiovani.cachetaburaco.domain.models.MatchConfig
import com.brunogiovani.cachetaburaco.domain.models.Rank
import com.brunogiovani.cachetaburaco.domain.models.Suit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BotDecisionEngineTest {

    @Test
    fun `normal compra lixo quando o topo encaixa em jogo existente`() {
        val config = MatchConfig(gameType = GameType.TRANCA, botDifficulty = BotDifficulty.NORMAL)
        val table = listOf(listOf(card(Rank.FOUR), card(Rank.FIVE), card(Rank.SIX)))
        val top = card(Rank.SEVEN)

        val source = BotDecisionEngine.chooseDrawSource(
            hand = listOf(Card(Suit.CLUBS, Rank.KING)),
            tableMelds = table,
            discardPile = listOf(top),
            config = config,
            cachetaTurnCard = null
        )

        assertEquals(BotDecisionEngine.DrawSource.DISCARD, source)
    }

    @Test
    fun `facil reconhece compra curta que encaixa imediatamente`() {
        val config = MatchConfig(gameType = GameType.TRANCA, botDifficulty = BotDifficulty.EASY)
        val table = listOf(listOf(card(Rank.FOUR), card(Rank.FIVE), card(Rank.SIX)))

        val source = BotDecisionEngine.chooseDrawSource(
            hand = listOf(Card(Suit.CLUBS, Rank.KING)),
            tableMelds = table,
            discardPile = listOf(card(Rank.SEVEN)),
            config = config,
            cachetaTurnCard = null
        )

        assertEquals(BotDecisionEngine.DrawSource.DISCARD, source)
    }

    @Test
    fun `facil evita lixo grande mesmo quando o topo encaixa`() {
        val config = MatchConfig(gameType = GameType.TRANCA, botDifficulty = BotDifficulty.EASY)
        val table = listOf(listOf(card(Rank.FOUR), card(Rank.FIVE), card(Rank.SIX)))

        val source = BotDecisionEngine.chooseDrawSource(
            hand = listOf(Card(Suit.CLUBS, Rank.KING)),
            tableMelds = table,
            discardPile = listOf(
                Card(Suit.CLUBS, Rank.QUEEN),
                Card(Suit.SPADES, Rank.KING),
                card(Rank.SEVEN)
            ),
            config = config,
            cachetaTurnCard = null
        )

        assertEquals(BotDecisionEngine.DrawSource.DECK, source)
    }

    @Test
    fun `compra obrigatoria gera jogada que usa exatamente o topo do lixo`() {
        val top = card(Rank.FOUR)
        val five = card(Rank.FIVE)
        val six = card(Rank.SIX)
        val config = MatchConfig(gameType = GameType.BURACO, botDifficulty = BotDifficulty.HARD)

        val move = BotDecisionEngine.chooseMeldMove(
            hand = listOf(top, five, six, Card(Suit.CLUBS, Rank.KING)),
            tableMelds = emptyList(),
            config = config,
            cachetaTurnCard = null,
            requiredCardId = top.id
        )

        assertNotNull(move)
        assertTrue(move!!.cardsFromHand.any { it.id == top.id })
        assertTrue(GameRulesEngine.validateMeld(move.resultingMeld, config).isValid)
    }

    @Test
    fun `dificil nao entrega carta que completa o jogo adversario`() {
        val usefulToOpponent = card(Rank.SEVEN)
        val harmless = Card(Suit.CLUBS, Rank.KING)
        val opponentTable = listOf(listOf(card(Rank.FOUR), card(Rank.FIVE), card(Rank.SIX)))
        val config = MatchConfig(gameType = GameType.TRANCA, botDifficulty = BotDifficulty.HARD)

        val discard = BotDecisionEngine.chooseDiscard(
            hand = listOf(usefulToOpponent, harmless),
            tableMelds = emptyList(),
            opponentTableMelds = opponentTable,
            config = config,
            cachetaTurnCard = null
        )

        assertEquals(harmless.id, discard?.id)
    }

    @Test
    fun `dificil usa tres preto como descarte defensivo na tranca`() {
        val blackThree = Card(Suit.SPADES, Rank.THREE)
        val other = Card(Suit.DIAMONDS, Rank.KING)
        val config = MatchConfig(gameType = GameType.TRANCA, botDifficulty = BotDifficulty.HARD)

        val discard = BotDecisionEngine.chooseDiscard(
            hand = listOf(blackThree, other),
            tableMelds = emptyList(),
            opponentTableMelds = emptyList(),
            config = config,
            cachetaTurnCard = null
        )

        assertEquals(blackThree.id, discard?.id)
    }

    @Test
    fun `dificil preserva o dois quando existe jogo natural melhor`() {
        val wildcard = Card(Suit.SPADES, Rank.TWO)
        val config = MatchConfig(gameType = GameType.TRANCA, botDifficulty = BotDifficulty.HARD)
        val hand = listOf(
            card(Rank.FOUR),
            card(Rank.FIVE),
            card(Rank.SIX),
            card(Rank.SEVEN),
            wildcard,
            Card(Suit.CLUBS, Rank.KING)
        )

        val move = BotDecisionEngine.chooseMeldMove(
            hand = hand,
            tableMelds = emptyList(),
            config = config,
            cachetaTurnCard = null
        )

        assertNotNull(move)
        assertTrue(move!!.cardsFromHand.none { it.id == wildcard.id })
        assertTrue(GameRulesEngine.validateMeld(move.resultingMeld, config).isValid)
    }

    @Test
    fun `niveis possuem ritmo e profundidade realmente diferentes`() {
        val easy = BotDecisionEngine.timing(BotDifficulty.EASY)
        val normal = BotDecisionEngine.timing(BotDifficulty.NORMAL)
        val hard = BotDecisionEngine.timing(BotDifficulty.HARD)

        assertTrue(easy.drawDelayMillis > normal.drawDelayMillis)
        assertTrue(normal.drawDelayMillis > hard.drawDelayMillis)
        assertTrue(easy.maxMeldActions < normal.maxMeldActions)
        assertTrue(normal.maxMeldActions < hard.maxMeldActions)
    }

    @Test
    fun `maquina preserva uma carta quando ainda nao pode bater`() {
        val config = MatchConfig(gameType = GameType.TRANCA, botDifficulty = BotDifficulty.HARD)
        val hand = listOf(card(Rank.FOUR), card(Rank.FIVE), card(Rank.SIX))
        val move = BotDecisionEngine.MeldMove(cardsFromHand = hand, resultingMeld = hand)

        val allowed = BotDecisionEngine.keepsLegalTurnFlow(
            hand = hand,
            tableMelds = emptyList(),
            move = move,
            cardsReleasedAfterMove = emptyList(),
            hasPickedMorto = true,
            mortosLeft = 0,
            config = config
        )

        assertEquals(false, allowed)
    }

    @Test
    fun `maquina pode esvaziar a mao quando vai receber o morto`() {
        val config = MatchConfig(gameType = GameType.TRANCA, botDifficulty = BotDifficulty.NORMAL)
        val hand = listOf(card(Rank.FOUR), card(Rank.FIVE), card(Rank.SIX))
        val move = BotDecisionEngine.MeldMove(cardsFromHand = hand, resultingMeld = hand)

        val allowed = BotDecisionEngine.keepsLegalTurnFlow(
            hand = hand,
            tableMelds = emptyList(),
            move = move,
            cardsReleasedAfterMove = emptyList(),
            hasPickedMorto = false,
            mortosLeft = 1,
            config = config
        )

        assertTrue(allowed)
    }

    @Test
    fun `maquina pode esvaziar a mao ao completar uma canastra limpa`() {
        val config = MatchConfig(gameType = GameType.TRANCA, botDifficulty = BotDifficulty.HARD)
        val table = listOf(listOf(card(Rank.FOUR), card(Rank.FIVE), card(Rank.SIX)))
        val hand = listOf(card(Rank.SEVEN), card(Rank.EIGHT), card(Rank.NINE), card(Rank.TEN))
        val resultingMeld = table.first() + hand
        val move = BotDecisionEngine.MeldMove(
            cardsFromHand = hand,
            resultingMeld = resultingMeld,
            replaceIndex = 0
        )

        val allowed = BotDecisionEngine.keepsLegalTurnFlow(
            hand = hand,
            tableMelds = table,
            move = move,
            cardsReleasedAfterMove = emptyList(),
            hasPickedMorto = true,
            mortosLeft = 0,
            config = config
        )

        assertTrue(allowed)
    }

    private fun card(rank: Rank): Card = Card(Suit.HEARTS, rank)
}

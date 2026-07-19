package com.brunogiovani.cachetaburaco.domain.usecases

import com.brunogiovani.cachetaburaco.domain.models.Card
import com.brunogiovani.cachetaburaco.domain.models.GameType
import com.brunogiovani.cachetaburaco.domain.models.MatchConfig
import com.brunogiovani.cachetaburaco.domain.models.Rank
import com.brunogiovani.cachetaburaco.domain.models.Suit
import com.brunogiovani.cachetaburaco.domain.usecases.GameRulesEngine.MeldType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameRulesEngineTest {

    @Test
    fun `cacheta uses next rank same suit as wildcard`() {
        val turnCard = card(Rank.KING, Suit.CLUBS)
        val config = MatchConfig(gameType = GameType.CACHETA)

        assertEquals(Rank.ACE, GameRulesEngine.getCachetaWildcardRank(turnCard))

        val result = GameRulesEngine.validateMeld(
            cards = listOf(
                card(Rank.QUEEN, Suit.CLUBS),
                card(Rank.KING, Suit.CLUBS),
                card(Rank.ACE, Suit.CLUBS)
            ),
            config = config,
            cachetaTurnCard = turnCard
        )

        assertTrue(result.reason, result.isValid)
        assertEquals(MeldType.SEQUENCIA, result.meldType)
    }

    @Test
    fun `cacheta wildcard must match vira suit`() {
        val config = MatchConfig(gameType = GameType.CACHETA)
        val turnCard = card(Rank.FOUR, Suit.SPADES)

        val result = GameRulesEngine.validateMeld(
            cards = listOf(
                card(Rank.THREE, Suit.HEARTS),
                card(Rank.FOUR, Suit.HEARTS),
                card(Rank.FIVE, Suit.DIAMONDS)
            ),
            config = config,
            cachetaTurnCard = turnCard
        )

        assertFalse(result.isValid)
    }

    @Test
    fun `buraco two always makes canastra dirty`() {
        val config = MatchConfig(gameType = GameType.BURACO)
        val meld = listOf(
            card(Rank.THREE, Suit.HEARTS),
            card(Rank.FOUR, Suit.HEARTS),
            card(Rank.FIVE, Suit.HEARTS),
            card(Rank.SIX, Suit.HEARTS),
            card(Rank.SEVEN, Suit.HEARTS),
            card(Rank.EIGHT, Suit.HEARTS),
            card(Rank.TWO, Suit.CLUBS)
        )

        val result = GameRulesEngine.validateMeld(meld, config)

        assertTrue(result.reason, result.isValid)
        assertEquals(MeldType.CANASTRA_SUJA, result.meldType)
    }

    @Test
    fun `buraco and tranca reject more than one wildcard in same meld`() {
        val meld = listOf(
            card(Rank.THREE, Suit.HEARTS),
            card(Rank.FOUR, Suit.HEARTS),
            card(Rank.TWO, Suit.CLUBS),
            card(Rank.TWO, Suit.SPADES)
        )

        val buraco = GameRulesEngine.validateMeld(meld, MatchConfig(gameType = GameType.BURACO))
        val tranca = GameRulesEngine.validateMeld(meld, MatchConfig(gameType = GameType.TRANCA))

        assertFalse(buraco.isValid)
        assertFalse(tranca.isValid)
        assertTrue(buraco.reason.contains("um curinga"))
        assertTrue(tranca.reason.contains("um curinga"))
    }

    @Test
    fun `buraco can require clean canastra to win`() {
        val config = MatchConfig(
            gameType = GameType.BURACO,
            requireCleanCanastraToWin = true
        )
        val dirtyCanastra = listOf(
            card(Rank.THREE, Suit.SPADES),
            card(Rank.FOUR, Suit.SPADES),
            card(Rank.FIVE, Suit.SPADES),
            card(Rank.SIX, Suit.SPADES),
            card(Rank.SEVEN, Suit.SPADES),
            card(Rank.EIGHT, Suit.SPADES),
            card(Rank.TWO, Suit.HEARTS)
        )

        val result = GameRulesEngine.canDeclareWin(
            hand = emptyList(),
            tableMelds = listOf(dirtyCanastra),
            hasMorto = false,
            config = config
        )

        assertFalse(result.canWin)
        assertTrue(result.reason.contains("Canastra Limpa"))
    }

    @Test
    fun `buraco clean canastra allows win after morto`() {
        val config = MatchConfig(
            gameType = GameType.BURACO,
            requireCleanCanastraToWin = true
        )
        val cleanCanastra = listOf(
            card(Rank.SEVEN, Suit.DIAMONDS),
            card(Rank.EIGHT, Suit.DIAMONDS),
            card(Rank.NINE, Suit.DIAMONDS),
            card(Rank.TEN, Suit.DIAMONDS),
            card(Rank.JACK, Suit.DIAMONDS),
            card(Rank.QUEEN, Suit.DIAMONDS),
            card(Rank.KING, Suit.DIAMONDS)
        )

        val result = GameRulesEngine.canDeclareWin(
            hand = emptyList(),
            tableMelds = listOf(cleanCanastra),
            hasMorto = false,
            config = config
        )

        assertTrue(result.reason, result.canWin)
    }

    @Test
    fun `tranca can require clean canastra to win`() {
        val config = MatchConfig(
            gameType = GameType.TRANCA,
            requireCleanCanastraToWin = true
        )
        val dirtyCanastra = listOf(
            card(Rank.FOUR, Suit.SPADES),
            card(Rank.FIVE, Suit.SPADES),
            card(Rank.SIX, Suit.SPADES),
            card(Rank.SEVEN, Suit.SPADES),
            card(Rank.EIGHT, Suit.SPADES),
            card(Rank.NINE, Suit.SPADES),
            card(Rank.TWO, Suit.HEARTS)
        )

        val result = GameRulesEngine.canDeclareWin(
            hand = emptyList(),
            tableMelds = listOf(dirtyCanastra),
            hasMorto = false,
            config = config
        )

        assertFalse(result.canWin)
        assertTrue(result.reason.contains("Canastra Limpa"))
    }

    @Test
    fun `tranca can allow dirty canastra to win when room disables clean requirement`() {
        val config = MatchConfig(
            gameType = GameType.TRANCA,
            requireCleanCanastraToWin = false
        )
        val dirtyCanastra = listOf(
            card(Rank.FOUR, Suit.CLUBS),
            card(Rank.FIVE, Suit.CLUBS),
            card(Rank.SIX, Suit.CLUBS),
            card(Rank.SEVEN, Suit.CLUBS),
            card(Rank.EIGHT, Suit.CLUBS),
            card(Rank.NINE, Suit.CLUBS),
            card(Rank.TWO, Suit.DIAMONDS)
        )

        val result = GameRulesEngine.canDeclareWin(
            hand = emptyList(),
            tableMelds = listOf(dirtyCanastra),
            hasMorto = false,
            config = config
        )

        assertTrue(result.reason, result.canWin)
    }

    @Test
    fun `charuto follows room option`() {
        val trinca = listOf(
            card(Rank.NINE, Suit.HEARTS),
            card(Rank.NINE, Suit.CLUBS),
            card(Rank.NINE, Suit.SPADES)
        )

        val blocked = GameRulesEngine.validateMeld(
            cards = trinca,
            config = MatchConfig(gameType = GameType.BURACO, allowCharutos = false)
        )
        val allowed = GameRulesEngine.validateMeld(
            cards = trinca,
            config = MatchConfig(gameType = GameType.BURACO, allowCharutos = true)
        )

        assertFalse(blocked.isValid)
        assertTrue(allowed.reason, allowed.isValid)
        assertEquals(MeldType.TRINCA, allowed.meldType)
    }

    @Test
    fun `sequence rejects duplicated natural cards from two decks`() {
        val result = GameRulesEngine.validateMeld(
            cards = listOf(
                card(Rank.SEVEN, Suit.HEARTS),
                card(Rank.SEVEN, Suit.HEARTS),
                card(Rank.EIGHT, Suit.HEARTS)
            ),
            config = MatchConfig(gameType = GameType.BURACO)
        )

        assertFalse(result.isValid)
    }

    @Test
    fun `tranca black three locks discard and red three is auto melded`() {
        val blackThree = card(Rank.THREE, Suit.SPADES)
        val redThree = card(Rank.THREE, Suit.HEARTS)
        val hand = listOf(redThree, card(Rank.ACE, Suit.CLUBS))

        assertTrue(GameRulesEngine.isDiscardLocked(blackThree, GameType.TRANCA))
        assertFalse(GameRulesEngine.canDrawFromDiscard(blackThree, MatchConfig(gameType = GameType.TRANCA)).allowed)

        val (updatedHand, updatedMelds) = GameRulesEngine.handleThreeReds(
            hand = hand,
            tableMelds = emptyList(),
            gameType = GameType.TRANCA
        )

        assertEquals(listOf(card(Rank.ACE, Suit.CLUBS)), updatedHand)
        assertEquals(listOf(listOf(redThree)), updatedMelds)
    }

    @Test
    fun `buraco two is worth ten points`() {
        val score = GameRulesEngine.calculateBuracoTrancaScore(
            hand = listOf(card(Rank.TWO, Suit.CLUBS)),
            tableMelds = emptyList(),
            hasMorto = false,
            didWin = false,
            gameType = GameType.BURACO
        )

        assertEquals(10, score.handPenalty)
        assertEquals(-10, score.totalRoundPoints)
    }

    @Test
    fun `tranca red three scores positive only with canastra`() {
        val redThree = card(Rank.THREE, Suit.HEARTS)
        val cleanCanastra = listOf(
            card(Rank.FOUR, Suit.CLUBS),
            card(Rank.FIVE, Suit.CLUBS),
            card(Rank.SIX, Suit.CLUBS),
            card(Rank.SEVEN, Suit.CLUBS),
            card(Rank.EIGHT, Suit.CLUBS),
            card(Rank.NINE, Suit.CLUBS),
            card(Rank.TEN, Suit.CLUBS)
        )

        val withoutCanastra = GameRulesEngine.calculateBuracoTrancaScore(
            hand = emptyList(),
            tableMelds = listOf(listOf(redThree)),
            hasMorto = false,
            didWin = false,
            gameType = GameType.TRANCA
        )
        val withCanastra = GameRulesEngine.calculateBuracoTrancaScore(
            hand = emptyList(),
            tableMelds = listOf(listOf(redThree), cleanCanastra),
            hasMorto = false,
            didWin = false,
            gameType = GameType.TRANCA
        )

        assertEquals(-100, withoutCanastra.tablePoints)
        assertEquals(-100, withoutCanastra.totalRoundPoints)
        assertEquals(170, withCanastra.tablePoints)
        assertEquals(370, withCanastra.totalRoundPoints)
    }

    @Test
    fun `match config roundtrips expanded rule fields`() {
        val original = MatchConfig(
            gameType = GameType.TRANCA,
            maxPlayers = 4,
            allowWildcards = false,
            allowDrawFromDiscard = true,
            allowCharutos = false,
            cachetaCardsPerPlayer = 10,
            cachetaStartsWithDiscard = true,
            requireCleanCanastraToWin = false,
            autoMeldTrancaRedThrees = false,
            autoSortHand = false,
            pointLimit = 3000
        )

        val restored = MatchConfig.deserialize(original.serialize())

        assertEquals(original, restored)
    }

    private fun card(rank: Rank, suit: Suit): Card = Card(suit = suit, rank = rank)

    @Test
    fun `sortMeld places wildcard inside gap to complete sequence`() {
        val wildcard = card(Rank.TWO, Suit.HEARTS) // Curinga fixo
        val cards = listOf(
            card(Rank.THREE, Suit.SPADES),
            card(Rank.FIVE, Suit.SPADES),
            wildcard
        )
        val sorted = GameRulesEngine.sortMeld(cards, MatchConfig(gameType = GameType.BURACO))
        // Esperado: 3 ♠, 2 ♥ (como 4), 5 ♠
        assertEquals(3, sorted.size)
        assertEquals(Rank.THREE, sorted[0].rank)
        assertEquals(Rank.TWO, sorted[1].rank)
        assertEquals(Rank.FIVE, sorted[2].rank)
    }

    @Test
    fun `sortMeld places surplus wildcard at the beginning if gap is filled`() {
        val wildcard = card(Rank.TWO, Suit.HEARTS)
        val cards = listOf(
            card(Rank.THREE, Suit.SPADES),
            card(Rank.FOUR, Suit.SPADES),
            wildcard
        )
        val sorted = GameRulesEngine.sortMeld(cards, MatchConfig(gameType = GameType.BURACO))
        // Esperado: 2 ♥, 3 ♠, 4 ♠
        assertEquals(Rank.TWO, sorted[0].rank)
        assertEquals(Rank.THREE, sorted[1].rank)
        assertEquals(Rank.FOUR, sorted[2].rank)
    }

    @Test
    fun `tranca scores double penalty for four red threes without canastra`() {
        val r3h = card(Rank.THREE, Suit.HEARTS)
        val r3d = card(Rank.THREE, Suit.DIAMONDS)
        val r3h2 = card(Rank.THREE, Suit.HEARTS) // Segundo deck
        val r3d2 = card(Rank.THREE, Suit.DIAMONDS)

        // Se temos os 4 vermelhos baixados mas nenhuma canastra
        val score = GameRulesEngine.calculateBuracoTrancaScore(
            hand = emptyList(),
            tableMelds = listOf(listOf(r3h, r3d, r3h2, r3d2)),
            hasMorto = false,
            didWin = false,
            gameType = GameType.TRANCA
        )
        // Pontuacao deve ser dobrada de -400 para -800
        assertEquals(-800, score.tablePoints)
        assertEquals(-800, score.totalRoundPoints)
    }

    @Test
    fun `tranca scores double positive for four red threes with canastra`() {
        val r3h = card(Rank.THREE, Suit.HEARTS)
        val r3d = card(Rank.THREE, Suit.DIAMONDS)
        val r3h2 = card(Rank.THREE, Suit.HEARTS)
        val r3d2 = card(Rank.THREE, Suit.DIAMONDS)
        val cleanCanastra = listOf(
            card(Rank.FOUR, Suit.CLUBS),
            card(Rank.FIVE, Suit.CLUBS),
            card(Rank.SIX, Suit.CLUBS),
            card(Rank.SEVEN, Suit.CLUBS),
            card(Rank.EIGHT, Suit.CLUBS),
            card(Rank.NINE, Suit.CLUBS),
            card(Rank.TEN, Suit.CLUBS)
        )

        // Com canastra, 4 vermelhos valem 800 pontos
        val score = GameRulesEngine.calculateBuracoTrancaScore(
            hand = emptyList(),
            tableMelds = listOf(listOf(r3h, r3d, r3h2, r3d2), cleanCanastra),
            hasMorto = false,
            didWin = false,
            gameType = GameType.TRANCA
        )
        // Canastra limpa = 200 pts. Cartas da canastra = 7 * 5 = 35. Vermelhos = 800 pts. Total = 1035.
        assertEquals(870, score.tablePoints) // 800 (3s reds) + 70 (normal cards inside melds)
        assertEquals(1070, score.totalRoundPoints) // 870 + 200 (canastra bonus)
    }

    @Test
    fun `cacheta trinca rejects duplicated suits`() {
        val config = MatchConfig(gameType = GameType.CACHETA)
        val result = GameRulesEngine.validateMeld(
            cards = listOf(
                card(Rank.SEVEN, Suit.HEARTS),
                card(Rank.SEVEN, Suit.HEARTS),
                card(Rank.SEVEN, Suit.DIAMONDS)
            ),
            config = config
        )
        assertFalse(result.isValid)
    }

    @Test
    fun `tranca black three in hand is penalized by 100 points`() {
        val score = GameRulesEngine.calculateBuracoTrancaScore(
            hand = listOf(card(Rank.THREE, Suit.SPADES), card(Rank.ACE, Suit.DIAMONDS)),
            tableMelds = emptyList(),
            hasMorto = false,
            didWin = false,
            gameType = GameType.TRANCA
        )
        // 3 preto = 100 pts. Ace = 10 pts. total = 110 pts penalty.
        assertEquals(110, score.handPenalty)
        assertEquals(-110, score.totalRoundPoints)
    }

}

package com.brunogiovani.cachetaburaco.domain.usecases

import com.brunogiovani.cachetaburaco.domain.models.Card
import com.brunogiovani.cachetaburaco.domain.models.BotDifficulty
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
        val config = MatchConfig(gameType = GameType.BURACO, allowWildcards = false)
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
    fun `printed joker cannot masquerade as natural card when wildcards are disabled`() {
        val forgedJoker = Card(
            suit = Suit.CLUBS,
            rank = Rank.FIVE,
            isJoker = true
        )

        GameType.entries.forEach { gameType ->
            val result = GameRulesEngine.validateMeld(
                cards = listOf(
                    card(Rank.FIVE, Suit.HEARTS),
                    card(Rank.FIVE, Suit.SPADES),
                    forgedJoker
                ),
                config = MatchConfig(
                    gameType = gameType,
                    allowWildcards = false,
                    allowCharutos = true
                )
            )

            assertFalse("$gameType aceitou um Joker desabilitado como carta natural", result.isValid)
            assertTrue(result.reason.contains("desabilitados"))
        }
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
    fun `buraco non uniform card points use configured card table`() {
        val score = GameRulesEngine.calculateBuracoTrancaScore(
            hand = listOf(
                card(Rank.ACE, Suit.CLUBS),
                card(Rank.FOUR, Suit.DIAMONDS),
                card(Rank.EIGHT, Suit.HEARTS),
                card(Rank.TWO, Suit.SPADES),
                joker()
            ),
            tableMelds = emptyList(),
            hasMorto = false,
            didWin = false,
            gameType = GameType.BURACO,
            uniformCardPoints = false
        )

        assertEquals(60, score.handPenalty)
        assertEquals(-60, score.totalRoundPoints)
    }

    @Test
    fun `buraco uniform card points make every card worth ten`() {
        val score = GameRulesEngine.calculateBuracoTrancaScore(
            hand = listOf(
                card(Rank.ACE, Suit.CLUBS),
                card(Rank.FOUR, Suit.DIAMONDS),
                card(Rank.EIGHT, Suit.HEARTS),
                card(Rank.TWO, Suit.SPADES),
                joker()
            ),
            tableMelds = emptyList(),
            hasMorto = false,
            didWin = false,
            gameType = GameType.BURACO,
            uniformCardPoints = true
        )

        assertEquals(50, score.handPenalty)
        assertEquals(-50, score.totalRoundPoints)
    }

    @Test
    fun `tranca uniform card points count table and hand as ten each`() {
        val score = GameRulesEngine.calculateBuracoTrancaScore(
            hand = listOf(
                card(Rank.ACE, Suit.CLUBS),
                card(Rank.FOUR, Suit.DIAMONDS)
            ),
            tableMelds = listOf(
                listOf(
                    card(Rank.SEVEN, Suit.HEARTS),
                    card(Rank.EIGHT, Suit.HEARTS),
                    card(Rank.NINE, Suit.HEARTS)
                )
            ),
            hasMorto = true,
            didWin = true,
            gameType = GameType.TRANCA,
            uniformCardPoints = true
        )

        assertEquals(30, score.tablePoints)
        assertEquals(20, score.handPenalty)
        assertEquals(-100, score.mortoPenalty)
        assertEquals(100, score.winBonus)
        assertEquals(10, score.totalRoundPoints)
    }

    @Test
    fun `tranca non uniform card points use card values for table and hand`() {
        val score = GameRulesEngine.calculateBuracoTrancaScore(
            hand = listOf(
                card(Rank.ACE, Suit.CLUBS),
                card(Rank.FOUR, Suit.DIAMONDS)
            ),
            tableMelds = listOf(
                listOf(
                    card(Rank.SEVEN, Suit.HEARTS),
                    card(Rank.EIGHT, Suit.HEARTS),
                    card(Rank.NINE, Suit.HEARTS)
                )
            ),
            hasMorto = true,
            didWin = true,
            gameType = GameType.TRANCA,
            uniformCardPoints = false
        )

        assertEquals(25, score.tablePoints)
        assertEquals(20, score.handPenalty)
        assertEquals(-100, score.mortoPenalty)
        assertEquals(100, score.winBonus)
        assertEquals(5, score.totalRoundPoints)
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
        assertEquals(150, withCanastra.tablePoints)
        assertEquals(350, withCanastra.totalRoundPoints)
    }

    @Test
    fun `match config roundtrips expanded rule fields`() {
        val original = MatchConfig(
            gameType = GameType.TRANCA,
            maxPlayers = 4,
            allowWildcards = false,
            allowDrawFromDiscard = true,
            allowCharutos = false,
            cachetaStartsWithDiscard = true,
            requireCleanCanastraToWin = false,
            autoMeldTrancaRedThrees = false,
            penalizeBlackThreesInHand = false,
            autoSortHand = false,
            uniformCardPoints = true,
            botDifficulty = BotDifficulty.HARD,
            pointLimit = 3000
        )

        val restored = MatchConfig.deserialize(original.serialize())

        assertEquals(original, restored)
    }

    @Test
    fun `legacy cacheta hand sizes are normalized to nine`() {
        val sevenCardRoom = MatchConfig.deserialize(
            "CACHETA,2,true,true,true,7,false,true,true,true,true,false,NORMAL,FREE,5"
        )
        val tenCardRoom = MatchConfig.deserialize(
            "CACHETA,2,true,true,true,10,false,true,true,true,true,false,NORMAL,FREE,5"
        )

        assertEquals(MatchConfig.CACHETA_HAND_SIZE, sevenCardRoom.cardsPerPlayer)
        assertEquals(MatchConfig.CACHETA_HAND_SIZE, tenCardRoom.cardsPerPlayer)
        assertEquals("9", sevenCardRoom.serialize().split(",")[5])
        assertEquals("9", tenCardRoom.serialize().split(",")[5])
    }

    @Test
    fun `tranca black three hand penalty follows room option`() {
        val heavy = GameRulesEngine.calculateBuracoTrancaScore(
            hand = listOf(card(Rank.THREE, Suit.SPADES)),
            tableMelds = emptyList(),
            hasMorto = false,
            didWin = false,
            gameType = GameType.TRANCA,
            penalizeBlackThreesInHand = true
        )
        val normal = GameRulesEngine.calculateBuracoTrancaScore(
            hand = listOf(card(Rank.THREE, Suit.SPADES)),
            tableMelds = emptyList(),
            hasMorto = false,
            didWin = false,
            gameType = GameType.TRANCA,
            penalizeBlackThreesInHand = false
        )

        assertEquals(100, heavy.handPenalty)
        assertEquals(100, heavy.blackThreePenalty)
        assertEquals(5, normal.handPenalty)
        assertEquals(5, normal.blackThreePenalty)
    }

    private fun card(rank: Rank, suit: Suit): Card = Card(suit = suit, rank = rank)
    private fun joker(): Card = Card(suit = Suit.SPADES, rank = Rank.ACE, isJoker = true)

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
        // Pontuação deve ser dobrada de -400 para -800
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
        // Canastra limpa = 200 pts. Cartas comuns por valor = 50. Vermelhos = 800 pts.
        assertEquals(850, score.tablePoints)
        assertEquals(1050, score.totalRoundPoints)
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
    fun `cacheta trinca must have exactly three cards including wildcard`() {
        val config = MatchConfig(gameType = GameType.CACHETA)
        val turnCard = card(Rank.FOUR, Suit.SPADES)
        val result = GameRulesEngine.validateMeld(
            cards = listOf(
                card(Rank.FIVE, Suit.HEARTS),
                card(Rank.FIVE, Suit.DIAMONDS),
                card(Rank.FIVE, Suit.CLUBS),
                card(Rank.FIVE, Suit.SPADES)
            ),
            config = config,
            cachetaTurnCard = turnCard
        )

        assertFalse(result.isValid)
    }

    // Este teste afirmava o contrario ("can have four cards") desde o primeiro
    // commit do projeto -- era suposicao do codigo original, nunca uma regra
    // conferida. Na Cacheta de verdade a mao de 9 cartas vira exatamente tres
    // jogos de tres, entao sequencia de 4 nunca deveria ter passado. Bruno
    // reportou (2026-08-11) e a regra foi corrigida no GameRulesEngine.
    @Test
    fun `cacheta sequence cannot have four cards`() {
        val config = MatchConfig(gameType = GameType.CACHETA)
        val result = GameRulesEngine.validateMeld(
            cards = listOf(
                card(Rank.SEVEN, Suit.HEARTS),
                card(Rank.EIGHT, Suit.HEARTS),
                card(Rank.NINE, Suit.HEARTS),
                card(Rank.TEN, Suit.HEARTS)
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
        // O 3 preto vale 100 pontos e o Ás vale 15, totalizando 115 de penalidade.
        assertEquals(115, score.handPenalty)
        assertEquals(-115, score.totalRoundPoints)
    }

    @Test
    fun `tranca reports red three hand penalty separately from regular cards`() {
        val score = GameRulesEngine.calculateBuracoTrancaScore(
            hand = listOf(card(Rank.THREE, Suit.HEARTS), card(Rank.ACE, Suit.DIAMONDS)),
            tableMelds = emptyList(),
            hasMorto = false,
            didWin = false,
            gameType = GameType.TRANCA
        )

        assertEquals(1, score.handCardCount)
        assertEquals(1, score.redThreesInHand)
        assertEquals(100, score.redThreeHandPenalty)
        assertEquals(115, score.handPenalty)
        assertEquals(-115, score.totalRoundPoints)
    }

    @Test
    fun `sortHand keeps ace as lowest card in buraco same suit`() {
        val hand = listOf(
            card(Rank.KING, Suit.HEARTS),
            card(Rank.ACE, Suit.HEARTS),
            card(Rank.THREE, Suit.HEARTS)
        )

        val sorted = GameRulesEngine.sortHand(hand, GameType.BURACO)

        // No Buraco o Ás continua contando como carta de valor 1: um curinga
        // pode preencher a lacuna do 2 e formar Ás-3-4-5..., então ele deve
        // aparecer antes das demais cartas do naipe (comportamento já existente).
        assertEquals(
            listOf(Rank.ACE, Rank.THREE, Rank.KING),
            sorted.map { it.rank }
        )
    }

    @Test
    fun `sortHand orders ace after king in tranca same suit`() {
        val hand = listOf(
            card(Rank.KING, Suit.SPADES),
            card(Rank.ACE, Suit.SPADES),
            card(Rank.FOUR, Suit.SPADES)
        )

        val sorted = GameRulesEngine.sortHand(hand, GameType.TRANCA)

        // Na Tranca o 3 não entra em jogo comum (validateTrancaMeld) e o 2 já
        // é sempre curinga, então uma sequência baixa com Ás exigiria 2
        // curingas (acima do limite de 1 por jogo). O Ás só forma sequência
        // alta (Q-K-Ás) e deve aparecer depois do Rei.
        assertEquals(
            listOf(Rank.FOUR, Rank.KING, Rank.ACE),
            sorted.map { it.rank }
        )
    }

    @Test
    fun `sortHand keeps wildcards after every natural card regardless of ace handling`() {
        val hand = listOf(
            card(Rank.ACE, Suit.CLUBS),
            card(Rank.KING, Suit.CLUBS),
            card(Rank.TWO, Suit.DIAMONDS),
            joker()
        )

        val trancaSorted = GameRulesEngine.sortHand(hand, GameType.TRANCA)
        val buracoSorted = GameRulesEngine.sortHand(hand, GameType.BURACO)

        assertEquals(
            listOf(Rank.KING, Rank.ACE, Rank.TWO, Rank.ACE), // último ACE é o joker
            trancaSorted.map { it.rank }
        )
        assertEquals(
            listOf(Rank.ACE, Rank.KING, Rank.TWO, Rank.ACE),
            buracoSorted.map { it.rank }
        )
    }

    @Test
    fun `buraco allows ace low sequence when a wildcard fills the missing two`() {
        // Confirma, a partir da validação já existente (não alterada), que o
        // Buraco permite Ás-3-4 com um curinga cobrindo a lacuna do 2 — por
        // isso sortHand não move o Ás para depois do Rei nesse modo.
        val result = GameRulesEngine.validateMeld(
            cards = listOf(
                card(Rank.ACE, Suit.HEARTS),
                card(Rank.THREE, Suit.HEARTS),
                card(Rank.FOUR, Suit.HEARTS),
                joker()
            ),
            config = MatchConfig(gameType = GameType.BURACO)
        )

        assertTrue(result.reason, result.isValid)
        assertEquals(MeldType.SEQUENCIA, result.meldType)
    }

    @Test
    fun `tranca cannot build ace low sequence even with a wildcard`() {
        // Como o 3 é banido de jogos comuns na Tranca (validateTrancaMeld) e o
        // 2 já é sempre curinga, a lacuna entre Ás e 4 tem tamanho 2 (cobrindo
        // 2 e 3), exigindo 2 curingas — acima do limite de 1 por jogo. Por
        // isso a sequência baixa com Ás é inalcançável na Tranca, e sortHand
        // trata o Ás como carta alta nesse modo.
        val result = GameRulesEngine.validateMeld(
            cards = listOf(
                card(Rank.ACE, Suit.HEARTS),
                card(Rank.FOUR, Suit.HEARTS),
                joker()
            ),
            config = MatchConfig(gameType = GameType.TRANCA)
        )

        assertFalse(result.reason, result.isValid)
    }


    // ─── Cacheta: jogo fecha em 3 cartas e nao cresce ─────────────────────
    // Bruno reportou que dava pra baixar sequencia de 4+ e ainda encaixar
    // carta em jogo ja baixado. A trinca ja era travada (exactSize = 3), mas
    // checkSequencia exigia minimo 3 e nao tinha maximo nenhum.

    @Test
    fun `cacheta aceita sequencia de exatamente tres cartas`() {
        val config = MatchConfig(gameType = GameType.CACHETA)

        val result = GameRulesEngine.validateMeld(
            cards = listOf(
                card(Rank.FOUR, Suit.HEARTS),
                card(Rank.FIVE, Suit.HEARTS),
                card(Rank.SIX, Suit.HEARTS)
            ),
            config = config
        )

        assertTrue(result.reason, result.isValid)
    }

    @Test
    fun `cacheta recusa sequencia com mais de tres cartas`() {
        val config = MatchConfig(gameType = GameType.CACHETA)

        val result = GameRulesEngine.validateMeld(
            cards = listOf(
                card(Rank.FOUR, Suit.HEARTS),
                card(Rank.FIVE, Suit.HEARTS),
                card(Rank.SIX, Suit.HEARTS),
                card(Rank.SEVEN, Suit.HEARTS)
            ),
            config = config
        )

        assertFalse("sequencia de 4 nao pode valer na Cacheta", result.isValid)
    }

    @Test
    fun `cacheta recusa encaixar carta em sequencia ja baixada`() {
        val config = MatchConfig(gameType = GameType.CACHETA)
        // "Encaixar" revalida o jogo inteiro (baixado + carta nova). Se o jogo
        // ja tem 3, qualquer encaixe cai em 4 e tem que ser recusado.
        val jogoBaixado = listOf(
            card(Rank.FOUR, Suit.HEARTS),
            card(Rank.FIVE, Suit.HEARTS),
            card(Rank.SIX, Suit.HEARTS)
        )
        val novaCarta = card(Rank.SEVEN, Suit.HEARTS)

        val result = GameRulesEngine.validateMeld(jogoBaixado + novaCarta, config)

        assertFalse("nao pode crescer jogo baixado na Cacheta", result.isValid)
    }

    @Test
    fun `cacheta recusa encaixar quarta carta em trinca ja baixada`() {
        val config = MatchConfig(gameType = GameType.CACHETA)
        val trincaBaixada = listOf(
            card(Rank.NINE, Suit.HEARTS),
            card(Rank.NINE, Suit.CLUBS),
            card(Rank.NINE, Suit.SPADES)
        )
        val quartoNove = card(Rank.NINE, Suit.DIAMONDS)

        val result = GameRulesEngine.validateMeld(trincaBaixada + quartoNove, config)

        assertFalse("nao pode crescer trinca baixada na Cacheta", result.isValid)
    }

    @Test
    fun `buraco continua aceitando sequencia longa`() {
        // A trava de 3 cartas e so da Cacheta -- Buraco precisa de 7 pra
        // canastra, entao nao pode ter sido afetado.
        val config = MatchConfig(gameType = GameType.BURACO)

        val result = GameRulesEngine.validateMeld(
            cards = listOf(
                card(Rank.FOUR, Suit.HEARTS),
                card(Rank.FIVE, Suit.HEARTS),
                card(Rank.SIX, Suit.HEARTS),
                card(Rank.SEVEN, Suit.HEARTS),
                card(Rank.EIGHT, Suit.HEARTS)
            ),
            config = config
        )

        assertTrue(result.reason, result.isValid)
    }
}

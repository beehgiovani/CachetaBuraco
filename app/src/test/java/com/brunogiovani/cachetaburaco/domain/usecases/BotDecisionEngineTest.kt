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

    // ─────────────────────────────────────────────────────────────────────
    // Cenarios fixos: mesmo estado de jogo avaliado nas 3 dificuldades.
    // Provam calibracao real (nao apenas constantes diferentes "no papel")
    // e que nenhum nivel entrega a partida.
    // ─────────────────────────────────────────────────────────────────────

    @Test
    fun `nenhuma dificuldade entrega a carta que estende jogo do adversario mesmo com cluster de mesmo rank na mao`() {
        // Antes da correcao, o bonus de risco do Facil (28) podia ser superado pelo
        // connectionScore de um cluster de mesmo rank (3 noves = 2*24 = 48), fazendo
        // o Facil descartar a carta perigosa (7 de copas) "sem querer". Este cenario
        // fixo prova que isso nao acontece mais em nenhuma dificuldade.
        val dangerous = card(Rank.SEVEN) // completa 4-5-6 de copas do adversario
        val cluster = listOf(
            Card(Suit.CLUBS, Rank.NINE),
            Card(Suit.DIAMONDS, Rank.NINE),
            Card(Suit.SPADES, Rank.NINE)
        )
        val hand = listOf(dangerous) + cluster
        val opponentTable = listOf(listOf(card(Rank.FOUR), card(Rank.FIVE), card(Rank.SIX)))

        listOf(BotDifficulty.EASY, BotDifficulty.NORMAL, BotDifficulty.HARD).forEach { difficulty ->
            val config = MatchConfig(gameType = GameType.TRANCA, botDifficulty = difficulty)
            val discard = BotDecisionEngine.chooseDiscard(
                hand = hand,
                tableMelds = emptyList(),
                opponentTableMelds = opponentTable,
                config = config,
                cachetaTurnCard = null
            )

            assertTrue(
                "dificuldade $difficulty descartou a carta perigosa",
                discard?.id != dangerous.id
            )
            assertEquals(Rank.NINE, discard?.rank)
        }
    }

    @Test
    fun `facil pode desperdicar chance de crescer o proprio jogo, mas normal e dificil aproveitam`() {
        // Mesmo estado do teste anterior, mas agora o jogo de mesa e DO PROPRIO time
        // (nao ha risco para o adversario). Aqui e aceitavel o Facil jogar pior --
        // ele descarta a carta que cresceria seu proprio jogo -- pois isso nao
        // "entrega a partida", apenas deixa de aproveitar uma jogada boa.
        val growsOwnMeld = card(Rank.SEVEN)
        val cluster = listOf(
            Card(Suit.CLUBS, Rank.NINE),
            Card(Suit.DIAMONDS, Rank.NINE),
            Card(Suit.SPADES, Rank.NINE)
        )
        val hand = listOf(growsOwnMeld) + cluster
        val ownTable = listOf(listOf(card(Rank.FOUR), card(Rank.FIVE), card(Rank.SIX)))

        val easyConfig = MatchConfig(gameType = GameType.TRANCA, botDifficulty = BotDifficulty.EASY)
        val normalConfig = MatchConfig(gameType = GameType.TRANCA, botDifficulty = BotDifficulty.NORMAL)
        val hardConfig = MatchConfig(gameType = GameType.TRANCA, botDifficulty = BotDifficulty.HARD)

        val easyDiscard = BotDecisionEngine.chooseDiscard(hand, ownTable, emptyList(), easyConfig, null)
        val normalDiscard = BotDecisionEngine.chooseDiscard(hand, ownTable, emptyList(), normalConfig, null)
        val hardDiscard = BotDecisionEngine.chooseDiscard(hand, ownTable, emptyList(), hardConfig, null)

        assertEquals(growsOwnMeld.id, easyDiscard?.id)
        assertEquals(Rank.NINE, normalDiscard?.rank)
        assertEquals(Rank.NINE, hardDiscard?.rank)
    }

    @Test
    fun `cacheta - dificil compra lixo em conexao que normal e facil ainda recusam`() {
        val topDiscard = card(Rank.SEVEN)
        val hand = listOf(
            Card(Suit.CLUBS, Rank.SEVEN),
            Card(Suit.DIAMONDS, Rank.KING),
            Card(Suit.SPADES, Rank.QUEEN),
            Card(Suit.CLUBS, Rank.TWO)
        )

        val results = BotDifficulty.entries.associateWith { difficulty ->
            BotDecisionEngine.chooseDrawSource(
                hand = hand,
                tableMelds = emptyList(),
                discardPile = listOf(topDiscard),
                config = MatchConfig(gameType = GameType.CACHETA, botDifficulty = difficulty),
                cachetaTurnCard = null
            )
        }

        assertEquals(BotDecisionEngine.DrawSource.DECK, results[BotDifficulty.EASY])
        assertEquals(BotDecisionEngine.DrawSource.DECK, results[BotDifficulty.NORMAL])
        assertEquals(BotDecisionEngine.DrawSource.DISCARD, results[BotDifficulty.HARD])
    }

    @Test
    fun `cacheta - normal e dificil compram lixo com conexao maior que facil ainda recusa`() {
        val topDiscard = card(Rank.SEVEN)
        val hand = listOf(
            Card(Suit.CLUBS, Rank.SEVEN),
            Card(Suit.HEARTS, Rank.NINE),
            Card(Suit.SPADES, Rank.KING),
            Card(Suit.CLUBS, Rank.TWO)
        )

        val results = BotDifficulty.entries.associateWith { difficulty ->
            BotDecisionEngine.chooseDrawSource(
                hand = hand,
                tableMelds = emptyList(),
                discardPile = listOf(topDiscard),
                config = MatchConfig(gameType = GameType.CACHETA, botDifficulty = difficulty),
                cachetaTurnCard = null
            )
        }

        assertEquals(BotDecisionEngine.DrawSource.DECK, results[BotDifficulty.EASY])
        assertEquals(BotDecisionEngine.DrawSource.DISCARD, results[BotDifficulty.NORMAL])
        assertEquals(BotDecisionEngine.DrawSource.DISCARD, results[BotDifficulty.HARD])
    }

    @Test
    fun `tranca - dificil arrisca comprar lixo pesado que normal e facil recusam`() {
        // Mesma compra obrigatoria (justifica com sequencia 4-5-6-7 de paus) mas com
        // um lixo grande e "pesado" (8 cartas inuteis por baixo do topo). Facil olha
        // so o tamanho do monte, Normal olha o peso do monte, Dificil pesa custo x
        // ganho estrategico e e o unico dos tres a arriscar aqui.
        val hand = listOf(Card(Suit.CLUBS, Rank.FOUR), Card(Suit.CLUBS, Rank.FIVE), Card(Suit.CLUBS, Rank.SIX))
        val topDiscard = Card(Suit.CLUBS, Rank.SEVEN)
        val heavyFillers = listOf(
            Card(Suit.HEARTS, Rank.KING), Card(Suit.DIAMONDS, Rank.KING), Card(Suit.SPADES, Rank.KING),
            Card(Suit.HEARTS, Rank.QUEEN), Card(Suit.DIAMONDS, Rank.QUEEN), Card(Suit.SPADES, Rank.QUEEN),
            Card(Suit.HEARTS, Rank.JACK), Card(Suit.DIAMONDS, Rank.JACK)
        )
        val discardPile = heavyFillers + topDiscard

        val results = BotDifficulty.entries.associateWith { difficulty ->
            BotDecisionEngine.chooseDrawSource(
                hand = hand,
                tableMelds = emptyList(),
                discardPile = discardPile,
                config = MatchConfig(gameType = GameType.TRANCA, botDifficulty = difficulty),
                cachetaTurnCard = null
            )
        }

        assertEquals(BotDecisionEngine.DrawSource.DECK, results[BotDifficulty.EASY])
        assertEquals(BotDecisionEngine.DrawSource.DECK, results[BotDifficulty.NORMAL])
        assertEquals(BotDecisionEngine.DrawSource.DISCARD, results[BotDifficulty.HARD])
    }

    @Test
    fun `facil gasta o curinga cedo enquanto normal e dificil preservam na mesma mao`() {
        val wildcard = Card(Suit.SPADES, Rank.TWO)
        val hand = listOf(
            card(Rank.FOUR), card(Rank.FIVE), card(Rank.SIX), card(Rank.SEVEN),
            wildcard,
            Card(Suit.CLUBS, Rank.KING)
        )

        val moves = BotDifficulty.entries.associateWith { difficulty ->
            BotDecisionEngine.chooseMeldMove(
                hand = hand,
                tableMelds = emptyList(),
                config = MatchConfig(gameType = GameType.TRANCA, botDifficulty = difficulty),
                cachetaTurnCard = null
            )
        }

        assertTrue(moves[BotDifficulty.EASY]!!.cardsFromHand.any { it.id == wildcard.id })
        assertTrue(moves[BotDifficulty.NORMAL]!!.cardsFromHand.none { it.id == wildcard.id })
        assertTrue(moves[BotDifficulty.HARD]!!.cardsFromHand.none { it.id == wildcard.id })
    }

    @Test
    fun `facil pode montar o jogo pior enquanto normal e dificil sempre montam o melhor`() {
        val betterExtension = Card(Suit.DIAMONDS, Rank.EIGHT) // completa 8-9-10-J, vale mais
        val worseExtension = card(Rank.SEVEN) // completa 4-5-6-7, vale menos
        val tableMelds = listOf(
            listOf(card(Rank.FOUR), card(Rank.FIVE), card(Rank.SIX)),
            listOf(Card(Suit.DIAMONDS, Rank.NINE), Card(Suit.DIAMONDS, Rank.TEN), Card(Suit.DIAMONDS, Rank.JACK))
        )
        val hand = listOf(worseExtension, betterExtension)

        val moves = BotDifficulty.entries.associateWith { difficulty ->
            BotDecisionEngine.chooseMeldMove(
                hand = hand,
                tableMelds = tableMelds,
                config = MatchConfig(gameType = GameType.TRANCA, botDifficulty = difficulty),
                cachetaTurnCard = null
            )
        }

        assertEquals(0, moves[BotDifficulty.EASY]?.replaceIndex)
        assertEquals(1, moves[BotDifficulty.NORMAL]?.replaceIndex)
        assertEquals(1, moves[BotDifficulty.HARD]?.replaceIndex)
    }

    private fun card(rank: Rank): Card = Card(Suit.HEARTS, rank)
}

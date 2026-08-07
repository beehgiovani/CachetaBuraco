package com.brunogiovani.cachetaburaco.domain.usecases

import com.brunogiovani.cachetaburaco.domain.models.BotDifficulty
import com.brunogiovani.cachetaburaco.domain.models.Card
import com.brunogiovani.cachetaburaco.domain.models.GameType
import com.brunogiovani.cachetaburaco.domain.models.MatchConfig
import com.brunogiovani.cachetaburaco.domain.models.Rank
import com.brunogiovani.cachetaburaco.domain.models.Suit
import kotlin.math.abs

/**
 * Decisoes puras da maquina.
 *
 * Deixei a leitura da jogada fora do transporte para conseguir testar cada nivel
 * sem depender de Android, tempo de animacao ou mensagens de rede.
 */
object BotDecisionEngine {

    enum class DrawSource { DECK, DISCARD }

    data class MeldMove(
        val cardsFromHand: List<Card>,
        val resultingMeld: List<Card>,
        val replaceIndex: Int = -1,
        val score: Int = 0
    )

    data class Timing(
        val drawDelayMillis: Long,
        val actionDelayMillis: Long,
        val maxMeldActions: Int
    )

    fun timing(difficulty: BotDifficulty): Timing = when (difficulty) {
        BotDifficulty.EASY -> Timing(drawDelayMillis = 900, actionDelayMillis = 1_050, maxMeldActions = 1)
        BotDifficulty.NORMAL -> Timing(drawDelayMillis = 650, actionDelayMillis = 820, maxMeldActions = 4)
        BotDifficulty.HARD -> Timing(drawDelayMillis = 480, actionDelayMillis = 680, maxMeldActions = 12)
    }

    fun chooseDrawSource(
        hand: List<Card>,
        tableMelds: List<List<Card>>,
        discardPile: List<Card>,
        config: MatchConfig,
        cachetaTurnCard: Card?
    ): DrawSource {
        val topDiscard = discardPile.lastOrNull() ?: return DrawSource.DECK
        if (!GameRulesEngine.canDrawFromDiscard(topDiscard, config).allowed) return DrawSource.DECK

        val forcedMove = chooseMeldMove(
            hand = hand + topDiscard,
            tableMelds = tableMelds,
            config = config,
            cachetaTurnCard = cachetaTurnCard,
            requiredCardId = topDiscard.id
        )

        if (config.gameType == GameType.CACHETA) {
            val connection = connectionScore(topDiscard, hand)
            return when (config.botDifficulty) {
                BotDifficulty.EASY -> if (forcedMove != null) DrawSource.DISCARD else DrawSource.DECK
                BotDifficulty.NORMAL -> if (forcedMove != null || connection >= 32) DrawSource.DISCARD else DrawSource.DECK
                BotDifficulty.HARD -> if (forcedMove != null || connection >= 20) DrawSource.DISCARD else DrawSource.DECK
            }
        }

        val canJustify = GameRulesEngine.canJustifyDiscardDraw(
            topDiscard = topDiscard,
            hand = hand,
            tableMelds = tableMelds,
            config = config,
            cachetaTurnCard = cachetaTurnCard
        )
        if (!canJustify || forcedMove == null) return DrawSource.DECK

        val restOfPile = discardPile.dropLast(1)
        val usefulCards = restOfPile.count { connectionScore(it, hand + topDiscard) >= 20 }
        val pileBurden = restOfPile.size - usefulCards
        val completesCanastra = forcedMove.resultingMeld.size >= 7

        return when (config.botDifficulty) {
            BotDifficulty.EASY -> {
                // O nivel facil perde oportunidades mais caras, mas reconhece uma
                // compra curta e imediatamente utilizavel para nao jogar no escuro.
                if (discardPile.size <= 2) DrawSource.DISCARD else DrawSource.DECK
            }
            BotDifficulty.NORMAL -> {
                if (completesCanastra || pileBurden <= 7) DrawSource.DISCARD else DrawSource.DECK
            }
            BotDifficulty.HARD -> {
                val strategicGain = forcedMove.score + usefulCards * 24 + if (hand.size <= 4) 70 else 0
                val burdenCost = pileBurden * 14
                if (completesCanastra || strategicGain >= burdenCost) DrawSource.DISCARD else DrawSource.DECK
            }
        }
    }

    fun chooseMeldMove(
        hand: List<Card>,
        tableMelds: List<List<Card>>,
        config: MatchConfig,
        cachetaTurnCard: Card?,
        requiredCardId: String? = null
    ): MeldMove? {
        if (hand.isEmpty()) return null

        val moves = mutableListOf<MeldMove>()

        tableMelds.forEachIndexed { index, meld ->
            hand.forEach cardLoop@{ card ->
                if (requiredCardId != null && card.id != requiredCardId) return@cardLoop
                val combined = meld + card
                val result = GameRulesEngine.validateMeld(combined, config, cachetaTurnCard)
                if (result.isValid) {
                    moves += MeldMove(
                        cardsFromHand = listOf(card),
                        resultingMeld = GameRulesEngine.sortMeld(combined, config, cachetaTurnCard),
                        replaceIndex = index,
                        score = meldScore(combined, listOf(card), config, cachetaTurnCard)
                    )
                }
            }
        }

        if (hand.size >= 3) {
            hand.combinationsOfThree().forEach seedLoop@{ seed ->
                if (requiredCardId != null && seed.none { it.id == requiredCardId }) return@seedLoop
                if (!GameRulesEngine.validateMeld(seed, config, cachetaTurnCard).isValid) return@seedLoop

                val grown = growMeld(seed, hand, config, cachetaTurnCard)
                moves += MeldMove(
                    cardsFromHand = grown,
                    resultingMeld = GameRulesEngine.sortMeld(grown, config, cachetaTurnCard),
                    score = meldScore(grown, grown, config, cachetaTurnCard)
                )
            }
        }

        val uniqueMoves = moves.distinctBy { move ->
            "${move.replaceIndex}:${move.cardsFromHand.map { it.id }.sorted().joinToString("|")}"
        }
        if (uniqueMoves.isEmpty()) return null

        val ordered = uniqueMoves.sortedWith(
            compareByDescending<MeldMove> { it.score }
                .thenByDescending { it.cardsFromHand.size }
                .thenBy { it.cardsFromHand.joinToString("|") { card -> card.id } }
        )

        return when (config.botDifficulty) {
            // O facil pode escolher a segunda opcao, desde que ela nao seja muito
            // pior que a principal. Assim ele erra por estrategia, nao por regra.
            BotDifficulty.EASY -> ordered.getOrNull(1)
                ?.takeIf { alternative -> alternative.score * 2 >= ordered.first().score }
                ?: ordered.first()
            BotDifficulty.NORMAL, BotDifficulty.HARD -> ordered.first()
        }
    }

    /**
     * Evita que a maquina baixe a ultima carta e fique sem uma continuacao valida.
     * Esvaziar a mao so e seguro quando ela vai pegar o morto ou ja pode bater.
     */
    fun keepsLegalTurnFlow(
        hand: List<Card>,
        tableMelds: List<List<Card>>,
        move: MeldMove,
        cardsReleasedAfterMove: List<Card>,
        hasPickedMorto: Boolean,
        mortosLeft: Int,
        config: MatchConfig
    ): Boolean {
        val remainingHand = move.cardsFromHand.fold(hand) { current, card ->
            current.filterNotOnce(card)
        } + cardsReleasedAfterMove
        if (remainingHand.isNotEmpty() || config.gameType == GameType.CACHETA) return true
        if (!hasPickedMorto && mortosLeft > 0) return true

        val resultingTable = if (move.replaceIndex in tableMelds.indices) {
            tableMelds.mapIndexed { index, meld ->
                if (index == move.replaceIndex) move.resultingMeld else meld
            }
        } else {
            tableMelds + listOf(move.resultingMeld)
        }
        return GameRulesEngine.canDeclareWin(
            hand = emptyList(),
            tableMelds = resultingTable,
            hasMorto = !hasPickedMorto,
            config = config
        ).canWin
    }

    fun chooseDiscard(
        hand: List<Card>,
        tableMelds: List<List<Card>>,
        opponentTableMelds: List<List<Card>>,
        config: MatchConfig,
        cachetaTurnCard: Card?
    ): Card? {
        if (hand.isEmpty()) return null

        val withoutAutomaticRedThrees = hand.filterNot { card ->
            config.gameType == GameType.TRANCA &&
                card.rank == Rank.THREE &&
                (card.suit == Suit.HEARTS || card.suit == Suit.DIAMONDS)
        }.ifEmpty { hand }

        val safeCandidates = withoutAutomaticRedThrees.filterNot { card ->
            val isWildcard = isMeldWildcard(card, config, cachetaTurnCard)
            if (!isWildcard || withoutAutomaticRedThrees.size == 1) return@filterNot false

            val remaining = withoutAutomaticRedThrees.filterNotOnce(card)
            val hasOrdinaryDiscard = remaining.any { !isMeldWildcard(it, config, cachetaTurnCard) }
            hasOrdinaryDiscard && GameRulesEngine.canJustifyDiscardDraw(
                topDiscard = card,
                hand = remaining,
                tableMelds = tableMelds,
                config = config,
                cachetaTurnCard = cachetaTurnCard
            )
        }.ifEmpty { withoutAutomaticRedThrees }

        return safeCandidates.minWithOrNull(
            compareBy<Card> { card ->
                keepScore(card, hand, tableMelds, opponentTableMelds, config, cachetaTurnCard)
            }.thenByDescending { cardPoints(it) }
                .thenBy { it.id }
        )
    }

    private fun growMeld(
        seed: List<Card>,
        hand: List<Card>,
        config: MatchConfig,
        cachetaTurnCard: Card?
    ): List<Card> {
        var current = seed
        var remaining = hand.filterNotIds(seed)
        while (remaining.isNotEmpty()) {
            val validCandidates = remaining
                .filter { GameRulesEngine.validateMeld(current + it, config, cachetaTurnCard).isValid }
            if (validCandidates.isEmpty()) break

            val naturalCandidates = validCandidates.filterNot {
                isMeldWildcard(it, config, cachetaTurnCard)
            }
            val candidates = if (config.botDifficulty == BotDifficulty.EASY || naturalCandidates.isEmpty()) {
                validCandidates
            } else {
                naturalCandidates
            }
            val best = candidates
                .maxByOrNull { card ->
                    cardPoints(card) + connectionScore(card, remaining.filterNotOnce(card))
                }
                ?: break

            val spendsFirstWildcard = isMeldWildcard(best, config, cachetaTurnCard) &&
                current.none { isMeldWildcard(it, config, cachetaTurnCard) }
            val strategicWildcardUse = current.size == 6 || remaining.size == 1
            if (spendsFirstWildcard && config.botDifficulty != BotDifficulty.EASY && !strategicWildcardUse) {
                break
            }
            current += best
            remaining = remaining.filterNotOnce(best)
        }
        return current
    }

    private fun meldScore(
        resultingMeld: List<Card>,
        cardsFromHand: List<Card>,
        config: MatchConfig,
        cachetaTurnCard: Card?
    ): Int {
        val validation = GameRulesEngine.validateMeld(resultingMeld, config, cachetaTurnCard)
        val canastraBonus = when (validation.meldType) {
            GameRulesEngine.MeldType.CANASTRA_LIMPA -> 240
            GameRulesEngine.MeldType.CANASTRA_SUJA -> 150
            else -> 0
        }
        val wildcardWeight = when (config.botDifficulty) {
            BotDifficulty.EASY -> 12
            BotDifficulty.NORMAL -> 36
            BotDifficulty.HARD -> 70
        }
        val wildcardCost = cardsFromHand.count { isMeldWildcard(it, config, cachetaTurnCard) } * wildcardWeight
        return cardsFromHand.sumOf(::cardPoints) + cardsFromHand.size * 24 + canastraBonus - wildcardCost
    }

    private fun keepScore(
        card: Card,
        hand: List<Card>,
        tableMelds: List<List<Card>>,
        opponentTableMelds: List<List<Card>>,
        config: MatchConfig,
        cachetaTurnCard: Card?
    ): Int {
        val remaining = hand.filterNotOnce(card)
        var score = connectionScore(card, remaining)

        if (tableMelds.any { GameRulesEngine.validateMeld(it + card, config, cachetaTurnCard).isValid }) {
            score += when (config.botDifficulty) {
                BotDifficulty.EASY -> 34
                BotDifficulty.NORMAL -> 64
                BotDifficulty.HARD -> 92
            }
        }
        if (opponentTableMelds.any { GameRulesEngine.validateMeld(it + card, config, cachetaTurnCard).isValid }) {
            score += when (config.botDifficulty) {
                BotDifficulty.EASY -> 28
                BotDifficulty.NORMAL -> 68
                BotDifficulty.HARD -> 118
            }
        }
        if (isMeldWildcard(card, config, cachetaTurnCard)) score += 95

        // Na Tranca o 3 preto e um descarte defensivo: livra penalidade e fecha o lixo.
        if (config.gameType == GameType.TRANCA && card.rank == Rank.THREE &&
            (card.suit == Suit.CLUBS || card.suit == Suit.SPADES)
        ) {
            score -= when (config.botDifficulty) {
                BotDifficulty.EASY -> 30
                BotDifficulty.NORMAL -> 70
                BotDifficulty.HARD -> 120
            }
        }

        val deadwoodWeight = when (config.botDifficulty) {
            BotDifficulty.EASY -> 1
            BotDifficulty.NORMAL -> 2
            BotDifficulty.HARD -> 3
        }
        return score - cardPoints(card) * deadwoodWeight
    }

    private fun connectionScore(card: Card, hand: List<Card>): Int {
        val sameRank = hand.count { it.rank == card.rank }
        val sameSuitNear = hand.count {
            it.suit == card.suit && abs(it.rank.value - card.rank.value) in 1..2
        }
        val adjacent = hand.count {
            it.suit == card.suit && abs(it.rank.value - card.rank.value) == 1
        }
        return sameRank * 24 + sameSuitNear * 12 + adjacent * 10
    }

    private fun isMeldWildcard(card: Card, config: MatchConfig, turnCard: Card?): Boolean {
        return when (config.gameType) {
            GameType.CACHETA -> {
                config.allowWildcards && (card.isJoker || (turnCard != null &&
                    card.rank == GameRulesEngine.getCachetaWildcardRank(turnCard) &&
                    card.suit == turnCard.suit))
            }
            GameType.BURACO, GameType.TRANCA -> card.rank == Rank.TWO || (config.allowWildcards && card.isJoker)
        }
    }

    private fun cardPoints(card: Card): Int = when {
        card.isJoker -> 20
        card.rank == Rank.TWO -> 10
        card.rank == Rank.ACE -> 15
        card.rank.value >= Rank.EIGHT.value -> 10
        else -> 5
    }

    private fun List<Card>.combinationsOfThree(): Sequence<List<Card>> = sequence {
        for (first in 0 until size - 2) {
            for (second in first + 1 until size - 1) {
                for (third in second + 1 until size) {
                    yield(listOf(this@combinationsOfThree[first], this@combinationsOfThree[second], this@combinationsOfThree[third]))
                }
            }
        }
    }

    private fun List<Card>.filterNotIds(cards: List<Card>): List<Card> {
        val ids = cards.mapTo(mutableSetOf()) { it.id }
        return filterNot { it.id in ids }
    }

    private fun List<Card>.filterNotOnce(card: Card): List<Card> {
        var removed = false
        return filter {
            if (!removed && it.id == card.id) {
                removed = true
                false
            } else {
                true
            }
        }
    }
}

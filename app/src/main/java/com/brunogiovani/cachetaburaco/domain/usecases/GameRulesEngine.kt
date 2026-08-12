package com.brunogiovani.cachetaburaco.domain.usecases

import com.brunogiovani.cachetaburaco.domain.models.Card
import com.brunogiovani.cachetaburaco.domain.models.GameType
import com.brunogiovani.cachetaburaco.domain.models.MatchConfig
import com.brunogiovani.cachetaburaco.domain.models.Rank
import com.brunogiovani.cachetaburaco.domain.models.Suit

/**
 * Fonte única das regras de Cacheta, Buraco e Tranca.
 *
 * Mantive este motor sem Compose, Context ou rede para reaproveitar a mesma
 * validação no jogo local, contra a máquina e no online depois.
 */
object GameRulesEngine {

    // Toda variação configurável passa pelo MatchConfig. Se uma regra de mesa
    // mudar, a alteração entra aqui antes de chegar na UI.

    /** Na Cacheta todo jogo fecha em 3 cartas e nao cresce depois de baixado. */
    const val CACHETA_MELD_SIZE = 3

    // Compra do lixo.

    data class DrawDiscardResult(
        val allowed: Boolean,
        val reason: String = ""
    )

    /**
     * Verifica se é permitido comprar a carta do topo do lixo.
     */
    fun canDrawFromDiscard(
        topDiscard: Card?,
        config: MatchConfig
    ): DrawDiscardResult {
        // Esta função responde apenas "pode comprar o topo do lixo?".
        // A obrigação de usar essa carta em Buraco/Tranca fica em outro passo.
        if (topDiscard == null) return DrawDiscardResult(false, "Lixo vazio")
        if (!config.allowDrawFromDiscard) return DrawDiscardResult(false, "Compra do lixo desabilitada nesta sala")

        return when (config.gameType) {
            GameType.CACHETA -> DrawDiscardResult(true)

            GameType.BURACO -> {
                // No Buraco, o 3 não bloqueia. Apenas o 3 preto na Tranca.
                if (topDiscard.isJoker) {
                    DrawDiscardResult(false, "Não é possível comprar Curinga do lixo")
                } else {
                    DrawDiscardResult(true)
                }
            }

            GameType.TRANCA -> {
                // 3 Preto (♠ ou ♣) TRANCA o lixo — ninguém pode comprar
                if (topDiscard.rank == Rank.THREE &&
                    (topDiscard.suit == Suit.SPADES || topDiscard.suit == Suit.CLUBS)) {
                    DrawDiscardResult(false, "🔒 Lixo trancado pelo 3 preto!")
                } else if (topDiscard.isJoker) {
                    DrawDiscardResult(false, "Não é possível comprar Curinga do lixo")
                } else {
                    DrawDiscardResult(true)
                }
            }
        }
    }

    // --- Validação de Baixar Cartas ------------------------------------------

    data class MeldValidationResult(
        val isValid: Boolean,
        val meldType: MeldType = MeldType.INVALID,
        val reason: String = ""
    )

    enum class MeldType { TRINCA, SEQUENCIA, CANASTRA_LIMPA, CANASTRA_SUJA, INVALID }

    /**
     * Valida se um conjunto de cartas forma um jogo válido para o modo atual.
     */
    fun validateMeld(
        cards: List<Card>,
        config: MatchConfig,
        cachetaTurnCard: Card? = null
    ): MeldValidationResult {
        if (cards.size < 3) return MeldValidationResult(false, reason = "Mínimo 3 cartas")
        if (!config.allowWildcards && cards.any(Card::isJoker)) {
            return MeldValidationResult(false, reason = "Curingas estão desabilitados nesta sala")
        }

        // Toda baixada/encaixe passa por aqui. Assim a tela, o bot e a rede
        // obedecem a mesma regra, sem cada um inventar sua própria validação.
        val wildcards = getMeldWildcards(cards, config, cachetaTurnCard)
        val normalCards = cards.filter { it !in wildcards }

        return when (config.gameType) {
            GameType.CACHETA -> validateCachetaMeld(normalCards, wildcards, config)
            GameType.BURACO -> validateBuracoMeld(normalCards, wildcards, config)
            GameType.TRANCA -> validateTrancaMeld(normalCards, wildcards, config)
        }
    }

    private fun validateCachetaMeld(
        normalCards: List<Card>,
        wildcards: List<Card>,
        config: MatchConfig
    ): MeldValidationResult {
        if (wildcards.size > 1) {
            return MeldValidationResult(false, reason = "Na Cacheta, cada jogo aceita apenas um coringa")
        }

        // Na Cacheta todo jogo tem exatamente 3 cartas -- a mao de 9 vira 3
        // jogos de 3, e jogo baixado nao cresce. Esta checagem sozinha cobre
        // as duas regras: baixar 4+ e recusado aqui, e "encaixar" numa trinca
        // ou sequencia ja baixada tambem, porque o encaixe revalida o jogo
        // inteiro (meld + carta nova) e cairia em 4 cartas.
        // Sem ela a sequencia passava batido: checkSequencia exige minimo 3,
        // mas nao tinha maximo nenhum.
        if (normalCards.size + wildcards.size != CACHETA_MELD_SIZE) {
            return MeldValidationResult(
                false,
                reason = "Na Cacheta, cada jogo tem exatamente $CACHETA_MELD_SIZE cartas"
            )
        }

        // Cacheta aceita trinca exata de 3 cartas ou sequência do mesmo naipe.
        val trincaResult = checkTrinca(normalCards, wildcards, exactSize = CACHETA_MELD_SIZE)
        if (trincaResult.isValid) {
            val allSuitsUnique = normalCards.distinctBy { it.suit }.size == normalCards.size
            if (allSuitsUnique) return trincaResult
        }

        val seqResult = checkSequencia(normalCards, wildcards)
        if (seqResult.isValid) return seqResult

        return MeldValidationResult(false, reason = "Precisa ser Trinca de naipes diferentes ou Sequência do mesmo naipe")
    }

    private fun validateBuracoMeld(
        normalCards: List<Card>,
        wildcards: List<Card>,
        config: MatchConfig
    ): MeldValidationResult {
        if (wildcards.size > 1) {
            return MeldValidationResult(false, reason = "Cada jogo aceita apenas um curinga")
        }

        // Buraco base aceita sequência do mesmo naipe. Charuto/trinca é opcional na sala.
        val seqResult = checkSequencia(normalCards, wildcards)
        if (seqResult.isValid) {
            return classifyCanastra(seqResult, normalCards, wildcards)
        }

        if (config.allowCharutos) {
            val trincaResult = checkTrinca(normalCards, wildcards)
            if (trincaResult.isValid) return classifyCanastra(trincaResult, normalCards, wildcards)
        }

        return MeldValidationResult(
            false,
            reason = if (config.allowCharutos) {
                "Precisa ser Sequência ou Charuto válido"
            } else {
                "Nesta sala de Buraco só valem sequências do mesmo naipe"
            }
        )
    }

    private fun validateTrancaMeld(
        normalCards: List<Card>,
        wildcards: List<Card>,
        config: MatchConfig
    ): MeldValidationResult {
        if (wildcards.size > 1) {
            return MeldValidationResult(false, reason = "Cada jogo aceita apenas um curinga")
        }

        if (normalCards.any { it.rank == Rank.THREE }) {
            return MeldValidationResult(false, reason = "Na Tranca, 3 não entra em jogo comum")
        }

        // Tranca aceita sequência e, quando habilitado na sala, charuto/trinca.
        val seqResult = checkSequencia(normalCards, wildcards)
        if (seqResult.isValid) return classifyCanastra(seqResult, normalCards, wildcards)

        if (config.allowCharutos) {
            val trincaResult = checkTrinca(normalCards, wildcards)
            if (trincaResult.isValid) return classifyCanastra(trincaResult, normalCards, wildcards)
        }

        return MeldValidationResult(
            false,
            reason = if (config.allowCharutos) {
                "Precisa ser Sequência ou Charuto válido"
            } else {
                "Nesta sala de Tranca só valem sequências do mesmo naipe"
            }
        )
    }

    // ─── Checagens Internas ──────────────────────────────────────────────────

    private fun checkTrinca(
        normalCards: List<Card>,
        wildcards: List<Card>,
        exactSize: Int? = null
    ): MeldValidationResult {
        if (normalCards.isEmpty()) return MeldValidationResult(false)

        val allSameRank = normalCards.distinctBy { it.rank }.size == 1
        if (!allSameRank) return MeldValidationResult(false)

        val totalCards = normalCards.size + wildcards.size
        if (exactSize != null && totalCards != exactSize) {
            return MeldValidationResult(false, reason = "Trinca precisa ter exatamente $exactSize cartas")
        }

        if (totalCards >= 3) {
            return MeldValidationResult(true, MeldType.TRINCA)
        }

        return MeldValidationResult(false, reason = "Trinca inválida")
    }

    private fun checkSequencia(normalCards: List<Card>, wildcards: List<Card>): MeldValidationResult {
        if (normalCards.isEmpty() && wildcards.isNotEmpty()) return MeldValidationResult(false)
        if (normalCards.distinctBy { it.suit }.size > 1) {
            return MeldValidationResult(false, reason = "Sequência precisa ser do mesmo naipe")
        }

        val totalCards = normalCards.size + wildcards.size
        if (totalCards < 3) return MeldValidationResult(false)

        val lowAceRanks = normalCards.map { it.rank.value }.sorted()
        if (canBuildSequence(lowAceRanks, wildcards.size)) {
            return MeldValidationResult(true, MeldType.SEQUENCIA)
        }

        val highAceRanks = normalCards.map { if (it.rank == Rank.ACE) 14 else it.rank.value }.sorted()
        if (canBuildSequence(highAceRanks, wildcards.size)) {
            return MeldValidationResult(true, MeldType.SEQUENCIA)
        }

        return MeldValidationResult(false, reason = "Curingas insuficientes para a sequência")
    }

    private fun canBuildSequence(sortedRanks: List<Int>, wildcardCount: Int): Boolean {
        var wildcardsNeeded = 0
        for (i in 0 until sortedRanks.size - 1) {
            val gap = sortedRanks[i + 1] - sortedRanks[i] - 1
            if (gap < 0) return false
            wildcardsNeeded += gap
        }

        return wildcardsNeeded <= wildcardCount
    }

    private fun classifyCanastra(
        baseResult: MeldValidationResult,
        normalCards: List<Card>,
        wildcards: List<Card>
    ): MeldValidationResult {
        val total = normalCards.size + wildcards.size
        if (total < 7) return baseResult

        return if (wildcards.isEmpty()) {
            MeldValidationResult(true, MeldType.CANASTRA_LIMPA)
        } else {
            MeldValidationResult(true, MeldType.CANASTRA_SUJA)
        }
    }

    // --- Condição de Vitória -------------------------------------------------

    data class WinCheckResult(val canWin: Boolean, val reason: String = "")

    fun canDeclareWin(
        hand: List<Card>,
        tableMelds: List<List<Card>>,
        hasMorto: Boolean,  // true = ainda não pegou o morto
        config: MatchConfig
    ): WinCheckResult {
        return when (config.gameType) {
            GameType.CACHETA -> {
                // Na Cacheta, basta ter a mão vazia ao descartar
                if (hand.isEmpty()) WinCheckResult(true, "Bata!")
                else WinCheckResult(false)
            }

            GameType.BURACO -> {
                // Buraco: a exigência de Canastra Limpa depende da configuração da sala
                val hasCanastra = tableMelds.any { it.size >= 7 }
                val hasCleanCanastra = tableMelds.any { meld ->
                    meld.size >= 7 && getWildcards(meld, GameType.BURACO).isEmpty()
                }
                when {
                    hasMorto -> WinCheckResult(false, "Você ainda não pegou o Morto")
                    !hasCanastra -> WinCheckResult(false, "Você precisa de pelo menos uma Canastra para bater")
                    config.requireCleanCanastraToWin && !hasCleanCanastra -> WinCheckResult(false, "Nesta sala, você precisa de uma Canastra Limpa para bater no Buraco")
                    hand.isEmpty() -> WinCheckResult(true, "Bata!")
                    else -> WinCheckResult(false)
                }
            }

            GameType.TRANCA -> {
                // Tranca: aceita qualquer Canastra (limpa ou suja, 7+ cartas), mas pode exigir limpa pela config
                val hasCanastra = tableMelds.any { it.size >= 7 }
                val hasCleanCanastra = tableMelds.any { meld ->
                    meld.size >= 7 && getWildcards(meld, GameType.TRANCA).isEmpty()
                }
                when {
                    hasMorto -> WinCheckResult(false, "Você ainda não pegou o Morto")
                    !hasCanastra -> WinCheckResult(false, "Você precisa de pelo menos uma Canastra para bater")
                    config.requireCleanCanastraToWin && !hasCleanCanastra -> WinCheckResult(false, "Nesta sala, você precisa de uma Canastra Limpa para bater")
                    hand.isEmpty() -> WinCheckResult(true, "Bata!")
                    else -> WinCheckResult(false)
                }
            }
        }
    }

    // --- Ordenação da Mão e Mesa ---------------------------------------------

    /**
     * Ordena um jogo baixado (meld) colocando curingas nas posições corretas (buracos) na sequência.
     */
    fun sortMeld(
        meld: List<Card>,
        config: MatchConfig,
        cachetaTurnCard: Card? = null
    ): List<Card> {
        val wildcards = getMeldWildcards(meld, config, cachetaTurnCard).toMutableList()
        val normalCards = meld.filter { it !in wildcards }

        if (normalCards.isEmpty()) return meld

        // Trinca (mesmo rank): normais + curingas ao final
        if (normalCards.distinctBy { it.rank }.size == 1) {
            return normalCards + wildcards
        }

        // Sequência: determina se Ás é baixo ou alto
        val lowAceRanks  = normalCards.map { it.rank.value }.sorted()
        val highAceRanks = normalCards.map { if (it.rank == Rank.ACE) 14 else it.rank.value }.sorted()
        val canBuildLow  = canBuildSequence(lowAceRanks, wildcards.size)
        val canBuildHigh = canBuildSequence(highAceRanks, wildcards.size)
        val useHighAce   = canBuildHigh && !canBuildLow

        val sortedNormal = normalCards.sortedBy {
            if (useHighAce && it.rank == Rank.ACE) 14 else it.rank.value
        }
        val rankValues = sortedNormal.map {
            if (useHighAce && it.rank == Rank.ACE) 14 else it.rank.value
        }

        // --- Algoritmo de preenchimento de buracos ---
        // 1. Constrói a sequência preenchendo gaps com curingas disponíveis
        val result = mutableListOf<Card>()
        val availableWilds = wildcards.toMutableList()

        // Decide se há um buraco antes do primeiro card (curinga como extensão à esquerda)
        // Para simplificar, só preenche buracos *internos* por padrão
        result.add(sortedNormal.first())

        for (i in 0 until sortedNormal.size - 1) {
            val curr = rankValues[i]
            val next = rankValues[i + 1]
            val gap  = next - curr - 1

            // Preenche gap interno com curingas
            repeat(gap) {
                if (availableWilds.isNotEmpty()) result.add(availableWilds.removeAt(0))
            }
            result.add(sortedNormal[i + 1])
        }

        // 2. Curingas sobrando: verifica se cabem como extensão ao final (rank < K) ou início (rank > A)
        val minRank = rankValues.first()
        val maxRank = rankValues.last()

        val extraAtStart = mutableListOf<Card>()
        val extraAtEnd   = mutableListOf<Card>()

        var maxSpacesLeft = minRank - 1

        for (w in availableWilds) {
            when {
                maxSpacesLeft > 0 -> {
                    extraAtStart.add(w)
                    maxSpacesLeft--
                }
                maxRank < 13 -> extraAtEnd.add(w)
                else -> extraAtEnd.add(w)
            }
        }

        return extraAtStart + result + extraAtEnd
    }


    /**
     * Ordena a mão para facilitar a visão do jogador.
     * Agrupa por naipe e ordena por valor dentro de cada naipe.
     * Curingas ficam ao final.
     *
     * Posição do Ás dentro do naipe: por padrão ele conta como carta de valor 1
     * (mais baixa), pois é assim que ele participa de sequências baixas
     * (Ás-2-3-4...) nesses modos — ver [checkSequencia], que testa tanto a
     * leitura de Ás baixo quanto Ás alto.
     *
     * Na Tranca, porém, o 3 nunca entra em jogo comum (ver
     * [validateTrancaMeld]) e o 2 já é sempre curinga (ver [isWildcard]). Uma
     * sequência baixa com Ás nesse modo precisaria "pular" tanto o 2 quanto o
     * 3, ou seja, exigiria 2 curingas — acima do limite de 1 curinga por jogo
     * já validado em [validateTrancaMeld]. Isso torna a sequência baixa
     * inalcançável na prática, então na Tranca o Ás só participa de
     * sequências altas (Q-K-Ás) e deve ser ordenado como carta alta (depois
     * do Rei). Buraco e Cacheta não têm essa restrição extra sobre o 3 e
     * continuam permitindo Ás baixo normalmente (no Buraco, um curinga pode
     * inclusive preencher a lacuna do 2 numa sequência Ás-3-4-5...), então
     * mantêm o Ás como carta de valor 1.
     */
    fun sortHand(cards: List<Card>, gameType: GameType): List<Card> {
        val wildcards = cards.filter { isWildcard(it, gameType) }
        val normal = cards.filter { !isWildcard(it, gameType) }

        val rankValue: (Card) -> Int = { c ->
            if (gameType == GameType.TRANCA && c.rank == Rank.ACE) 14 else c.rank.value
        }

        val sorted = normal.sortedWith(
            compareBy({ it.suit.ordinal }, rankValue)
        )

        return sorted + wildcards
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    fun getWildcards(cards: List<Card>, gameType: GameType): List<Card> =
        cards.filter { isWildcard(it, gameType) }

    private fun getMeldWildcards(
        cards: List<Card>,
        config: MatchConfig,
        cachetaTurnCard: Card? = null
    ): List<Card> =
        when (config.gameType) {
            GameType.CACHETA -> {
                if (!config.allowWildcards) {
                    emptyList()
                } else {
                    val cachetaWildcard = cachetaTurnCard?.let { getCachetaWildcardRank(it) }
                    cards.filter { card ->
                        card.isJoker ||
                            (cachetaWildcard != null &&
                                card.rank == cachetaWildcard &&
                                card.suit == cachetaTurnCard.suit)
                    }
                }
            }

            GameType.BURACO, GameType.TRANCA -> {
                cards.filter { it.rank == Rank.TWO || (config.allowWildcards && it.isJoker) }
            }
        }

    fun isWildcard(card: Card, gameType: GameType): Boolean = when (gameType) {
        GameType.CACHETA -> card.isJoker
        GameType.BURACO, GameType.TRANCA -> card.isJoker || card.rank == Rank.TWO
    }

    fun getCachetaWildcardRank(turnCard: Card): Rank {
        val ranks = Rank.entries
        val nextIndex = (ranks.indexOf(turnCard.rank) + 1) % ranks.size
        return ranks[nextIndex]
    }

    fun isDiscardLocked(topDiscard: Card?, gameType: GameType): Boolean {
        if (topDiscard == null || gameType != GameType.TRANCA) return false
        return topDiscard.rank == Rank.THREE &&
                (topDiscard.suit == Suit.SPADES || topDiscard.suit == Suit.CLUBS)
    }

    /**
     * Identifica e remove 3 vermelhos (Copas/Ouros) da mão na Tranca,
     * retornando a mão limpa e a nova lista de jogos baixados.
     */
    fun handleThreeReds(
        hand: List<Card>,
        tableMelds: List<List<Card>>,
        gameType: GameType
    ): Pair<List<Card>, List<List<Card>>> {
        if (gameType != GameType.TRANCA) return Pair(hand, tableMelds)
        val threeReds = hand.filter {
            it.rank == Rank.THREE && (it.suit == Suit.HEARTS || it.suit == Suit.DIAMONDS)
        }
        if (threeReds.isEmpty()) return Pair(hand, tableMelds)

        val updatedHand = hand.filter { it !in threeReds }
        val updatedMelds = tableMelds + threeReds.map { listOf(it) }
        return Pair(updatedHand, updatedMelds)
    }

    /**
     * Verifica se o topo do lixo pode ser comprado justificando a compra:
     * - Pode encaixar em um jogo já baixado na mesa.
     * - Pode formar um novo jogo de pelo menos 3 cartas com cartas da mão.
     */
    fun canJustifyDiscardDraw(
        topDiscard: Card,
        hand: List<Card>,
        tableMelds: List<List<Card>>,
        config: MatchConfig,
        cachetaTurnCard: Card? = null
    ): Boolean {
        // 1. Verificar se encaixa em algum jogo já baixado na mesa
        for (meld in tableMelds) {
            val combined = meld + topDiscard
            if (validateMeld(combined, config, cachetaTurnCard).isValid) {
                return true
            }
        }

        // 2. Verificar se forma um novo jogo com 2 cartas da mão
        if (hand.size >= 2) {
            for (i in 0 until hand.size) {
                for (j in i + 1 until hand.size) {
                    val potentialMeld = listOf(hand[i], hand[j], topDiscard)
                    if (validateMeld(potentialMeld, config, cachetaTurnCard).isValid) {
                        return true
                    }
                }
            }
        }

        return false
    }

    /**
     * Detalhes dos pontos de um jogador/equipe em uma rodada.
     */
    data class ScoreBreakdown(
        val tablePoints: Int,
        val canastraPoints: Int,
        val handPenalty: Int,
        val winBonus: Int,
        val mortoPenalty: Int,
        val totalRoundPoints: Int,
        val tableCardCount: Int = 0,
        val handCardCount: Int = 0,
        val cleanCanastras: Int = 0,
        val dirtyCanastras: Int = 0,
        val redThreesOnTable: Int = 0,
        val redThreePoints: Int = 0,
        val redThreesInHand: Int = 0,
        val redThreeHandPenalty: Int = 0,
        val blackThreesInHand: Int = 0,
        val blackThreePenalty: Int = 0
    )

    fun calculateBuracoTrancaScore(
        hand: List<Card>,
        tableMelds: List<List<Card>>,
        hasMorto: Boolean, // true se não pegou o morto
        didWin: Boolean,
        gameType: GameType,
        uniformCardPoints: Boolean = false,
        penalizeBlackThreesInHand: Boolean = true
    ): ScoreBreakdown {
        var tablePoints = 0
        var canastraPoints = 0
        var handPenalty = 0
        var tableCardCount = 0
        var cleanCanastras = 0
        var dirtyCanastras = 0
        var redThreePoints = 0
        var redThreesInHand = 0
        var redThreeHandPenalty = 0
        var blackThreesInHand = 0
        var blackThreePenalty = 0
        val winBonus = if (didWin) 100 else 0
        val mortoPenalty = if (hasMorto) -100 else 0
        val hasCanastra = tableMelds.any { it.size >= 7 }
        var trancaRedThreesOnTable = 0

        // 1. Pontos das cartas na mesa
        for (meld in tableMelds) {
            // Verificar Canastras (7+ cartas)
            if (meld.size >= 7) {
                val wildcards = getWildcards(meld, gameType)
                if (wildcards.isEmpty()) {
                    cleanCanastras++
                    canastraPoints += 200 // Limpa
                } else {
                    dirtyCanastras++
                    canastraPoints += 100 // Suja
                }
            }

            // Somar valor das cartas individualmente
            for (card in meld) {
                if (gameType == GameType.TRANCA && isRedThree(card)) {
                    trancaRedThreesOnTable++
                } else {
                    tableCardCount++
                    tablePoints += getCardPointsValue(card, gameType, uniformCardPoints, penalizeBlackThreesInHand)
                }
            }
        }

        // 2. Penalidade das cartas na mão
        if (gameType == GameType.TRANCA && trancaRedThreesOnTable > 0) {
            redThreePoints = if (trancaRedThreesOnTable == 4) 800 else trancaRedThreesOnTable * 100
            tablePoints += if (hasCanastra) redThreePoints else -redThreePoints
            if (!hasCanastra) redThreePoints = -redThreePoints
        }

        for (card in hand) {
            val value = getCardPointsValue(card, gameType, uniformCardPoints, penalizeBlackThreesInHand)
            handPenalty += value
            if (gameType == GameType.TRANCA) {
                when {
                    isRedThree(card) -> {
                        redThreesInHand++
                        redThreeHandPenalty += value
                    }
                    isBlackThree(card) -> {
                        blackThreesInHand++
                        blackThreePenalty += value
                    }
                }
            }
        }

        val total = tablePoints + canastraPoints + winBonus + mortoPenalty - handPenalty
        return ScoreBreakdown(
            tablePoints = tablePoints,
            canastraPoints = canastraPoints,
            handPenalty = handPenalty,
            winBonus = winBonus,
            mortoPenalty = mortoPenalty,
            totalRoundPoints = total,
            tableCardCount = tableCardCount,
            handCardCount = hand.count { !(gameType == GameType.TRANCA && isRedThree(it)) },
            cleanCanastras = cleanCanastras,
            dirtyCanastras = dirtyCanastras,
            redThreesOnTable = trancaRedThreesOnTable,
            redThreePoints = redThreePoints,
            redThreesInHand = redThreesInHand,
            redThreeHandPenalty = redThreeHandPenalty,
            blackThreesInHand = blackThreesInHand,
            blackThreePenalty = blackThreePenalty
        )
    }

    private fun getCardPointsValue(
        card: Card,
        gameType: GameType,
        uniformCardPoints: Boolean = false,
        penalizeBlackThreesInHand: Boolean = true
    ): Int {
        if (card.isJoker) return if (uniformCardPoints) 10 else 20
        if (gameType == GameType.TRANCA) {
            if (card.rank == Rank.THREE && (card.suit == Suit.SPADES || card.suit == Suit.CLUBS)) {
                return if (penalizeBlackThreesInHand) 100 else if (uniformCardPoints) 10 else 5
            }
            if (card.rank == Rank.THREE && (card.suit == Suit.HEARTS || card.suit == Suit.DIAMONDS)) {
                return 100
            }
            if (uniformCardPoints) return 10
        }
        if (uniformCardPoints) return 10
        if (card.rank == Rank.TWO && gameType == GameType.BURACO) return 10
        return when (card.rank) {
            Rank.ACE -> 15
            Rank.EIGHT, Rank.NINE, Rank.TEN, Rank.JACK, Rank.QUEEN, Rank.KING -> 10
            Rank.THREE, Rank.FOUR, Rank.FIVE, Rank.SIX, Rank.SEVEN -> 5
            else -> 5
        }
    }

    private fun isRedThree(card: Card): Boolean {
        return card.rank == Rank.THREE && (card.suit == Suit.HEARTS || card.suit == Suit.DIAMONDS)
    }

    private fun isBlackThree(card: Card): Boolean {
        return card.rank == Rank.THREE && (card.suit == Suit.SPADES || card.suit == Suit.CLUBS)
    }
}

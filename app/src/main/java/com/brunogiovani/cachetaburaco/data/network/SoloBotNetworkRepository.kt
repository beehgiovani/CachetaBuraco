package com.brunogiovani.cachetaburaco.data.network

import com.brunogiovani.cachetaburaco.domain.models.Card
import com.brunogiovani.cachetaburaco.domain.models.BotDifficulty
import com.brunogiovani.cachetaburaco.domain.models.DeckFactory
import com.brunogiovani.cachetaburaco.domain.models.GameType
import com.brunogiovani.cachetaburaco.domain.models.MatchConfig
import com.brunogiovani.cachetaburaco.domain.models.Rank
import com.brunogiovani.cachetaburaco.domain.models.Suit
import com.brunogiovani.cachetaburaco.domain.repositories.ConnectionStatus
import com.brunogiovani.cachetaburaco.domain.repositories.DiscoveredRoom
import com.brunogiovani.cachetaburaco.domain.repositories.LocalNetworkRepository
import com.brunogiovani.cachetaburaco.domain.repositories.NetworkMessage
import com.brunogiovani.cachetaburaco.domain.usecases.GameRulesEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Modo contra a máquina usando o mesmo caminho de uma partida em rede.
 *
 * A máquina guarda apenas a própria mão, observa mesa/lixo/mortos públicos e envia
 * mensagens como se fosse um cliente normal. Assim o host continua mandando na partida
 * e a máquina não ganha acesso ao monte real nem à mão do jogador.
 *
 * Os níveis mudam a forma de decidir compra do lixo e descarte. A validação final
 * continua passando pelo MatchViewModel e pelo GameRulesEngine.
 */
class SoloBotNetworkRepository : LocalNetworkRepository {
    override val discoveredRooms: StateFlow<List<DiscoveredRoom>> = MutableStateFlow(emptyList())
    override val connectedClientsCount: StateFlow<Int> = MutableStateFlow(1)
    override val incomingMessages: SharedFlow<NetworkMessage> get() = incoming
    override val connectionStatus: StateFlow<ConnectionStatus> = MutableStateFlow(ConnectionStatus.CONNECTED)

    private val incoming = MutableSharedFlow<NetworkMessage>(extraBufferCapacity = 64)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val allCards = DeckFactory.createDecks(2, includeJokers = false).associateBy { it.id }

    private val botId = "machine-1"
    private var botSeat = 1
    private var activeSeat = 0
    private var currentConfig = MatchConfig(maxPlayers = 2)
    private var hand = emptyList<Card>()
    private var tableMelds = emptyList<List<Card>>()
    private var opponentTableMelds = emptyList<List<Card>>()
    private var discardPile = emptyList<Card>()
    private var turnCard: Card? = null
    private var deckSize = 0
    private var mortosLeft = 0
    private var actionScheduled = false

    override fun startHosting(playerName: String, port: Int, config: MatchConfig?) {
        currentConfig = (config ?: currentConfig).copy(maxPlayers = 2)
        (connectedClientsCount as MutableStateFlow).value = 1
        (connectionStatus as MutableStateFlow).value = ConnectionStatus.CONNECTED
    }

    override fun stopHosting() = Unit
    override fun startDiscovery() = Unit
    override fun stopDiscovery() = Unit
    override fun connectToRoom(host: String, port: Int) = Unit
    override fun reconnect(): Boolean = true
    override fun disconnect() {
        resetBotState()
    }

    override fun resetConnectionStatus() {
        (connectionStatus as MutableStateFlow).value = ConnectionStatus.CONNECTED
    }

    override fun sendMessage(message: NetworkMessage) {
        if (message.senderId == botId) return
        // Só entram aqui informações públicas da mesa. A mão do jogador continua privada.
        when (message.type) {
            "DISCARD" -> handleHostDiscard(message.payload)
            "DRAW_DISCARD" -> handleHostDrewDiscard()
            "MELD" -> handlePublicMeld(message.payload)
            "WIN_ROUND" -> replyWinRound()
            "COUNT_ROUND" -> replyCountRound()
            "PICK_MORTO" -> mortosLeft = runCatching {
                JSONObject(message.payload).optInt("mortosLeft", mortosLeft)
            }.getOrDefault(mortosLeft)
            "NEXT_ROUND" -> resetBotState(keepConfig = true)
        }
    }

    override fun sendMessageToClient(clientIndex: Int, message: NetworkMessage): Boolean {
        if (clientIndex != 0) return false
        if (message.type == "GAME_START") {
            handleGameStart(message.payload)
        }
        return true
    }

    override fun sendMessageToPlayer(playerId: String, message: NetworkMessage): Boolean {
        if (playerId != botId && playerId != "client-1") return false
        when (message.type) {
            "SERVE_CARD" -> handleServedCard(message.payload)
            "SERVE_MORTO" -> handleServedMorto(message.payload)
            "RECONNECT_STATE" -> handleGameStart(message.payload)
        }
        return true
    }

    private fun handleGameStart(payload: String) {
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return
        currentConfig = runCatching {
            MatchConfig.deserialize(json.optString("config", currentConfig.serialize())).copy(maxPlayers = 2)
        }.getOrElse { currentConfig.copy(maxPlayers = 2) }
        botSeat = json.optInt("seat", 1).coerceIn(1, 1)
        activeSeat = json.optInt("activeSeat", 1)
        hand = sort(cardsFromJson(json.optJSONArray("hand") ?: JSONArray()))
        tableMelds = emptyList()
        opponentTableMelds = emptyList()
        discardPile = json.optString("discard", "").takeIf { it.isNotBlank() }?.let { id ->
            allCards[id]?.let { listOf(it) }
        }.orEmpty()
        turnCard = allCards[json.optString("turnCard", "")]
        deckSize = json.optInt("deckSize", 0)
        mortosLeft = json.optInt("mortosLeft", 0)
        scheduleTurnIfNeeded()
    }

    private fun handleHostDiscard(payload: String) {
        val cardId = parseCardId(payload)
        val card = allCards[cardId] ?: return
        discardPile = discardPile + card
        activeSeat = botSeat
        scheduleTurnIfNeeded()
    }

    private fun handleHostDrewDiscard() {
        discardPile = if (currentConfig.gameType == GameType.CACHETA) {
            discardPile.dropLast(1)
        } else {
            emptyList()
        }
    }

    private fun handlePublicMeld(payload: String) {
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val seat = json.optInt("seat", -1)
        val cards = cardsFromJson(json.optJSONArray("cards") ?: JSONArray())
        if (cards.isEmpty()) return

        val replaceIndex = json.optInt("replaceIndex", -1)
        if (seat == botSeat) {
            tableMelds = replaceOrAppendMeld(tableMelds, replaceIndex, cards)
        } else {
            opponentTableMelds = replaceOrAppendMeld(opponentTableMelds, replaceIndex, cards)
        }
    }

    private fun handleServedCard(payload: String) {
        val card = allCards[payload] ?: return
        deckSize = (deckSize - 1).coerceAtLeast(0)
        hand = sort(hand + card)
        val redThree = currentConfig.gameType == GameType.TRANCA &&
            card.rank == Rank.THREE &&
            (card.suit == Suit.HEARTS || card.suit == Suit.DIAMONDS)
        if (redThree && currentConfig.autoMeldTrancaRedThrees) {
            meldCards(listOf(card))
            requestDeckAgain()
        } else {
            scheduleAction()
        }
    }

    private fun handleServedMorto(payload: String) {
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val morto = cardsFromJson(json.optJSONArray("hand") ?: JSONArray())
        if (morto.size != currentConfig.cardsPerPlayer) return
        hand = sort(morto)
        mortosLeft = json.optInt("mortosLeft", (mortosLeft - 1).coerceAtLeast(0))
        activeSeat = botSeat
        scheduleAction()
    }

    private fun scheduleTurnIfNeeded() {
        if (activeSeat != botSeat || actionScheduled) return
        actionScheduled = true
        scope.launch {
            delay(650)
            actionScheduled = false
            if (shouldDrawFromDiscard()) {
                val topDiscard = discardPile.lastOrNull()
                val drawnCards = if (currentConfig.gameType == GameType.CACHETA) {
                    topDiscard?.let { listOf(it) }.orEmpty()
                } else {
                    discardPile
                }
                if (topDiscard != null && drawnCards.isNotEmpty()) {
                    discardPile = if (currentConfig.gameType == GameType.CACHETA) {
                        discardPile.dropLast(1)
                    } else {
                        emptyList()
                    }
                    hand = sort(hand + drawnCards)
                    emitToHost("DRAW_DISCARD", topDiscard.id)
                    scheduleAction()
                } else {
                    emitToHost("REQ_DRAW_DECK", seatPayload())
                }
            } else {
                emitToHost("REQ_DRAW_DECK", seatPayload())
            }
        }
    }

    private fun scheduleAction() {
        if (actionScheduled) return
        actionScheduled = true
        scope.launch {
            delay(850)
            actionScheduled = false
            playAction()
        }
    }

    private fun requestDeckAgain() {
        scope.launch {
            delay(500)
            emitToHost("REQ_DRAW_DECK", seatPayload())
        }
    }

    private fun playAction() {
        // A máquina joga pelo mesmo protocolo de um cliente real.
        // O nível muda a heurística: quando comprar lixo e qual carta descartar.
        autoDropRedThrees()
        while (true) {
            val extension = findBestTableExtension()
            if (extension != null) {
                meldCards(extension.cards, replaceIndex = extension.replaceIndex)
                continue
            }
            val meld = findBestMeld()
            if (meld != null) {
                meldCards(meld)
                continue
            }
            break
        }

        if (hand.isEmpty()) {
            finishOrAskMorto()
            return
        }

        val discard = chooseDiscard()
        hand = hand.filterNotOnce(discard)
        discardPile = discardPile + discard
        activeSeat = 0
        emitToHost("DISCARD", JSONObject().put("v", 1).put("card", discard.id).put("seat", botSeat).toString())

        if (hand.isEmpty()) {
            scope.launch {
                delay(150)
                finishOrAskMorto()
            }
        }
    }

    private fun autoDropRedThrees() {
        if (currentConfig.gameType != GameType.TRANCA || !currentConfig.autoMeldTrancaRedThrees) return
        val redThrees = hand.filter {
            it.rank == Rank.THREE && (it.suit == Suit.HEARTS || it.suit == Suit.DIAMONDS)
        }
        redThrees.forEach { meldCards(listOf(it)) }
    }

    private fun findBestMeld(): List<Card>? {
        if (hand.size < 3) return null
        val sorted = sort(hand)
        var best: List<Card>? = null
        var bestScore = Int.MIN_VALUE
        for (i in 0 until sorted.size - 2) {
            for (j in i + 1 until sorted.size - 1) {
                for (k in j + 1 until sorted.size) {
                    val candidate = listOf(sorted[i], sorted[j], sorted[k])
                    if (GameRulesEngine.validateMeld(candidate, currentConfig, turnCard).isValid) {
                        val score = candidate.sumOf { cardValue(it) } + meldGrowthBonus(candidate)
                        if (score > bestScore) {
                            best = candidate
                            bestScore = score
                        }
                    }
                }
            }
        }
        return best
    }

    private fun meldCards(cards: List<Card>, replaceIndex: Int = -1) {
        if (cards.isEmpty()) return
        hand = cards.fold(hand) { current, card -> current.filterNotOnce(card) }
        tableMelds = replaceOrAppendMeld(tableMelds, replaceIndex, cards)
        emitToHost(
            "MELD",
            JSONObject()
                .put("v", 1)
                .put("cards", JSONArray().apply { cards.forEach { put(it.id) } })
                .put("seat", botSeat)
                .put("team", botSeat.floorMod(2))
                .put("replaceIndex", replaceIndex)
                .toString()
        )
    }

    private fun finishOrAskMorto() {
        if (currentConfig.gameType != GameType.CACHETA && mortosLeft > 0) {
            emitToHost("REQ_PICK_MORTO", seatPayload())
        } else {
            emitToHost(
                "WIN_ROUND",
                JSONObject()
                    .put("v", 1)
                    .put("playerId", botId)
                    .put("seat", botSeat)
                    .put("winnerId", botId)
                    .put("hand", JSONArray())
                    .put("tableMelds", JSONArray().apply {
                        tableMelds.forEach { meld ->
                            put(JSONArray().apply { meld.forEach { put(it.id) } })
                        }
                    })
                    .toString()
            )
        }
    }

    private fun chooseDiscard(): Card {
        val candidates = hand.filterNot {
            currentConfig.gameType == GameType.TRANCA && it.rank == Rank.THREE && (it.suit == Suit.HEARTS || it.suit == Suit.DIAMONDS)
        }.ifEmpty { hand }

        return candidates.minByOrNull { discardRiskScore(it) } ?: hand.last()
    }

    private fun shouldDrawFromDiscard(): Boolean {
        val topDiscard = discardPile.lastOrNull() ?: return false
        val check = GameRulesEngine.canDrawFromDiscard(topDiscard, currentConfig)
        if (!check.allowed) return false

        if (currentConfig.gameType == GameType.CACHETA) {
            return currentConfig.botDifficulty != BotDifficulty.EASY || improvesHandPotential(topDiscard)
        }

        val canUse = GameRulesEngine.canJustifyDiscardDraw(
            topDiscard = topDiscard,
            hand = hand,
            tableMelds = tableMelds,
            config = currentConfig,
            cachetaTurnCard = turnCard
        )
        if (!canUse) return false

        return when (currentConfig.botDifficulty) {
            BotDifficulty.EASY -> formsNewMeld(topDiscard)
            BotDifficulty.NORMAL -> true
            BotDifficulty.HARD -> true
        }
    }

    private data class MeldExtension(
        val replaceIndex: Int,
        val cards: List<Card>,
        val score: Int
    )

    private fun findBestTableExtension(): MeldExtension? {
        var best: MeldExtension? = null
        var bestScore = Int.MIN_VALUE
        tableMelds.forEachIndexed { index, meld ->
            hand.forEach { card ->
                val combined = meld + card
                if (GameRulesEngine.validateMeld(combined, currentConfig, turnCard).isValid) {
                    val score = cardValue(card) + meldGrowthBonus(combined)
                    if (score > bestScore) {
                        best = MeldExtension(index, combined, score)
                        bestScore = score
                    }
                }
            }
        }
        return best
    }

    private fun formsNewMeld(topDiscard: Card): Boolean {
        if (hand.size < 2) return false
        for (i in hand.indices) {
            for (j in i + 1 until hand.size) {
                if (GameRulesEngine.validateMeld(listOf(hand[i], hand[j], topDiscard), currentConfig, turnCard).isValid) {
                    return true
                }
            }
        }
        return false
    }

    private fun improvesHandPotential(card: Card): Boolean {
        if (formsNewMeld(card)) return true
        return hand.any { other ->
            other.rank == card.rank ||
                (other.suit == card.suit && kotlin.math.abs(other.rank.value - card.rank.value) <= 2)
        }
    }

    private fun discardRiskScore(card: Card): Int {
        var score = cardValue(card)
        if (fitsOpponentTable(card)) {
            score += when (currentConfig.botDifficulty) {
                BotDifficulty.EASY -> 45
                BotDifficulty.NORMAL -> 65
                BotDifficulty.HARD -> 95
            }
        }
        if (hand.count { it.rank == card.rank } >= 2) {
            score += if (currentConfig.botDifficulty == BotDifficulty.EASY) 14 else 35
        }
        if (hand.any { it.suit == card.suit && kotlin.math.abs(it.rank.value - card.rank.value) == 1 }) {
            score += if (currentConfig.botDifficulty == BotDifficulty.EASY) 12 else 28
        }
        if (currentConfig.gameType == GameType.TRANCA && card.rank == Rank.THREE) score += 100
        if (card.rank == Rank.TWO || card.isJoker) score += 60
        return score
    }

    private fun fitsOpponentTable(card: Card): Boolean {
        return opponentTableMelds.any { meld ->
            GameRulesEngine.validateMeld(meld + card, currentConfig, turnCard).isValid
        }
    }

    private fun meldGrowthBonus(cards: List<Card>): Int {
        return when {
            cards.size >= 7 -> 120
            cards.size >= 5 -> 40
            else -> 0
        }
    }

    private fun cardValue(card: Card): Int {
        return when {
            card.isJoker -> 50
            card.rank == Rank.TWO -> 20
            card.rank == Rank.ACE -> 15
            card.rank.value >= Rank.EIGHT.value -> 10
            else -> 5
        }
    }

    private fun replyCountRound() {
        emitToHost(
            "REPLY_COUNT_ROUND",
            JSONObject()
                .put("v", 1)
                .put("playerId", botId)
                .put("seat", botSeat)
                .put("hand", JSONArray().apply { hand.forEach { put(it.id) } })
                .put("tableMelds", JSONArray().apply {
                    tableMelds.forEach { meld ->
                        put(JSONArray().apply { meld.forEach { put(it.id) } })
                    }
                })
                .toString()
        )
    }

    private fun replyWinRound() {
        emitToHost(
            "REPLY_WIN_ROUND",
            JSONObject()
                .put("v", 1)
                .put("playerId", botId)
                .put("seat", botSeat)
                .put("hand", JSONArray().apply { hand.forEach { put(it.id) } })
                .put("tableMelds", JSONArray().apply {
                    tableMelds.forEach { meld ->
                        put(JSONArray().apply { meld.forEach { put(it.id) } })
                    }
                })
                .toString()
        )
    }

    private fun emitToHost(type: String, payload: String) {
        incoming.tryEmit(NetworkMessage(botId, type, payload))
    }

    private fun cardsFromJson(array: JSONArray): List<Card> {
        return buildList {
            repeat(array.length()) { index ->
                allCards[array.optString(index)]?.let { add(it) }
            }
        }
    }

    private fun parseCardId(payload: String): String {
        val trimmed = payload.trim()
        if (!trimmed.startsWith("{")) return payload
        return runCatching { JSONObject(trimmed).optString("card", payload) }.getOrDefault(payload)
    }

    private fun seatPayload(): String = JSONObject().put("v", 1).put("seat", botSeat).toString()

    private fun sort(cards: List<Card>): List<Card> = GameRulesEngine.sortHand(cards, currentConfig.gameType)

    private fun replaceOrAppendMeld(
        current: List<List<Card>>,
        replaceIndex: Int,
        cards: List<Card>
    ): List<List<Card>> {
        if (replaceIndex !in current.indices) return current + listOf(cards)
        return current.mapIndexed { index, meld -> if (index == replaceIndex) cards else meld }
    }

    private fun resetBotState(keepConfig: Boolean = false) {
        if (!keepConfig) currentConfig = MatchConfig(maxPlayers = 2)
        hand = emptyList()
        tableMelds = emptyList()
        opponentTableMelds = emptyList()
        discardPile = emptyList()
        turnCard = null
        deckSize = 0
        mortosLeft = 0
        activeSeat = 0
        actionScheduled = false
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

    private fun Int.floorMod(mod: Int): Int = ((this % mod) + mod) % mod
}

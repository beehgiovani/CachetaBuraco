package com.brunogiovani.cachetaburaco.data.network

import com.brunogiovani.cachetaburaco.domain.models.Card
import com.brunogiovani.cachetaburaco.domain.models.DeckFactory
import com.brunogiovani.cachetaburaco.domain.models.GameType
import com.brunogiovani.cachetaburaco.domain.models.MatchConfig
import com.brunogiovani.cachetaburaco.domain.models.Rank
import com.brunogiovani.cachetaburaco.domain.models.Suit
import com.brunogiovani.cachetaburaco.domain.repositories.ConnectionStatus
import com.brunogiovani.cachetaburaco.domain.repositories.DiscoveredRoom
import com.brunogiovani.cachetaburaco.domain.repositories.LocalNetworkRepository
import com.brunogiovani.cachetaburaco.domain.repositories.NetworkMessage
import com.brunogiovani.cachetaburaco.domain.usecases.BotDecisionEngine
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
    private enum class BotTurnPhase {
        WAITING,
        DRAW,
        WAITING_CARD,
        ACTION,
        WAITING_MORTO,
        CLOSED
    }

    override val isBotRepository: Boolean = true
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
    private var hasPickedMorto = false
    private var actionScheduled = false
    private var roundClosed = false
    private var roundGeneration = 0
    private var scheduledTaskToken = 0
    private var turnPhase = BotTurnPhase.WAITING
    private var pendingDiscardTopId: String? = null
    private var pendingDiscardRest = emptyList<Card>()

    override fun startHosting(playerName: String, port: Int, config: MatchConfig?, password: String?) {
        currentConfig = (config ?: currentConfig).copy(maxPlayers = 2)
        (connectedClientsCount as MutableStateFlow).value = 1
        (connectionStatus as MutableStateFlow).value = ConnectionStatus.CONNECTED
    }

    override fun stopHosting() = Unit
    override fun startDiscovery() = Unit
    override fun stopDiscovery() = Unit
    override fun connectToRoom(host: String, port: Int, password: String?) = Unit
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
            "PUBLIC_STATE" -> handlePublicState(message.payload)
            "DISCARD" -> handleHostDiscard(message.payload)
            "DRAW_DISCARD" -> handleHostDrewDiscard()
            "MELD" -> handlePublicMeld(message.payload)
            "WIN_ROUND" -> {
                closeRoundForBot()
                replyWinRound()
            }
            "COUNT_ROUND" -> {
                closeRoundForBot()
                replyCountRound()
            }
            "RESTART_MATCH" -> if (message.payload != "CANCEL") replyRestartMatch()
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

    override fun sendMessageToSeat(seat: Int, message: NetworkMessage): Boolean {
        if (seat != botSeat) return false
        if (message.type == "GAME_START") handleGameStart(message.payload)
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
        roundGeneration++
        roundClosed = false
        actionScheduled = false
        pendingDiscardTopId = null
        pendingDiscardRest = emptyList()
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
        hasPickedMorto = false
        turnPhase = if (activeSeat == botSeat) BotTurnPhase.DRAW else BotTurnPhase.WAITING
        autoDropRedThrees()
        scheduleTurnIfNeeded()
    }

    /**
     * O host fecha cada acao com uma fotografia publica. Ela corrige qualquer atraso de
     * mensagens sem revelar cartas privadas e tambem limpa a mesa entre duas rodadas.
     */
    private fun handlePublicState(payload: String) {
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val team0Melds = handsFromJson(json.optJSONArray("team0Melds") ?: JSONArray())
        val team1Melds = handsFromJson(json.optJSONArray("team1Melds") ?: JSONArray())
        val botTeam = botSeat.floorMod(2)

        val previousActiveSeat = activeSeat
        activeSeat = json.optInt("activeSeat", activeSeat)
        deckSize = json.optInt("deckSize", deckSize).coerceAtLeast(0)
        mortosLeft = json.optInt("mortosLeft", mortosLeft).coerceAtLeast(0)
        discardPile = cardsFromJson(json.optJSONArray("discardPile") ?: JSONArray())
        turnCard = allCards[json.optString("turnCard", "")] ?: turnCard
        tableMelds = if (botTeam == 0) team0Melds else team1Melds
        opponentTableMelds = if (botTeam == 0) team1Melds else team0Melds
        if (activeSeat != botSeat) {
            turnPhase = BotTurnPhase.WAITING
            actionScheduled = false
            scheduledTaskToken++
        } else if (previousActiveSeat != botSeat && turnPhase == BotTurnPhase.WAITING) {
            turnPhase = BotTurnPhase.DRAW
        }
        scheduleTurnIfNeeded()
    }

    private fun handleHostDiscard(payload: String) {
        val cardId = parseCardId(payload)
        val card = allCards[cardId] ?: return
        discardPile = discardPile + card
        activeSeat = botSeat
        turnPhase = BotTurnPhase.DRAW
        actionScheduled = false
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
            turnPhase = BotTurnPhase.WAITING_CARD
            actionScheduled = false
            applyMeldMove(
                BotDecisionEngine.MeldMove(
                    cardsFromHand = listOf(card),
                    resultingMeld = listOf(card)
                ),
                notifyHost = false
            )
            requestDeckAgain()
        } else {
            turnPhase = BotTurnPhase.ACTION
            actionScheduled = false
            scheduleAction()
        }
    }

    private fun handleServedMorto(payload: String) {
        if (currentConfig.gameType == GameType.CACHETA) return
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val morto = cardsFromJson(json.optJSONArray("hand") ?: JSONArray())
        if (morto.size != currentConfig.cardsPerPlayer) return
        hand = sort(morto)
        hasPickedMorto = true
        mortosLeft = json.optInt("mortosLeft", (mortosLeft - 1).coerceAtLeast(0))
        val indirect = json.optBoolean("indirect", false)
        activeSeat = json.optInt("activeSeat", if (indirect) 0 else botSeat)
        actionScheduled = false
        turnPhase = if (!indirect && activeSeat == botSeat) {
            BotTurnPhase.ACTION
        } else {
            BotTurnPhase.WAITING
        }
        if (turnPhase == BotTurnPhase.ACTION) scheduleAction()
    }

    private fun scheduleTurnIfNeeded() {
        if (roundClosed || activeSeat != botSeat || turnPhase != BotTurnPhase.DRAW || actionScheduled) return
        actionScheduled = true
        val generation = roundGeneration
        val taskToken = ++scheduledTaskToken
        val timing = BotDecisionEngine.timing(currentConfig.botDifficulty)
        scope.launch {
            delay(timing.drawDelayMillis)
            if (generation != roundGeneration || taskToken != scheduledTaskToken) return@launch
            actionScheduled = false
            if (roundClosed || activeSeat != botSeat || turnPhase != BotTurnPhase.DRAW) return@launch
            val drawSource = BotDecisionEngine.chooseDrawSource(
                    hand = hand,
                    tableMelds = tableMelds,
                    discardPile = discardPile,
                    config = currentConfig,
                    cachetaTurnCard = turnCard
                )
            if (drawSource == BotDecisionEngine.DrawSource.DISCARD && canTakeDiscardWithoutBlockingTurn()) {
                val topDiscard = discardPile.lastOrNull()
                if (topDiscard != null) {
                    val restOfDiscard = discardPile.dropLast(1)
                    discardPile = if (currentConfig.gameType == GameType.CACHETA) {
                        emptyList()
                    } else {
                        restOfDiscard
                    }
                    hand = sort(hand + topDiscard)
                    turnPhase = BotTurnPhase.ACTION
                    pendingDiscardTopId = if (currentConfig.gameType == GameType.CACHETA) null else topDiscard.id
                    pendingDiscardRest = if (currentConfig.gameType == GameType.CACHETA) emptyList() else restOfDiscard
                    emitToHost("DRAW_DISCARD", topDiscard.id)
                    scheduleAction()
                } else {
                    turnPhase = BotTurnPhase.WAITING_CARD
                    emitToHost("REQ_DRAW_DECK", seatPayload())
                }
            } else {
                turnPhase = BotTurnPhase.WAITING_CARD
                emitToHost("REQ_DRAW_DECK", seatPayload())
            }
        }
    }

    private fun scheduleAction() {
        if (roundClosed || turnPhase != BotTurnPhase.ACTION || actionScheduled) return
        actionScheduled = true
        val generation = roundGeneration
        val taskToken = ++scheduledTaskToken
        val timing = BotDecisionEngine.timing(currentConfig.botDifficulty)
        scope.launch {
            delay(timing.actionDelayMillis)
            if (generation != roundGeneration || taskToken != scheduledTaskToken) return@launch
            actionScheduled = false
            if (roundClosed || turnPhase != BotTurnPhase.ACTION) return@launch
            playAction()
        }
    }

    private fun requestDeckAgain() {
        if (roundClosed || turnPhase != BotTurnPhase.WAITING_CARD || actionScheduled) return
        actionScheduled = true
        val generation = roundGeneration
        val taskToken = ++scheduledTaskToken
        scope.launch {
            delay(500)
            if (roundClosed || generation != roundGeneration || taskToken != scheduledTaskToken ||
                turnPhase != BotTurnPhase.WAITING_CARD
            ) {
                if (taskToken != scheduledTaskToken) return@launch
                actionScheduled = false
                return@launch
            }
            actionScheduled = false
            emitToHost("REQ_DRAW_DECK", seatPayload())
        }
    }

    private fun playAction() {
        if (roundClosed) return
        // A maquina usa o mesmo protocolo de um cliente, mas decide tudo por um
        // motor puro para eu conseguir testar cada nivel de forma isolada.
        autoDropRedThrees()

        val maxActions = BotDecisionEngine.timing(currentConfig.botDifficulty).maxMeldActions
        var actions = 0

        pendingDiscardTopId?.let { requiredCardId ->
            val forcedMove = BotDecisionEngine.chooseMeldMove(
                hand = hand,
                tableMelds = tableMelds,
                config = currentConfig,
                cachetaTurnCard = turnCard,
                requiredCardId = requiredCardId
            )
            if (forcedMove == null) {
                // Esta protecao nao deveria disparar porque a compra foi validada antes.
                // Se o estado mudou no caminho, a maquina nao tenta esconder a falha descartando.
                closeRoundForBot()
                return
            }
            applyMeldMove(forcedMove)
            actions++
        }

        while (actions < maxActions) {
            val move = BotDecisionEngine.chooseMeldMove(
                hand = hand,
                tableMelds = tableMelds,
                config = currentConfig,
                cachetaTurnCard = turnCard
            ) ?: break
            if (!canApplyMeldWithoutBlockingTurn(move)) break
            applyMeldMove(move)
            actions++
        }

        if (hand.isEmpty()) {
            finishOrAskMorto(indirect = false)
            return
        }

        val discard = BotDecisionEngine.chooseDiscard(
            hand = hand,
            tableMelds = tableMelds,
            opponentTableMelds = opponentTableMelds,
            config = currentConfig,
            cachetaTurnCard = turnCard
        ) ?: return
        hand = hand.filterNotOnce(discard)
        discardPile = discardPile + discard
        activeSeat = 0
        turnPhase = BotTurnPhase.WAITING
        emitToHost("DISCARD", JSONObject().put("v", 1).put("card", discard.id).put("seat", botSeat).toString())

        if (hand.isEmpty()) {
            val generation = roundGeneration
            scope.launch {
                delay(150)
                if (roundClosed || generation != roundGeneration) return@launch
                finishOrAskMorto(indirect = true)
            }
        }
    }

    private fun closeRoundForBot() {
        roundClosed = true
        actionScheduled = false
        scheduledTaskToken++
        activeSeat = 0
        turnPhase = BotTurnPhase.CLOSED
        pendingDiscardTopId = null
        pendingDiscardRest = emptyList()
    }

    private fun autoDropRedThrees() {
        if (currentConfig.gameType != GameType.TRANCA || !currentConfig.autoMeldTrancaRedThrees) return
        val redThrees = hand.filter {
            it.rank == Rank.THREE && (it.suit == Suit.HEARTS || it.suit == Suit.DIAMONDS)
        }
        redThrees.forEach { redThree ->
            applyMeldMove(
                BotDecisionEngine.MeldMove(
                    cardsFromHand = listOf(redThree),
                    resultingMeld = listOf(redThree)
                )
            )
        }
    }

    private fun applyMeldMove(move: BotDecisionEngine.MeldMove, notifyHost: Boolean = true) {
        if (move.cardsFromHand.isEmpty() || move.resultingMeld.isEmpty()) return
        hand = move.cardsFromHand.fold(hand) { current, card -> current.filterNotOnce(card) }
        tableMelds = replaceOrAppendMeld(tableMelds, move.replaceIndex, move.resultingMeld)

        val requiredTop = pendingDiscardTopId
        if (requiredTop != null && move.cardsFromHand.any { it.id == requiredTop }) {
            hand = sort(hand + pendingDiscardRest)
            discardPile = emptyList()
            pendingDiscardTopId = null
            pendingDiscardRest = emptyList()
        }

        if (notifyHost) {
            emitToHost(
                "MELD",
                JSONObject()
                    .put("v", 1)
                    .put("cards", JSONArray().apply { move.resultingMeld.forEach { put(it.id) } })
                    .put("seat", botSeat)
                    .put("team", botSeat.floorMod(2))
                    .put("replaceIndex", move.replaceIndex)
                    .toString()
            )
        }
    }

    private fun canTakeDiscardWithoutBlockingTurn(): Boolean {
        val topDiscard = discardPile.lastOrNull() ?: return false
        val move = BotDecisionEngine.chooseMeldMove(
            hand = hand + topDiscard,
            tableMelds = tableMelds,
            config = currentConfig,
            cachetaTurnCard = turnCard,
            requiredCardId = topDiscard.id
        ) ?: return false
        val releasedPile = if (currentConfig.gameType == GameType.CACHETA) {
            emptyList()
        } else {
            discardPile.dropLast(1)
        }
        return BotDecisionEngine.keepsLegalTurnFlow(
            hand = hand + topDiscard,
            tableMelds = tableMelds,
            move = move,
            cardsReleasedAfterMove = releasedPile,
            hasPickedMorto = hasPickedMorto,
            mortosLeft = mortosLeft,
            config = currentConfig
        )
    }

    private fun canApplyMeldWithoutBlockingTurn(move: BotDecisionEngine.MeldMove): Boolean {
        return BotDecisionEngine.keepsLegalTurnFlow(
            hand = hand,
            tableMelds = tableMelds,
            move = move,
            cardsReleasedAfterMove = emptyList(),
            hasPickedMorto = hasPickedMorto,
            mortosLeft = mortosLeft,
            config = currentConfig
        )
    }

    private fun finishOrAskMorto(indirect: Boolean) {
        if (roundClosed) return
        if (currentConfig.gameType != GameType.CACHETA && !hasPickedMorto && mortosLeft > 0) {
            turnPhase = BotTurnPhase.WAITING_MORTO
            emitToHost("REQ_PICK_MORTO", seatPayload(indirect))
        } else {
            val canWin = GameRulesEngine.canDeclareWin(
                hand = hand,
                tableMelds = tableMelds,
                hasMorto = currentConfig.gameType != GameType.CACHETA && !hasPickedMorto,
                config = currentConfig
            ).canWin
            if (!canWin) {
                turnPhase = BotTurnPhase.WAITING
                return
            }
            turnPhase = BotTurnPhase.CLOSED
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

    private fun replyRestartMatch() {
        // A maquina nunca recusa uma revanche.
        emitToHost("REPLY_RESTART", "YES")
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
        val message = NetworkMessage(botId, type, payload, senderSeat = botSeat)
        if (!incoming.tryEmit(message)) {
            scope.launch { incoming.emit(message) }
        }
    }

    private fun cardsFromJson(array: JSONArray): List<Card> {
        return buildList {
            repeat(array.length()) { index ->
                allCards[array.optString(index)]?.let { add(it) }
            }
        }
    }

    private fun handsFromJson(array: JSONArray): List<List<Card>> {
        return buildList {
            repeat(array.length()) { index ->
                val cards = cardsFromJson(array.optJSONArray(index) ?: JSONArray())
                if (cards.isNotEmpty()) add(cards)
            }
        }
    }

    private fun parseCardId(payload: String): String {
        val trimmed = payload.trim()
        if (!trimmed.startsWith("{")) return payload
        return runCatching { JSONObject(trimmed).optString("card", payload) }.getOrDefault(payload)
    }

    private fun seatPayload(indirect: Boolean = false): String = JSONObject()
        .put("v", 1)
        .put("seat", botSeat)
        .put("indirect", indirect)
        .toString()

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
        roundGeneration++
        scheduledTaskToken++
        if (!keepConfig) currentConfig = MatchConfig(maxPlayers = 2)
        hand = emptyList()
        tableMelds = emptyList()
        opponentTableMelds = emptyList()
        discardPile = emptyList()
        turnCard = null
        deckSize = 0
        mortosLeft = 0
        hasPickedMorto = false
        activeSeat = 0
        actionScheduled = false
        roundClosed = false
        turnPhase = BotTurnPhase.WAITING
        pendingDiscardTopId = null
        pendingDiscardRest = emptyList()
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

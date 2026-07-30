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
 * Transporte local para partida contra a maquina.
 *
 * Eu trato a IA como se fosse um cliente real: ela recebe GAME_START, pede carta
 * com REQ_DRAW_DECK, baixa jogo com MELD, descarta com DISCARD e pede morto com
 * REQ_PICK_MORTO. Isso e intencional, porque exercita o mesmo fluxo que o online
 * vai usar depois e evita criar regras duplicadas so para o bot.
 *
 * Responsabilidade desta classe:
 * - guardar a mao privada da maquina;
 * - tomar decisoes simples de jogada;
 * - emitir NetworkMessage para o MatchViewModel do host processar oficialmente.
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
        // Mensagens publicas do host atualizam a visao publica da IA.
        // A IA nunca recebe a mao do jogador humano.
        when (message.type) {
            "DISCARD" -> handleHostDiscard(message.payload)
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
            emitToHost("REQ_DRAW_DECK", seatPayload())
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
        // Primeira versao da IA: baixa o que for claramente valido e descarta
        // a carta de maior valor. Depois posso evoluir aqui para niveis de dificuldade.
        autoDropRedThrees()
        val meld = findBestMeld()
        if (meld != null) {
            meldCards(meld)
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
        for (i in 0 until sorted.size - 2) {
            for (j in i + 1 until sorted.size - 1) {
                for (k in j + 1 until sorted.size) {
                    val candidate = listOf(sorted[i], sorted[j], sorted[k])
                    if (GameRulesEngine.validateMeld(candidate, currentConfig, turnCard).isValid) {
                        return candidate
                    }
                }
            }
        }
        return null
    }

    private fun meldCards(cards: List<Card>) {
        if (cards.isEmpty()) return
        hand = cards.fold(hand) { current, card -> current.filterNotOnce(card) }
        tableMelds = tableMelds + listOf(cards)
        emitToHost(
            "MELD",
            JSONObject()
                .put("v", 1)
                .put("cards", JSONArray().apply { cards.forEach { put(it.id) } })
                .put("seat", botSeat)
                .put("team", botSeat.floorMod(2))
                .put("replaceIndex", -1)
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
        return hand
            .filterNot { currentConfig.gameType == GameType.TRANCA && it.rank == Rank.THREE && (it.suit == Suit.HEARTS || it.suit == Suit.DIAMONDS) }
            .maxByOrNull { it.rank.value }
            ?: hand.last()
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

    private fun resetBotState(keepConfig: Boolean = false) {
        if (!keepConfig) currentConfig = MatchConfig(maxPlayers = 2)
        hand = emptyList()
        tableMelds = emptyList()
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

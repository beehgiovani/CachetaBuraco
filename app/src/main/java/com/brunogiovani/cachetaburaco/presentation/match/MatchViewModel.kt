package com.brunogiovani.cachetaburaco.presentation.match

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brunogiovani.cachetaburaco.domain.models.Card
import com.brunogiovani.cachetaburaco.domain.models.DeckFactory
import com.brunogiovani.cachetaburaco.domain.models.GameType
import com.brunogiovani.cachetaburaco.domain.models.MatchConfig
import com.brunogiovani.cachetaburaco.domain.models.Rank
import com.brunogiovani.cachetaburaco.domain.models.Suit
import com.brunogiovani.cachetaburaco.domain.repositories.ConnectionStatus
import com.brunogiovani.cachetaburaco.domain.repositories.LocalNetworkRepository
import com.brunogiovani.cachetaburaco.domain.repositories.NetworkMessage
import com.brunogiovani.cachetaburaco.domain.usecases.GameRulesEngine
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.lang.ref.WeakReference
import java.util.UUID

// Fases simples do turno: esperando o outro jogador, comprando ou jogando.
enum class TurnPhase {
    WAITING_OPPONENT,
    DRAW,
    ACTION
}

data class RoundEndDetails(
    val winnerName: String,
    val myRoundScore: Int,
    val opponentRoundScore: Int,
    val myNewTotal: Int,
    val opponentNewTotal: Int,
    val isMatchOver: Boolean,
    val breakdown: String,
    val teamScores: List<Int> = emptyList(),
    val winnerTeam: Int? = null,
    /** Índice absoluto de equipe (0 ou 1) do jogador local neste dispositivo. */
    val localTeam: Int = 0,
    val myLabel: String = "Você",
    val opponentLabel: String = "Oponente"
)

private data class RoundSeatReport(
    val playerId: String,
    val seat: Int,
    val hand: List<Card>,
    val tableMelds: List<List<Card>>
)

private data class PendingRemoteDiscardDraw(
    val topCardId: String,
    val remainingPile: List<Card>
)

// Estado único observado pela tela. A ideia é deixar a UI só desenhar a mesa,
// sem precisar procurar regra ou dado privado em outra camada.
data class GameState(
    val myHand: List<Card> = emptyList(),
    val dealEventId: Int = 0,
    val selectedCards: Set<Card> = emptySet(),
    val discardPile: List<Card> = emptyList(),
    val turnCard: Card? = null,
    val myTableMelds: List<List<Card>> = emptyList(),
    val opponentTableMelds: List<List<Card>> = emptyList(),
    val opponentHandCount: Int = 0,
    val deckSize: Int = 0,
    val mortosLeft: Int = 0,
    val playerSeat: Int = 0,
    val activeSeat: Int = 0,
    val turnPhase: TurnPhase = TurnPhase.WAITING_OPPONENT,
    val feedbackMessage: String = "Aguardando início...",
    val lastMeldResult: String = "",
    val isDiscardLocked: Boolean = false,
    val canDrawFromDiscard: Boolean = true,
    val drawDiscardBlockedReason: String = "",
    val myScore: Int = 0,
    val opponentScore: Int = 0,
    val teamScores: List<Int> = emptyList(),
    val showRoundEndDialog: Boolean = false,
    val roundEndDetails: RoundEndDetails? = null,
    val config: MatchConfig = MatchConfig(),
    val pendingMeldTargets: List<Int>? = null,
    val lastDrawnCardId: String? = null,
    val opponentPickedMorto: Boolean = false,
    val mortoNoticeSeat: Int? = null,
    val mortoNoticeId: Int = 0,
    val showRestartMatchDialog: Boolean = false,
    val myLabel: String = "Você",
    val opponentLabel: String = "Oponente"
)

/**
 * Coração da partida.
 *
 * O host é quem manda oficialmente na mesa: distribui cartas, guarda o monte real,
 * controla mortos, fecha placar e valida os pedidos privados dos clientes. Cliente
 * e máquina passam pelo mesmo protocolo de NetworkMessage, para o modo online poder
 * entrar depois sem regra duplicada espalhada pela UI.
 *
 * Quando o online chegar, a troca principal deve ser no transporte/autorizador.
 * O motor de regras continua sendo o GameRulesEngine.
 */
class MatchViewModel(
    private val networkRepository: LocalNetworkRepository,
    private val playerId: String,
    private val isHost: Boolean,
    private val config: MatchConfig,
    context: Context? = null
) : ViewModel() {
    private val appContextRef = context?.applicationContext?.let(::WeakReference)

    private val _gameState = MutableStateFlow(GameState(
        myScore = if (config.gameType == GameType.CACHETA) config.pointLimit else 0,
        opponentScore = if (config.gameType == GameType.CACHETA) config.pointLimit else 0,
        teamScores = initialTeamScores(config),
        config = config,
        opponentLabel = if (isMachineMatch()) "Máquina" else "Oponente"
    ))
    val gameState: StateFlow<GameState> = _gameState.asStateFlow()

    private var currentConfig = config
    private var localSeat = if (isHost) 0 else -1
    var isRestored: Boolean = false
    private val remoteHandsBySeat = mutableMapOf<Int, List<Card>>()
    private val remotePlayerSeats = mutableMapOf<String, Int>()

    // Só o host conhece o monte e os mortos completos.
    // Cliente recebe carta servida; isso evita mão duplicada e já prepara o online.
    private var masterDeck = mutableListOf<Card>()
    private var mortos = mutableListOf<List<Card>>()
    private val teamsThatPickedMorto = mutableSetOf<Int>()
    private val teamsWithMortoServedByHost = mutableSetOf<Int>()
    private val deckServedSeatsThisTurn = mutableSetOf<Int>()
    private val pendingRoundReports = mutableMapOf<Int, RoundSeatReport>()
    private var pendingWinnerId: String? = null
    private var pendingWinnerTeam: Int? = null
    private var pendingCountOnlyRound: Boolean = false
    private var pendingMortoPickupIsIndirect: Boolean = false
    private var pendingDiscardDrawCardId: String? = null
    private var pendingDiscardDrawRest: List<Card> = emptyList()
    private val pendingRemoteDiscardDraws = mutableMapOf<Int, PendingRemoteDiscardDraw>()
    private val processedNetworkMessageIds = linkedSetOf<String>()
    private val readyRemoteSeats = mutableSetOf<Int>()
    private var gameStartRequested = false
    private val gameStartMessagesBySeat = mutableMapOf<Int, NetworkMessage>()
    private val pendingGameStartSeats = mutableSetOf<Int>()
    private val confirmedGameStartSeats = mutableSetOf<Int>()
    private var currentRoundId: String? = null

    private data class PreparedMorto(
        val hand: List<Card>,
        val redThreeMelds: List<List<Card>>
    )

    private fun isMachineMatch(): Boolean {
        // Propriedade do contrato em vez do nome da classe: R8/ProGuard pode
        // renomear a classe em builds de release e quebrar uma comparacao por string.
        return networkRepository.isBotRepository
    }

    private fun opponentDisplayLabel(): String = if (isMachineMatch()) "Máquina" else "Oponente"

    private fun teamDisplayLabel(team: Int, localTeam: Int): String {
        return if (team == localTeam) "Seu lado" else "Lado ${opponentDisplayLabel().lowercase()}"
    }

    private fun seatDisplayLabel(seat: Int, localSeat: Int): String {
        if (seat == localSeat) return "Você"
        return if (teamForSeat(seat) == teamForSeat(localSeat)) "Seu parceiro" else opponentDisplayLabel()
    }

    private fun clearRoundEndUiState(state: GameState = _gameState.value): GameState {
        return state.copy(
            selectedCards = emptySet(),
            pendingMeldTargets = null,
            lastDrawnCardId = null,
            lastMeldResult = "",
            showRoundEndDialog = false,
            roundEndDetails = null,
            showRestartMatchDialog = false
        )
    }

    // Mapa rápido para transformar IDs recebidos pela rede em cartas reais.
    private val allCardsMap: Map<String, Card> by lazy {
        DeckFactory.createDecks(2, includeJokers = false).associateBy { it.id }
    }

    init {
        // O bot precisa receber GAME_START para reconstruir a própria mão e fase.
        // Um snapshot visual antigo não contém esse estado privado, então partidas
        // solo sempre recomeçam pelo fluxo completo de distribuição. Online também
        // fica de fora: ele já tem um jeito de reconectar de verdade pelo servidor
        // (REQ_RECONNECT/RECONNECT_STATE), e depois da autoridade do baralho passar
        // pro servidor, um snapshot local pode nem bater mais com o que o servidor
        // considera a mesa atual -- "recuperar" com um retrato antigo faria mais
        // mal do que bem.
        isRestored = canUseLocalSnapshot() && tryRestoreFromSnapshot()
        viewModelScope.launch {
            networkRepository.incomingMessages.collect { message ->
                handleNetworkMessage(message)
            }
        }
        if (!isHost && networkRepository.requiresClientReadyHandshake) {
            viewModelScope.launch {
                while (localSeat < 0) {
                    if (networkRepository.connectionStatus.value == ConnectionStatus.CONNECTED) {
                        networkRepository.sendMessage(NetworkMessage(playerId, "CLIENT_READY", ""))
                    }
                    delay(500)
                }
            }
        }
        // Snapshot local para recuperar queda/reabertura -- só pra Wi-Fi local, que
        // é o único transporte sem um jeito melhor de retomar a mesa (ver comentário
        // acima). Sem isso, "Continuar Partida Salva" no menu principal sempre
        // reconectava pelo Wi-Fi local mesmo quando a partida salva era contra a
        // máquina ou online, deixando a mesa visível mas sem nenhuma conexão de
        // verdade por trás -- o jogo travava. Durante o diálogo de fim de rodada eu
        // não salvo, para não gravar uma mesa limpa antes da próxima distribuição.
        if (canUseLocalSnapshot()) {
            viewModelScope.launch {
                _gameState.collect { state ->
                    if (!state.showRoundEndDialog &&
                        (state.turnPhase != TurnPhase.WAITING_OPPONENT ||
                        state.myTableMelds.isNotEmpty())) {
                        saveGameSnapshot(state)
                    }
                }
            }
        }
    }

    private fun canUseLocalSnapshot(): Boolean {
        return !isMachineMatch() && !networkRepository.isOnlineTransport
    }

    // --- Início do jogo ---

    /**
     * Inicia a partida a partir do lobby, configurando o estado inicial de acordo com as regras (Cacheta, Buraco ou Tranca).
     */
    fun startGame() {
        if (!isHost) return
        val expectedReadyClients = currentConfig.maxPlayers - 1
        if (networkRepository.requiresClientReadyHandshake && readyRemoteSeats.size < expectedReadyClients) {
            gameStartRequested = true
            _gameState.value = _gameState.value.copy(
                feedbackMessage = "Aguardando os jogadores abrirem a mesa..."
            )
            return
        }
        gameStartRequested = false
        currentRoundId = UUID.randomUUID().toString()

        mortos.clear()
        teamsThatPickedMorto.clear()
        teamsWithMortoServedByHost.clear()
        deckServedSeatsThisTurn.clear()
        remoteHandsBySeat.clear()
        gameStartMessagesBySeat.clear()
        pendingGameStartSeats.clear()
        confirmedGameStartSeats.clear()
        pendingRoundReports.clear()
        pendingWinnerId = null
        pendingWinnerTeam = null
        pendingCountOnlyRound = false
        restartMatchApprovals.clear()
        pendingMortoPickupIsIndirect = false
        pendingDiscardDrawCardId = null
        pendingDiscardDrawRest = emptyList()
        pendingRemoteDiscardDraws.clear()

        // No online, quem embaralha e distribui a rodada e o servidor (RPC
        // start_online_round), nao o proprio aparelho -- assim um host mal
        // intencionado nao escolhe mais quem recebe o que. Wi-Fi local e
        // maquina nao tem servidor, entao continuam com a distribuicao local
        // de sempre logo abaixo.
        if (networkRepository.isOnlineTransport) {
            _gameState.value = _gameState.value.copy(
                feedbackMessage = "Preparando distribuição no servidor..."
            )
            networkRepository.requestServerDeal { rawJson ->
                viewModelScope.launch { applyServerDeal(rawJson) }
            }
            return
        }

        masterDeck = DeckFactory.createDecks(2, includeJokers = false).toMutableList()

        val myCards = dealCards(currentConfig.cardsPerPlayer, isInitialDeal = true)
        val opponentHands = (1 until currentConfig.maxPlayers).map { dealCards(currentConfig.cardsPerPlayer, isInitialDeal = true) }

        if (currentConfig.gameType != GameType.CACHETA) {
            // O morto nasce com onze cartas físicas. Os 3 vermelhos só são baixados
            // e repostos quando a equipe realmente pega esse morto.
            mortos.add(dealCards(11))
            mortos.add(dealCards(11))
        }

        val turnCard = if (currentConfig.gameType == GameType.CACHETA) drawTopCard() else null
        val firstDiscard = when {
            currentConfig.gameType == GameType.CACHETA && currentConfig.cachetaStartsWithDiscard -> turnCard
            currentConfig.gameType == GameType.CACHETA -> null
            else -> drawOpeningDiscard()
        }
        val sortedHand = sortHandIfEnabled(myCards)
        
        // O host prepara todos os 3 vermelhos antes de distribuir as maos privadas.
        // Assim nenhum aparelho precisa corrigir a abertura por uma mensagem que pode chegar cedo demais.
        val (processedHand, localRedThreeMelds) = if (
            currentConfig.gameType == GameType.TRANCA && currentConfig.autoMeldTrancaRedThrees
        ) {
            GameRulesEngine.handleThreeReds(
                hand = sortedHand,
                tableMelds = emptyList(),
                gameType = currentConfig.gameType
            )
        } else {
            sortedHand to emptyList()
        }
        var localTeamMelds = localRedThreeMelds
        var opponentTeamMelds = emptyList<List<Card>>()
        val preparedOpponentHands = opponentHands.mapIndexed { index, hand ->
            val seat = index + 1
            if (currentConfig.gameType == GameType.TRANCA && currentConfig.autoMeldTrancaRedThrees) {
                val sameTeamAsHost = teamForSeat(seat) == teamForSeat(localSeat)
                val currentTeamMelds = if (sameTeamAsHost) localTeamMelds else opponentTeamMelds
                val (preparedHand, preparedMelds) = GameRulesEngine.handleThreeReds(
                    hand = hand,
                    tableMelds = currentTeamMelds,
                    gameType = currentConfig.gameType
                )
                if (sameTeamAsHost) localTeamMelds = preparedMelds else opponentTeamMelds = preparedMelds
                sortHandIfEnabled(preparedHand)
            } else {
                sortHandIfEnabled(hand)
            }
        }

        updateDiscardState(processedHand, firstDiscard, turnCard, localTeamMelds, activeSeat = 1)
        _gameState.value = _gameState.value.copy(opponentTableMelds = opponentTeamMelds)

        preparedOpponentHands.forEachIndexed { index, hand ->
            val seat = index + 1
            remoteHandsBySeat[seat] = hand
            val startMessage = NetworkMessage(
                senderId = playerId,
                type = "GAME_START",
                payload = buildGameStartPayload(hand, firstDiscard, turnCard, playerSeat = seat),
                roundId = currentRoundId
            )
            gameStartMessagesBySeat[seat] = startMessage
            sendGameStartToSeat(seat, startMessage)
        }

        // A distribuicao privada vai primeiro; depois todos recebem a mesma fotografia publica.
        // Isso evita que um 3 vermelho baixado na abertura desapareca no cliente ou na maquina.
        publishPublicTableState()
    }

    /**
     * Aplica o resultado de `start_online_round`: o servidor ja embaralhou, ja
     * distribuiu a mao de cada assento e ja mandou o GAME_START/PUBLIC_STATE
     * pros outros jogadores sozinho (dentro da RPC). Aqui eu so preciso montar
     * o mesmo estado local que o host sempre teve -- mao propria, mesa da
     * equipe, monte restante pra continuar puxando carta e mortos -- porque
     * o proprio host nunca recebe de volta os eventos que ele mesmo disparou
     * (handleNetworkMessage descarta qualquer coisa com senderId igual ao seu).
     */
    private fun applyServerDeal(rawJson: String?) {
        val json = rawJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (json == null) {
            _gameState.value = _gameState.value.copy(
                feedbackMessage = "Não foi possível preparar a rodada. Tente novamente."
            )
            gameStartRequested = true
            return
        }

        currentRoundId = json.optString("roundId").takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        // O servidor nao devolve mais o baralho de verdade aqui (ver comentario
        // na migration 0025): devolver a ordem inteira do monte pro host
        // destruiria a propria protecao de online_draw_deck_card, que decide
        // cada compra da rodada -- o host nem precisaria burlar a RPC, so ler
        // a ordem completa direto da distribuicao. masterDeck vira so um
        // marcador de TAMANHO pro online (nunca mais lido por conteudo, ja que
        // toda compra online passa pela RPC); mortos ainda vem com o conteudo
        // real porque REQ_PICK_MORTO continua sendo servido localmente por
        // enquanto (gap conhecido, fica pra uma proxima fase).
        masterDeck = MutableList(json.optInt("deckSize", 0)) { PLACEHOLDER_DECK_CARD }

        val mortosJson = json.optJSONArray("mortos") ?: JSONArray()
        repeat(mortosJson.length()) { index ->
            val morto = cardsFromJson(mortosJson.optJSONArray(index) ?: JSONArray())
            if (morto.isNotEmpty()) mortos.add(morto)
        }

        val handsJson = json.optJSONObject("hands") ?: JSONObject()
        val myCards = cardsFromJson(handsJson.optJSONArray("0") ?: JSONArray())
        for (seat in 1 until currentConfig.maxPlayers) {
            remoteHandsBySeat[seat] = cardsFromJson(handsJson.optJSONArray(seat.toString()) ?: JSONArray())
        }

        val turnCard = allCardsMap[json.optString("turnCard")]
        val firstDiscard = allCardsMap[json.optString("discard")]
        val sortedHand = sortHandIfEnabled(myCards)

        val team0Melds = handsFromJson(json.optJSONArray("team0Melds") ?: JSONArray())
        val team1Melds = handsFromJson(json.optJSONArray("team1Melds") ?: JSONArray())
        val localTeamMelds = if (teamForSeat(localSeat) == 0) team0Melds else team1Melds
        val opponentTeamMelds = if (teamForSeat(localSeat) == 0) team1Melds else team0Melds

        updateDiscardState(sortedHand, firstDiscard, turnCard, localTeamMelds, activeSeat = 1)
        _gameState.value = _gameState.value.copy(opponentTableMelds = opponentTeamMelds)
    }

    /**
     * Distribui as cartas iniciais da rodada para todos os jogadores.
     */
    private fun dealCards(count: Int, isInitialDeal: Boolean = false): List<Card> {
        val hand = mutableListOf<Card>()
        var validCount = 0
        while (validCount < count) {
            val card = drawTopCard() ?: break
            hand.add(card)
            if (isInitialDeal && currentConfig.gameType == GameType.TRANCA &&
                currentConfig.autoMeldTrancaRedThrees
            ) {
                val isRedThree = card.rank == Rank.THREE && (card.suit == Suit.HEARTS || card.suit == Suit.DIAMONDS)
                if (!isRedThree) validCount++
            } else {
                validCount++
            }
        }
        return hand
    }

    /**
     * Retira a carta do topo do monte principal (apenas Host).
     */
    private fun drawTopCard(): Card? {
        return if (masterDeck.isNotEmpty()) masterDeck.removeAt(masterDeck.lastIndex) else null
    }

    /**
     * Na abertura da Tranca o 3 vermelho nao fica no lixo. Eu separo qualquer um
     * sorteado, viro outra carta e devolvo os 3 ao meio do monte sem perder carta.
     */
    private fun drawOpeningDiscard(): Card? {
        if (currentConfig.gameType != GameType.TRANCA) return drawTopCard()

        val postponedRedThrees = mutableListOf<Card>()
        var openingCard: Card? = null
        while (masterDeck.isNotEmpty() && openingCard == null) {
            val candidate = drawTopCard() ?: break
            val isRedThree = candidate.rank == Rank.THREE &&
                (candidate.suit == Suit.HEARTS || candidate.suit == Suit.DIAMONDS)
            if (isRedThree) postponedRedThrees += candidate else openingCard = candidate
        }
        if (postponedRedThrees.isNotEmpty()) {
            masterDeck.addAll(postponedRedThrees)
            masterDeck.shuffle()
        }
        return openingCard
    }

    /**
     * Abre um morto sem alterar sua quantidade regulamentar de onze cartas.
     * Cada 3 vermelho vai para a mesa e recebe uma reposicao do monte; se a
     * reposicao tambem for um 3 vermelho, o processo continua ate a mao estabilizar.
     */
    private fun prepareMortoForPickup(rawMorto: List<Card>): PreparedMorto {
        if (!currentConfig.autoMeldTrancaRedThrees || currentConfig.gameType != GameType.TRANCA) {
            return PreparedMorto(sortHandIfEnabled(rawMorto), emptyList())
        }

        val preparedHand = rawMorto.toMutableList()
        val redThreeMelds = mutableListOf<List<Card>>()
        while (true) {
            val redThrees = preparedHand.filter(::isTrancaRedThree)
            if (redThrees.isEmpty()) break

            preparedHand.removeAll(redThrees.toSet())
            redThreeMelds += redThrees.map(::listOf)
            repeat(redThrees.size) {
                drawTopCard()?.let(preparedHand::add)
            }
        }

        return PreparedMorto(
            hand = sortHandIfEnabled(preparedHand),
            redThreeMelds = redThreeMelds
        )
    }

    /**
     * Ordena as cartas da mão automaticamente se a configuração permitir.
     */
    private fun sortHandIfEnabled(cards: List<Card>): List<Card> {
        return if (currentConfig.autoSortHand) {
            GameRulesEngine.sortHand(cards, currentConfig.gameType)
        } else {
            cards
        }
    }

    // --- Ações do jogador ---

    /** Compra uma carta do monte. Cliente sempre pede ao host; host processa na hora. */
    fun drawFromDeck() {
        var state = _gameState.value
        if (state.turnPhase != TurnPhase.DRAW) return

        if (isHost) {
            pendingDiscardDrawCardId = null
            pendingDiscardDrawRest = emptyList()

            if (networkRepository.isOnlineTransport) {
                networkRepository.requestServerDraw(localSeat) { rawJson ->
                    viewModelScope.launch { applyServerDrawResult(rawJson) }
                }
                return
            }

            if (masterDeck.isEmpty() && !prepareDeckForDraw(state)) {
                return
            }
            state = _gameState.value
            val card = drawTopCard() ?: return
            val newHand = sortHandIfEnabled(state.myHand + card)
            val (processedHand, newMelds) = autoProcessThreeReds(newHand, state.myTableMelds)
            val droppedThree = processedHand.size < newHand.size
            _gameState.value = state.copy(
                myHand = processedHand,
                myTableMelds = newMelds,
                deckSize = masterDeck.size,
                turnPhase = if (droppedThree) TurnPhase.DRAW else TurnPhase.ACTION,
                lastDrawnCardId = card.id,
                feedbackMessage = if (droppedThree) "3 Vermelho! Você deve comprar novamente." else "Você comprou do Monte. Baixe ou Descarte."
            )
            networkRepository.sendMessage(NetworkMessage(playerId, "DRAW_DECK", card.id))
            publishPublicTableState()
        } else if (!isHost) {
            pendingDiscardDrawCardId = null
            pendingDiscardDrawRest = emptyList()
            // Cliente pede ao host para comprar
            _gameState.value = state.copy(
                turnPhase = TurnPhase.WAITING_OPPONENT,
                lastDrawnCardId = null,
                feedbackMessage = "Aguardando carta do host..."
            )
            networkRepository.sendMessage(NetworkMessage(playerId, "REQ_DRAW_DECK", buildSeatPayload(localSeat)))
        }
    }

    /**
     * Aplica o resultado de `online_draw_deck_card` na compra do proprio host.
     * O servidor ja decidiu a carta (e ja reciclou lixo/morto se precisou) e
     * ja publicou o PUBLIC_STATE atualizado sozinho -- aqui so replico o
     * mesmo efeito local que drawTopCard() sempre teve.
     */
    private fun applyServerDrawResult(rawJson: String?) {
        val json = rawJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (json == null) {
            _gameState.value = _gameState.value.copy(
                feedbackMessage = "Não foi possível comprar do monte. Tente novamente."
            )
            return
        }
        when (json.optString("status")) {
            "EXHAUSTED" -> beginCountOnlyRound()
            "OK" -> {
                // O host so tem UMA copia de tamanho de mortos (nao o conteudo
                // real, ver applyServerDeal); quando o servidor consome um
                // morto pra virar monte novo, espelho o mesmo consumo aqui
                // senao SERVE_MORTO (ainda local, fase seguinte) podia tentar
                // servir de novo um morto que o servidor ja gastou.
                if (json.optBoolean("mortoConsumedForRefill", false) && mortos.isNotEmpty()) {
                    mortos.removeAt(0)
                }
                val card = allCardsMap[json.optString("card")] ?: run {
                    _gameState.value = _gameState.value.copy(
                        feedbackMessage = "Carta inválida recebida do servidor."
                    )
                    return
                }
                val state = _gameState.value
                val newHand = sortHandIfEnabled(state.myHand + card)
                val (processedHand, newMelds) = autoProcessThreeReds(newHand, state.myTableMelds)
                val droppedThree = processedHand.size < newHand.size
                _gameState.value = state.copy(
                    myHand = processedHand,
                    myTableMelds = newMelds,
                    deckSize = json.optInt("deckSize", state.deckSize),
                    mortosLeft = json.optInt("mortosLeft", state.mortosLeft),
                    discardPile = json.optJSONArray("discardPile")?.let { cardsFromJson(it) } ?: state.discardPile,
                    turnPhase = if (droppedThree) TurnPhase.DRAW else TurnPhase.ACTION,
                    lastDrawnCardId = card.id,
                    feedbackMessage = if (droppedThree) "3 Vermelho! Você deve comprar novamente." else "Você comprou do Monte. Baixe ou Descarte."
                )
                networkRepository.sendMessage(NetworkMessage(playerId, "DRAW_DECK", card.id))
            }
            else -> _gameState.value = _gameState.value.copy(
                feedbackMessage = "Não foi possível comprar do monte. Tente novamente."
            )
        }
    }

    /**
     * Aplica o resultado de `online_take_morto` no pedido de morto do proprio
     * host. O servidor ja decidiu o conteudo (incluindo 3 vermelho extraidos
     * e repostos do monte) e ja publicou o PUBLIC_STATE atualizado sozinho.
     */
    private fun applyServerMortoResultForOwnSeat(rawJson: String?, meldLabel: String) {
        val json = rawJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (json == null || json.optString("status") != "OK") {
            _gameState.value = _gameState.value.copy(
                feedbackMessage = "Não foi possível pegar o morto agora. Tente novamente."
            )
            return
        }
        val hand = cardsFromJson(json.optJSONArray("hand") ?: JSONArray())
        if (hand.size != currentConfig.cardsPerPlayer) {
            _gameState.value = _gameState.value.copy(
                feedbackMessage = "Morto inválido recebido do servidor."
            )
            return
        }
        if (mortos.isNotEmpty()) mortos.removeAt(0)
        val redThreeMelds = handsFromJson(json.optJSONArray("redThreeMelds") ?: JSONArray())
        teamsThatPickedMorto.add(teamForSeat(localSeat))
        val state = _gameState.value
        _gameState.value = state.copy(
            myHand = sortHandIfEnabled(hand),
            myTableMelds = state.myTableMelds + redThreeMelds,
            mortosLeft = json.optInt("mortosLeft", state.mortosLeft),
            deckSize = json.optInt("deckSize", state.deckSize),
            lastMeldResult = meldLabel,
            feedbackMessage = "$meldLabel Você pegou o Morto!",
            mortoNoticeSeat = localSeat,
            mortoNoticeId = if (state.mortoNoticeSeat == localSeat) state.mortoNoticeId else state.mortoNoticeId + 1
        )
    }

    /**
     * Garante que existe monte para comprar, reciclando lixo ou morto se necessário.
     */
    private fun prepareDeckForDraw(state: GameState): Boolean {
        if (masterDeck.isNotEmpty()) return true

        return when (currentConfig.gameType) {
            GameType.CACHETA -> recycleCachetaDiscardAsDeck(state)
            GameType.BURACO, GameType.TRANCA -> {
                if (useMortoAsDeckIfAvailable(state)) {
                    true
                } else {
                    beginCountOnlyRound()
                    false
                }
            }
        }
    }

    private fun recycleCachetaDiscardAsDeck(state: GameState): Boolean {
        val topDiscard = state.discardPile.lastOrNull()
        val cardsToRecycle = state.discardPile.dropLast(1)
        if (topDiscard == null || cardsToRecycle.isEmpty()) {
            _gameState.value = state.copy(
                deckSize = 0,
                feedbackMessage = "Monte vazio e lixo insuficiente para reciclar."
            )
            return false
        }

        masterDeck = cardsToRecycle.shuffled().toMutableList()
        _gameState.value = state.copy(
            discardPile = listOf(topDiscard),
            deckSize = masterDeck.size,
            feedbackMessage = "Monte refeito com o lixo."
        )
        return true
    }

    private fun useMortoAsDeckIfAvailable(state: GameState): Boolean {
        if (mortos.isEmpty()) {
            _gameState.value = state.copy(
                deckSize = 0,
                feedbackMessage = "Monte vazio e sem morto para virar monte."
            )
            return false
        }

        masterDeck = mortos.removeAt(0).shuffled().toMutableList()
        val mortosLeft = mortos.size
        networkRepository.sendMessage(NetworkMessage(playerId, "PICK_MORTO", buildMortosLeftPayload(mortosLeft, pickedSeat = -1)))
        _gameState.value = state.copy(
            deckSize = masterDeck.size,
            mortosLeft = mortosLeft,
            feedbackMessage = "Um morto virou o novo monte."
        )
        publishPublicTableState()
        return true
    }

    /** Compra do lixo respeitando a regra de cada modo e a carta obrigatória de Buraco/Tranca. */
    fun drawFromDiscard() {
        val state = _gameState.value
        if (state.turnPhase != TurnPhase.DRAW) return
        val topCard = state.discardPile.lastOrNull() ?: return

        val check = GameRulesEngine.canDrawFromDiscard(topCard, currentConfig)
        if (!check.allowed) {
            _gameState.value = state.copy(feedbackMessage = check.reason)
            return
        }

        val mustJustifyDiscardDraw = currentConfig.gameType != GameType.CACHETA
        if (mustJustifyDiscardDraw) {
            val canJustify = GameRulesEngine.canJustifyDiscardDraw(
                topDiscard = topCard,
                hand = state.myHand,
                tableMelds = state.myTableMelds,
                config = currentConfig,
                cachetaTurnCard = state.turnCard
            )
            if (!canJustify) {
                _gameState.value = state.copy(
                    feedbackMessage = "Você não pode comprar do lixo: a carta não forma jogo novo nem encaixa nos existentes!"
                )
                return
            }
        }

        val restOfDiscard = state.discardPile.dropLast(1)
        val drawnCards = listOf(topCard)
        val newPile = if (currentConfig.gameType == GameType.CACHETA) emptyList() else restOfDiscard

        val newHand = sortHandIfEnabled(state.myHand + drawnCards)
        val (processedHand, newMelds) = autoProcessThreeReds(newHand, state.myTableMelds)
        pendingDiscardDrawCardId = if (mustJustifyDiscardDraw) topCard.id else null
        pendingDiscardDrawRest = if (mustJustifyDiscardDraw) restOfDiscard else emptyList()

        _gameState.value = state.copy(
            myHand = processedHand,
            myTableMelds = newMelds,
            discardPile = newPile,
            turnPhase = TurnPhase.ACTION,
            lastDrawnCardId = topCard.id,
            feedbackMessage = "Você comprou do Lixo. Baixe ou Descarte."
        )
        networkRepository.sendMessage(NetworkMessage(playerId, "DRAW_DISCARD", topCard.id))
        publishPublicTableState()
    }

    /**
     * Alterna o estado de seleção de uma carta na mão do jogador atual.
     */
    fun toggleCardSelection(card: Card) {
        if (_gameState.value.turnPhase != TurnPhase.ACTION) return
        val current = _gameState.value.selectedCards.toMutableSet()
        if (card in current) current.remove(card) else current.add(card)
        _gameState.value = _gameState.value.copy(selectedCards = current)
    }

    /**
     * Cancela a seleção de alvo para encaixe de jogo (Meld).
     */
    fun cancelMeldTargetSelection() {
        _gameState.value = _gameState.value.copy(pendingMeldTargets = null)
    }

    /**
     * Tenta baixar ou encaixar as cartas selecionadas na mesa, validando as regras do jogo atual.
     */
    fun meldSelectedCards(chosenTargetIndex: Int? = null) {
        val state = _gameState.value
        if (state.turnPhase != TurnPhase.ACTION) return
        val selected = state.selectedCards.toList()
        if (selected.isEmpty()) {
            _gameState.value = state.copy(lastMeldResult = "Selecione cartas para baixar")
            return
        }

        val pendingDiscardCard = pendingDiscardDrawCardId
        if (pendingDiscardCard != null && selected.none { it.id == pendingDiscardCard }) {
            _gameState.value = state.copy(lastMeldResult = "Use a carta comprada do lixo antes de descartar")
            return
        }

        // Se o índice de destino já foi especificado pelo jogador
        val appendTargetIndex = if (chosenTargetIndex != null) {
            chosenTargetIndex
        } else {
            // Identifica todos os alvos válidos para encaixe
            val eligibleTargets = findAppendTargets(state.myTableMelds, selected, state.turnCard)
            val isValidAsNew = if (selected.size >= 3) {
                GameRulesEngine.validateMeld(selected, currentConfig, state.turnCard).isValid
            } else {
                false
            }

            if (eligibleTargets.size > 1) {
                // Múltiplos jogos válidos, ou pode criar jogo: abre diálogo
                _gameState.value = state.copy(pendingMeldTargets = eligibleTargets)
                return
            } else if (eligibleTargets.size == 1 && isValidAsNew) {
                // Um jogo válido + novo jogo válido: abre diálogo para escolha
                _gameState.value = state.copy(pendingMeldTargets = eligibleTargets)
                return
            } else if (eligibleTargets.size == 1) {
                // Apenas um jogo possível para encaixe
                eligibleTargets.first()
            } else {
                // Novo jogo
                -1
            }
        }

        val isNewMeld = appendTargetIndex < 0
        if (currentConfig.gameType == GameType.CACHETA && !isNewMeld) {
            val targetMeld = state.myTableMelds[appendTargetIndex]
            val targetType = GameRulesEngine.validateMeld(targetMeld, currentConfig, state.turnCard).meldType
            if (targetType == GameRulesEngine.MeldType.TRINCA) {
                _gameState.value = state.copy(
                    lastMeldResult = "Na Cacheta, trinca fica fechada com exatamente 3 cartas",
                    pendingMeldTargets = null
                )
                return
            }
        }
        val result = if (!isNewMeld) {
            val targetMeld = state.myTableMelds[appendTargetIndex]
            GameRulesEngine.validateMeld(targetMeld + selected, currentConfig, state.turnCard)
        } else {
            GameRulesEngine.validateMeld(selected, currentConfig, state.turnCard)
        }

        if (isNewMeld && selected.size < 3) {
            _gameState.value = state.copy(
                lastMeldResult = "Selecione ao menos 3 cartas",
                pendingMeldTargets = null
            )
            return
        }

        if (!result.isValid) {
            _gameState.value = state.copy(
                lastMeldResult = "❌ ${result.reason}",
                selectedCards = emptySet(),
                pendingMeldTargets = null
            )
            return
        }

        var updatedHand = state.myHand.filter { it !in selected }
        var updatedMelds = if (!isNewMeld) {
            state.myTableMelds.mapIndexed { index, meld ->
                if (index == appendTargetIndex) GameRulesEngine.sortMeld(meld + selected, currentConfig, state.turnCard) else meld
            }
        } else {
            state.myTableMelds + listOf(GameRulesEngine.sortMeld(selected, currentConfig, state.turnCard))
        }
        val releasedDiscardRest = pendingDiscardDrawRest
        if (pendingDiscardCard != null && releasedDiscardRest.isNotEmpty()) {
            val handWithRest = sortHandIfEnabled(updatedHand + releasedDiscardRest)
            val processed = autoProcessThreeReds(handWithRest, updatedMelds)
            updatedHand = processed.first
            updatedMelds = processed.second
        }
        val discardPileAfterMeld = if (pendingDiscardCard != null && currentConfig.gameType != GameType.CACHETA) {
            emptyList()
        } else {
            state.discardPile
        }
        val publicMeld = if (!isNewMeld) updatedMelds[appendTargetIndex] else selected
        val replaceMeldIndex = if (!isNewMeld) appendTargetIndex else -1
        val meldLabel = when (result.meldType) {
            GameRulesEngine.MeldType.CANASTRA_LIMPA -> "✨ Canastra Limpa!"
            GameRulesEngine.MeldType.CANASTRA_SUJA -> "🃏 Canastra Suja!"
            GameRulesEngine.MeldType.TRINCA -> "🎯 Trinca!"
            GameRulesEngine.MeldType.SEQUENCIA -> "🔗 Sequência!"
            else -> ""
        }

        // Reseta estados temporários de compra/escolha
        pendingDiscardDrawCardId = null
        pendingDiscardDrawRest = emptyList()
        var finalHand = updatedHand
        var mortosLeft = state.mortosLeft
        var feedback = "Jogo baixado! $meldLabel"
        var pickedMortoSeat: Int? = null

        if (finalHand.isEmpty() && state.mortosLeft > 0 && !teamsThatPickedMorto.contains(teamForSeat(localSeat))) {
            if (!isHost) {
                pendingMortoPickupIsIndirect = false
                _gameState.value = state.copy(
                    myHand = finalHand,
                    myTableMelds = updatedMelds,
                    discardPile = discardPileAfterMeld,
                    selectedCards = emptySet(),
                    mortosLeft = mortosLeft,
                    lastMeldResult = meldLabel,
                    feedbackMessage = "$meldLabel Solicitando o Morto...",
                    pendingMeldTargets = null
                )
                networkRepository.sendMessage(NetworkMessage(
                    playerId, "MELD", buildMeldPayload(publicMeld, localSeat, replaceMeldIndex)
                ))
                requestMorto()
                return
            }
            if (networkRepository.isOnlineTransport) {
                // Mesmo padrao do cliente acima: aplica um estado otimista,
                // publica o MELD e completa a entrega do morto de forma
                // assincrona (applyServerMortoResultForOwnSeat) quando a RPC
                // online_take_morto responder -- e o banco, nao o host, quem
                // decide o conteudo real do morto.
                _gameState.value = state.copy(
                    myHand = finalHand,
                    myTableMelds = updatedMelds,
                    discardPile = discardPileAfterMeld,
                    selectedCards = emptySet(),
                    mortosLeft = mortosLeft,
                    lastMeldResult = meldLabel,
                    feedbackMessage = "$meldLabel Pegando o Morto...",
                    pendingMeldTargets = null
                )
                networkRepository.sendMessage(NetworkMessage(
                    playerId, "MELD", buildMeldPayload(publicMeld, localSeat, replaceMeldIndex)
                ))
                networkRepository.requestServerMorto(localSeat) { rawJson ->
                    viewModelScope.launch { applyServerMortoResultForOwnSeat(rawJson, meldLabel) }
                }
                return
            }
            if (mortos.isNotEmpty()) {
                val preparedMorto = prepareMortoForPickup(mortos.removeAt(0))
                teamsThatPickedMorto.add(teamForSeat(localSeat))
                finalHand = preparedMorto.hand
                updatedMelds = updatedMelds + preparedMorto.redThreeMelds
                mortosLeft = mortos.size
                pickedMortoSeat = localSeat
                feedback = "$meldLabel Você pegou o Morto!"
                networkRepository.sendMessage(NetworkMessage(playerId, "PICK_MORTO", buildMortosLeftPayload(mortosLeft, localSeat)))
            }
        } else if (finalHand.isEmpty()) {
            // Mão vazia e sem morto para pegar: verificar vitória ou contagem
            networkRepository.sendMessage(
                NetworkMessage(playerId, "MELD", buildMeldPayload(publicMeld, localSeat, replaceMeldIndex))
            )
            if (currentConfig.gameType == GameType.CACHETA) {
                // Cacheta: mão vazia após baixar = bateu
                _gameState.value = state.copy(
                    myHand = finalHand,
                    myTableMelds = updatedMelds,
                    discardPile = discardPileAfterMeld,
                    selectedCards = emptySet(),
                    mortosLeft = mortosLeft,
                    lastMeldResult = "🏆 Bateu!",
                    feedbackMessage = "🏆 Você bateu a Cacheta!",
                    pendingMeldTargets = null
                )
                triggerWinFlow(finalHand)
            } else {
                // Buraco/Tranca: verificar condições de vitória ou encerrar por contagem
                val winCheck = GameRulesEngine.canDeclareWin(
                    hand = finalHand,
                    tableMelds = updatedMelds,
                    hasMorto = !teamsThatPickedMorto.contains(teamForSeat(localSeat)),
                    config = currentConfig
                )
                if (winCheck.canWin) {
                    _gameState.value = state.copy(
                        myHand = finalHand,
                        myTableMelds = updatedMelds,
                        discardPile = discardPileAfterMeld,
                        selectedCards = emptySet(),
                        mortosLeft = mortosLeft,
                        lastMeldResult = "🏆 Bateu!",
                        feedbackMessage = "🏆 Você bateu!",
                        pendingMeldTargets = null
                    )
                    triggerWinFlow(finalHand)
                } else {
                    // Sem condições de bater, mas mão vazia: encerrar por contagem
                    _gameState.value = state.copy(
                        myHand = finalHand,
                        myTableMelds = updatedMelds,
                        discardPile = discardPileAfterMeld,
                        selectedCards = emptySet(),
                        mortosLeft = mortosLeft,
                        lastMeldResult = meldLabel,
                        feedbackMessage = "Mão vazia. Encerrando rodada por contagem...",
                        pendingMeldTargets = null
                    )
                    if (isHost) beginCountOnlyRound()
                }
            }
            return
        }

        _gameState.value = state.copy(
            myHand = finalHand,
            myTableMelds = updatedMelds,
            discardPile = discardPileAfterMeld,
            selectedCards = emptySet(),
            deckSize = masterDeck.size,
            mortosLeft = mortosLeft,
            lastMeldResult = meldLabel,
            feedbackMessage = feedback,
            pendingMeldTargets = null,
            mortoNoticeSeat = pickedMortoSeat ?: state.mortoNoticeSeat,
            mortoNoticeId = if (pickedMortoSeat != null) state.mortoNoticeId + 1 else state.mortoNoticeId
        )
        networkRepository.sendMessage(NetworkMessage(
            playerId, "MELD", buildMeldPayload(publicMeld, localSeat, replaceMeldIndex)
        ))
        publishPublicTableState()
    }

    /** Descarta a carta e fecha o turno, incluindo bater ou pegar morto quando a mão zera. */
    fun discardCard(card: Card) {
        val state = _gameState.value
        if (state.turnPhase != TurnPhase.ACTION) return
        if (pendingDiscardDrawCardId != null && state.myHand.any { it.id == pendingDiscardDrawCardId }) {
            _gameState.value = state.copy(feedbackMessage = "Baixe ou encaixe a carta comprada do lixo antes de descartar.")
            return
        }
        if (!canDiscardWildcardNow(card, state)) {
            _gameState.value = state.copy(
                feedbackMessage = "Use esse coringa/2 em um jogo antes de descartar. Só descarte se não houver outra carta ou se ele não encaixar."
            )
            return
        }

        val updatedHand = state.myHand.filter { it != card }
        val updatedPile = state.discardPile + card
        val nextSeat = nextSeatAfter(localSeat)

        // Verifica condição de bater
        val winCheck = GameRulesEngine.canDeclareWin(
            hand = updatedHand,
            tableMelds = state.myTableMelds,
            hasMorto = !teamsThatPickedMorto.contains(teamForSeat(localSeat)),
            config = currentConfig
        )

        var mortosLeft = state.mortosLeft
        var finalHand = updatedHand
        var finalTableMelds = state.myTableMelds
        var feedback = "Turno do oponente."
        var pickedMortoSeat: Int? = null

        if (winCheck.canWin) {
            _gameState.value = state.copy(
                myHand = updatedHand,
                discardPile = updatedPile,
                selectedCards = emptySet(),
                mortosLeft = mortosLeft,
                activeSeat = localSeat,
                turnPhase = TurnPhase.ACTION,
                lastDrawnCardId = null,
                feedbackMessage = "🏆 Você ganhou a rodada!"
            )
            networkRepository.sendMessage(NetworkMessage(playerId, "DISCARD", buildDiscardPayload(card, localSeat)))
            triggerWinFlow(updatedHand)
            return
        } else if (finalHand.isEmpty() && state.mortosLeft > 0 && !teamsThatPickedMorto.contains(teamForSeat(localSeat))) {
            if (!isHost) {
                val discardLocked = GameRulesEngine.isDiscardLocked(updatedPile.lastOrNull(), currentConfig.gameType)
                val drawCheck = GameRulesEngine.canDrawFromDiscard(updatedPile.lastOrNull(), currentConfig)
                pendingMortoPickupIsIndirect = true
                _gameState.value = state.copy(
                    myHand = finalHand,
                    discardPile = updatedPile,
                    selectedCards = emptySet(),
                    mortosLeft = mortosLeft,
                    activeSeat = localSeat,
                    turnPhase = TurnPhase.ACTION,
                    feedbackMessage = "Solicitando o Morto...",
                    isDiscardLocked = discardLocked,
                    canDrawFromDiscard = drawCheck.allowed,
                    drawDiscardBlockedReason = drawCheck.reason
                )
                networkRepository.sendMessage(NetworkMessage(playerId, "DISCARD", buildDiscardPayload(card, localSeat)))
                requestMorto()
                return
            }
            if (mortos.isNotEmpty()) {
                val preparedMorto = prepareMortoForPickup(mortos.removeAt(0))
                teamsThatPickedMorto.add(teamForSeat(localSeat))
                finalHand = preparedMorto.hand
                finalTableMelds = finalTableMelds + preparedMorto.redThreeMelds
                mortosLeft = mortos.size
                feedback = "Você pegou o Morto!"
                networkRepository.sendMessage(NetworkMessage(playerId, "PICK_MORTO", buildMortosLeftPayload(mortosLeft, localSeat)))
            }
        } else if (finalHand.isEmpty()) {
            networkRepository.sendMessage(NetworkMessage(playerId, "DISCARD", buildDiscardPayload(card, localSeat)))
            _gameState.value = state.copy(
                myHand = finalHand,
                discardPile = updatedPile,
                selectedCards = emptySet(),
                mortosLeft = mortosLeft,
                lastDrawnCardId = null,
                feedbackMessage = "Mão vazia. Encerrando rodada..."
            )
            if (isHost) {
                beginCountOnlyRound()
            }
            return
        }

        val newTopDiscard = updatedPile.lastOrNull()
        val discardLocked = GameRulesEngine.isDiscardLocked(newTopDiscard, currentConfig.gameType)
        val drawCheck = GameRulesEngine.canDrawFromDiscard(newTopDiscard, currentConfig)
        deckServedSeatsThisTurn.clear()

        _gameState.value = state.copy(
            myHand = finalHand,
            myTableMelds = finalTableMelds,
            discardPile = updatedPile,
            selectedCards = emptySet(),
            deckSize = masterDeck.size,
            mortosLeft = mortosLeft,
            activeSeat = nextSeat,
            turnPhase = TurnPhase.WAITING_OPPONENT,
            lastDrawnCardId = null,
            feedbackMessage = feedback,
            isDiscardLocked = discardLocked,
            canDrawFromDiscard = drawCheck.allowed,
            drawDiscardBlockedReason = drawCheck.reason,
            mortoNoticeSeat = pickedMortoSeat ?: state.mortoNoticeSeat,
            mortoNoticeId = if (pickedMortoSeat != null) state.mortoNoticeId + 1 else state.mortoNoticeId
        )

        networkRepository.sendMessage(NetworkMessage(playerId, "DISCARD", buildDiscardPayload(card, localSeat)))
        publishPublicTableState()

        if (isHost && masterDeck.isEmpty() && mortos.isEmpty() && currentConfig.gameType != GameType.CACHETA) {
            beginCountOnlyRound()
        }
    }

    /**
     * Limpa a mensagem de feedback exibida na interface.
     */
    fun clearMeldFeedback() {
        _gameState.value = _gameState.value.copy(lastMeldResult = "")
    }

    // --- Fluxo de pontuação ---

    /**
     * Dispara o fechamento da rodada quando o jogador local bate.
     */
    private fun triggerWinFlow(myRemainingHand: List<Card>) {
        val state = _gameState.value
        val report = RoundSeatReport(
            playerId = playerId,
            seat = localSeat,
            hand = myRemainingHand,
            tableMelds = state.myTableMelds
        )
        val payload = buildRoundReportPayload(report, winnerId = playerId)

        if (isHost) {
            beginPendingRoundSummary(winnerId = playerId, winnerReport = report)
        }

        networkRepository.sendMessage(NetworkMessage(playerId, "WIN_ROUND", payload))
    }

    private fun handleOpponentWinRound(message: NetworkMessage) {
        if (isHost && (_gameState.value.showRoundEndDialog || pendingWinnerId != null)) return
        val receivedReport = parseRoundReportPayload(message.payload) ?: return
        val winnerReport = if (isHost) {
            val actorSeat = trustedRemoteSeat(message, receivedReport.seat) ?: return
            val canonicalReport = RoundSeatReport(
                playerId = message.senderId,
                seat = actorSeat,
                hand = remoteHandsBySeat[actorSeat].orEmpty(),
                tableMelds = remoteTableForSeat(actorSeat)
            )
            val canWin = GameRulesEngine.canDeclareWin(
                hand = canonicalReport.hand,
                tableMelds = canonicalReport.tableMelds,
                hasMorto = currentConfig.gameType != GameType.CACHETA &&
                    !teamsThatPickedMorto.contains(teamForSeat(actorSeat)),
                config = currentConfig
            )
            if (!canWin.canWin) {
                rejectRemoteAction("Vitoria recusada: ${canWin.reason.ifBlank { "a mao ainda nao terminou" }}.")
                return
            }
            canonicalReport
        } else {
            if (message.senderSeat != null && message.senderSeat != 0) return
            receivedReport
        }
        if (isHost) {
            val winnerId = winnerReport.playerId
            beginPendingRoundSummary(winnerId = winnerId, winnerReport = winnerReport)
            if (currentConfig.maxPlayers > 2) {
                networkRepository.sendMessage(NetworkMessage(playerId, "COUNT_ROUND", ""))
            }
            return
        }

        val state = _gameState.value
        val myReport = RoundSeatReport(
            playerId = playerId,
            seat = localSeat,
            hand = state.myHand,
            tableMelds = state.myTableMelds
        )
        networkRepository.sendMessage(NetworkMessage(playerId, "REPLY_WIN_ROUND", buildRoundReportPayload(myReport)))
    }

    private fun handleReplyWinRound(message: NetworkMessage) {
        if (!isHost) return

        val received = parseRoundReportPayload(message.payload) ?: return
        val actorSeat = trustedRemoteSeat(message, received.seat) ?: return
        pendingRoundReports[actorSeat] = RoundSeatReport(
            playerId = message.senderId,
            seat = actorSeat,
            hand = remoteHandsBySeat[actorSeat].orEmpty(),
            tableMelds = remoteTableForSeat(actorSeat)
        )
        tryFinalizePendingRoundSummary()
    }

    private fun handleCountRoundRequest() {
        val state = _gameState.value
        val report = RoundSeatReport(
            playerId = playerId,
            seat = localSeat,
            hand = state.myHand,
            tableMelds = state.myTableMelds
        )
        networkRepository.sendMessage(NetworkMessage(playerId, "REPLY_COUNT_ROUND", buildRoundReportPayload(report)))
        _gameState.value = state.copy(feedbackMessage = "Rodada encerrada por falta de cartas. Aguardando contagem...")
    }

    private fun handleReplyCountRound(message: NetworkMessage) {
        if (!isHost) return

        val received = parseRoundReportPayload(message.payload) ?: return
        val actorSeat = trustedRemoteSeat(message, received.seat) ?: return
        pendingRoundReports[actorSeat] = RoundSeatReport(
            playerId = message.senderId,
            seat = actorSeat,
            hand = remoteHandsBySeat[actorSeat].orEmpty(),
            tableMelds = remoteTableForSeat(actorSeat)
        )
        tryFinalizePendingRoundSummary()
    }

    private fun beginPendingRoundSummary(winnerId: String, winnerReport: RoundSeatReport) {
        pendingRoundReports.clear()
        pendingWinnerId = winnerId
        pendingWinnerTeam = teamForSeat(winnerReport.seat)
        pendingCountOnlyRound = false
        pendingRoundReports[winnerReport.seat] = winnerReport

        if (winnerReport.seat != localSeat) {
            val state = _gameState.value
            pendingRoundReports[localSeat] = RoundSeatReport(
                playerId = playerId,
                seat = localSeat,
                hand = state.myHand,
                tableMelds = state.myTableMelds
            )
        }

        tryFinalizePendingRoundSummary()
    }

    private fun beginCountOnlyRound() {
        if (!isHost) return
        val state = _gameState.value
        pendingRoundReports.clear()
        pendingWinnerId = ""
        pendingWinnerTeam = 0
        pendingCountOnlyRound = true
        pendingRoundReports[localSeat] = RoundSeatReport(
            playerId = playerId,
            seat = localSeat,
            hand = state.myHand,
            tableMelds = state.myTableMelds
        )
        _gameState.value = state.copy(
            deckSize = 0,
            feedbackMessage = "Monte e mortos acabaram. Encerrando por contagem..."
        )
        networkRepository.sendMessage(NetworkMessage(playerId, "COUNT_ROUND", ""))
        tryFinalizePendingRoundSummary()
    }

    private fun tryFinalizePendingRoundSummary() {
        val winnerId = pendingWinnerId ?: return
        val winnerTeam = pendingWinnerTeam ?: return
        val countOnlyRound = pendingCountOnlyRound
        if (pendingRoundReports.size < currentConfig.maxPlayers) return

        val state = _gameState.value
        val reports = pendingRoundReports.values.sortedBy { it.seat }
        val teamRoundScores = MutableList(2) { 0 }
        val breakdownLines = mutableListOf<String>()

        if (currentConfig.gameType == GameType.CACHETA) {
            // Em modo de dupla a mesa da equipe e compartilhada (remoteTableForSeat retorna
            // o mesmo myTableMelds/opponentTableMelds para os dois parceiros), entao somar o
            // relatorio de cada assento contaria a mesma baixa duas vezes. Precisa deduplicar
            // por carta antes de contar, igual ja e feito no ramo Buraco/Tranca abaixo.
            val winnerCardCount = uniqueTableMeldsForTeam(reports, winnerTeam).sumOf { it.size }
            val lostLives = if (winnerCardCount >= 10) 2 else 1
            teamRoundScores[opposingTeam(winnerTeam)] = -lostLives
            breakdownLines += "Equipe [TEAM_${opposingTeam(winnerTeam)}] perdeu $lostLives vida(s)."
        } else {
            breakdownLines += if (currentConfig.uniformCardPoints) {
                "Pontuação das cartas: uniforme, 10 pontos por carta."
            } else {
                "Pontuação das cartas: por valor da carta."
            }
            repeat(teamRoundScores.size) { team ->
                val teamMelds = uniqueTableMeldsForTeam(reports, team)
                val tableScore = GameRulesEngine.calculateBuracoTrancaScore(
                    hand = emptyList(),
                    tableMelds = teamMelds,
                    hasMorto = false,
                    didWin = false,
                    gameType = currentConfig.gameType,
                    uniformCardPoints = currentConfig.uniformCardPoints,
                    penalizeBlackThreesInHand = currentConfig.penalizeBlackThreesInHand
                )
                teamRoundScores[team] += tableScore.totalRoundPoints
                breakdownLines += "Equipe [TEAM_${team}]: mesa ${tableScore.tableCardCount} carta(s) = ${tableScore.tablePoints} pts"
                breakdownLines += "Equipe [TEAM_${team}]: canastras limpas ${tableScore.cleanCanastras}, sujas ${tableScore.dirtyCanastras} = +${tableScore.canastraPoints} pts"
                if (currentConfig.gameType == GameType.TRANCA) {
                    breakdownLines += "Equipe [TEAM_${team}]: 3 vermelhos ${tableScore.redThreesOnTable} = ${tableScore.redThreePoints} pts"
                }
            }
            reports.forEach { report ->
                val team = teamForSeat(report.seat)
                val handScore = GameRulesEngine.calculateBuracoTrancaScore(
                    hand = report.hand,
                    tableMelds = emptyList(),
                    hasMorto = false,
                    didWin = false,
                    gameType = currentConfig.gameType,
                    uniformCardPoints = currentConfig.uniformCardPoints,
                    penalizeBlackThreesInHand = currentConfig.penalizeBlackThreesInHand
                )
                teamRoundScores[team] += handScore.totalRoundPoints
                val specialHandCards = handScore.redThreesInHand + handScore.blackThreesInHand
                if (currentConfig.gameType == GameType.TRANCA && specialHandCards > 0) {
                    val regularCards = (handScore.handCardCount - handScore.blackThreesInHand).coerceAtLeast(0)
                    val regularPenalty = (
                        handScore.handPenalty -
                            handScore.redThreeHandPenalty -
                            handScore.blackThreePenalty
                        ).coerceAtLeast(0)
                    breakdownLines += "Jogador [PLAYER_${report.seat}]: mão comum $regularCards carta(s) = -$regularPenalty pts"
                } else {
                    breakdownLines += "Jogador [PLAYER_${report.seat}]: mão ${handScore.handCardCount} carta(s) = -${handScore.handPenalty} pts"
                }
                if (currentConfig.gameType == GameType.TRANCA && handScore.redThreesInHand > 0) {
                    breakdownLines += "Jogador [PLAYER_${report.seat}]: 3 vermelhos na mão ${handScore.redThreesInHand} = -${handScore.redThreeHandPenalty} pts"
                }
                if (currentConfig.gameType == GameType.TRANCA && handScore.blackThreesInHand > 0) {
                    breakdownLines += "Jogador [PLAYER_${report.seat}]: 3 pretos na mão ${handScore.blackThreesInHand} = -${handScore.blackThreePenalty} pts (${if (currentConfig.penalizeBlackThreesInHand) "regra pesada ligada" else "regra pesada desligada"})"
                }
            }
            repeat(teamRoundScores.size) { team ->
                if (team !in teamsThatPickedMorto) {
                    teamRoundScores[team] -= 100
                    breakdownLines += "Equipe [TEAM_${team}]: morto não pego -100"
                }
            }
            if (countOnlyRound) {
                breakdownLines += "Rodada encerrada por contagem, sem bonus de bate."
            } else {
                teamRoundScores[winnerTeam] += 100
                breakdownLines += "Equipe [TEAM_${winnerTeam}]: bonus de bate +100"
            }
            repeat(teamRoundScores.size) { team ->
                breakdownLines += "Equipe [TEAM_${team}]: total da rodada ${teamRoundScores[team]} pts"
            }
        }

        val loserTeam = opposingTeam(winnerTeam)
        val myRoundScore = teamRoundScores[teamForSeat(localSeat)]
        val opponentRoundScore = teamRoundScores[opposingTeam(teamForSeat(localSeat))]
        val updatedTeamScores = if (countOnlyRound) {
            applyCountRoundToTeamScores(state.teamScores, teamRoundScores)
        } else {
            applyRoundToTeamScores(
                currentScores = state.teamScores,
                winnerTeam = winnerTeam,
                winnerRoundScore = teamRoundScores[winnerTeam],
                loserTeam = loserTeam,
                loserRoundScore = teamRoundScores[loserTeam]
            )
        }
        val myNew = updatedTeamScores[teamForSeat(localSeat)]
        val oppNew = updatedTeamScores[opposingTeam(teamForSeat(localSeat))]
        val over = isMatchOver(updatedTeamScores)
        val breakdown = breakdownLines.joinToString("\n")

        networkRepository.sendMessage(NetworkMessage(playerId, "ROUND_SUMMARY", buildRoundSummaryPayload(
            winnerId = winnerId,
            winnerRoundScore = teamRoundScores[winnerTeam],
            loserRoundScore = teamRoundScores[loserTeam],
            winnerTotal = updatedTeamScores[winnerTeam],
            loserTotal = updatedTeamScores[loserTeam],
            isMatchOver = over,
            breakdown = breakdown,
            teamScores = updatedTeamScores,
            winnerTeam = winnerTeam,
            noWinner = countOnlyRound,
            teamRoundScores = teamRoundScores
        )))
        _gameState.value = state.copy(
            myScore = myNew,
            opponentScore = oppNew,
            teamScores = updatedTeamScores,
            showRoundEndDialog = true,
            roundEndDetails = run {
                val localTeamIdx = teamForSeat(localSeat)
                // Substitui placeholders do breakdown para exibição no host
                val formattedBreakdown = formatBreakdownForLocal(breakdown, localTeamIdx)
                RoundEndDetails(
                    winnerName = when {
                        countOnlyRound -> "Contagem"
                        winnerId == playerId -> "Você"
                        localTeamIdx == winnerTeam -> "Sua equipe"
                        else -> opponentDisplayLabel()
                    },
                    myRoundScore = myRoundScore,
                    opponentRoundScore = opponentRoundScore,
                    myNewTotal = myNew,
                    opponentNewTotal = oppNew,
                    isMatchOver = over,
                    breakdown = formattedBreakdown,
                    teamScores = updatedTeamScores,
                    winnerTeam = winnerTeam,
                    localTeam = localTeamIdx,
                    opponentLabel = opponentDisplayLabel()
                )
            }
        )

        pendingRoundReports.clear()
        pendingWinnerId = null
        pendingWinnerTeam = null
        pendingCountOnlyRound = false
        pendingDiscardDrawCardId = null
        pendingDiscardDrawRest = emptyList()
    }

    private fun uniqueTableMeldsForTeam(
        reports: List<RoundSeatReport>,
        team: Int
    ): List<List<Card>> {
        val uniqueMelds = linkedMapOf<String, List<Card>>()
        reports
            .filter { teamForSeat(it.seat) == team }
            .flatMap { it.tableMelds }
            .forEach { meld ->
                val key = meld.map { it.id }.sorted().joinToString("|")
                uniqueMelds.putIfAbsent(key, meld)
            }
        return uniqueMelds.values.toList()
    }

    private fun handleRoundSummary(payload: String) {
        if (!payload.trim().startsWith("{")) return
        handleRoundSummaryJson(payload)
    }

    /**
     * Inicia uma nova rodada mantendo as pontuações acumuladas.
     * O host redistribui as cartas; os pontos do estado atual são preservados
     * dentro de startGame() via _gameState.value.teamScores.
     */
    fun nextRound() {
        _gameState.value = clearRoundEndUiState()
        if (isHost) {
            publishNextRoundAndStart(resetScores = false)
        } else {
            networkRepository.sendMessage(NetworkMessage(playerId, "REQ_NEXT_ROUND", ""))
        }
    }

    /**
     * Uma nova distribuicao so comeca depois que o transporte confirmou o encerramento.
     * No online isso garante a ordem no banco; no Wi-Fi e na maquina o contrato responde na hora.
     */
    private fun publishNextRoundAndStart(resetScores: Boolean) {
        val nextRound = NetworkMessage(playerId, "NEXT_ROUND", "")
        networkRepository.sendMessageConfirmed(nextRound) { delivered ->
            viewModelScope.launch {
                if (!delivered) {
                    _gameState.value = _gameState.value.copy(
                        feedbackMessage = "Não foi possível preparar a nova rodada. Tente novamente."
                    )
                    return@launch
                }
                if (resetScores) {
                    val scores = initialTeamScores(currentConfig)
                    _gameState.value = _gameState.value.copy(
                        teamScores = scores,
                        myScore = scores.getOrElse(teamForSeat(localSeat)) { 0 },
                        opponentScore = scores.getOrElse(opposingTeam(teamForSeat(localSeat))) { 0 },
                        showRestartMatchDialog = false
                    )
                }
                startGame()
            }
        }
    }

    private val restartMatchApprovals = mutableSetOf<String>()

    /**
     * Solicita o reinício completo da partida (zerando pontuações).
     */
    fun requestRestartMatch() {
        if (isHost) {
            restartMatchApprovals.clear()
                restartMatchApprovals.add("seat:0")
            networkRepository.sendMessage(NetworkMessage(playerId, "RESTART_MATCH", ""))
            _gameState.value = _gameState.value.copy(feedbackMessage = "Aguardando confirmação para reiniciar a partida...")
        } else {
            networkRepository.sendMessage(NetworkMessage(playerId, "REPLY_RESTART", "YES"))
            _gameState.value = _gameState.value.copy(showRestartMatchDialog = false, feedbackMessage = "Aguardando host reiniciar a partida...")
        }
    }
    
    /**
     * Recusa o pedido de reinício da partida, voltando ao estado anterior.
     */
    fun declineRestartMatch() {
        _gameState.value = _gameState.value.copy(showRestartMatchDialog = false)
        networkRepository.sendMessage(NetworkMessage(playerId, "REPLY_RESTART", "NO"))
    }

    private fun handleReplyRestartMatch(message: NetworkMessage) {
        if (!isHost) return
        val senderSeat = authenticatedRemoteSeat(message) ?: return
        val payload = message.payload
        if (payload == "NO") {
            _gameState.value = _gameState.value.copy(feedbackMessage = "Um jogador recusou reiniciar a partida.")
            restartMatchApprovals.clear()
            // RESTART_MATCH com CANCEL fecha somente o pedido; NEXT_ROUND limpa uma rodada de verdade.
            networkRepository.sendMessage(NetworkMessage(playerId, "RESTART_MATCH", "CANCEL"))
            return
        }
        if (payload == "YES") {
            restartMatchApprovals.add("seat:$senderSeat")
            // Contamos o próprio host + aprovações dos clientes
            val approvalNeeded = currentConfig.maxPlayers
            if (restartMatchApprovals.size >= approvalNeeded) {
                restartMatchApprovals.clear()
                publishNextRoundAndStart(resetScores = true)
            }
        }
    }

    // --- Mensagens de rede ---

    /**
     * Entrada única das mensagens da mesa.
     */
    private fun handleNetworkMessage(message: NetworkMessage) {
        if (message.senderId == playerId) return
        // Deduplicação por messageId: essencial para rede real, reconexão e online,
        // onde a mesma ação pode chegar repetida por retry ou atraso.
        if (!processedNetworkMessageIds.add(message.messageId)) return
        if (processedNetworkMessageIds.size > 500) {
            val iterator = processedNetworkMessageIds.iterator()
            repeat(100) {
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }
        try {
            when (message.type) {
                "GAME_START"         -> if (!isHost && isHostOrigin(message)) handleGameStart(message.payload)
                "CLIENT_READY"       -> if (isHost) handleClientReady(message)
                "PUBLIC_STATE"       -> if (!isHost && isHostOrigin(message)) handlePublicTableState(message.payload)
                "DISCARD"            -> if (isHost) handleRemoteDiscard(message) else if (isHostOrigin(message)) handleOpponentDiscard(message.payload)
                "DRAW_DECK"          -> if (!isHost && isHostOrigin(message)) handleOpponentDrewDeck(message.senderId)
                "DRAW_DISCARD"       -> if (isHost) handleRemoteDiscardDraw(message) else if (isHostOrigin(message)) handleOpponentDrewDiscard(message.senderId, message.payload)
                "REQ_DRAW_DECK"      -> if (isHost) handleClientDrawRequest(message)
                "SERVE_CARD"         -> if (!isHost && isHostOrigin(message)) handleServeCard(message.payload)
                "REQ_PICK_MORTO"     -> if (isHost) handleClientMortoRequest(message)
                "SERVE_MORTO"        -> if (!isHost && isHostOrigin(message)) handleServeMorto(message.payload)
                "MELD"               -> if (isHost) handleRemoteMeld(message) else if (isHostOrigin(message)) handleOpponentMeld(message.payload)
                "PICK_MORTO"         -> if (!isHost && isHostOrigin(message)) handleOpponentPickMorto(message.payload)
                "WIN_ROUND"          -> handleOpponentWinRound(message)
                "REPLY_WIN_ROUND"    -> if (isHost) handleReplyWinRound(message)
                "COUNT_ROUND"        -> if (!isHost && isHostOrigin(message)) handleCountRoundRequest()
                "REPLY_COUNT_ROUND"  -> if (isHost) handleReplyCountRound(message)
                "ROUND_SUMMARY"      -> if (!isHost && isHostOrigin(message)) handleRoundSummary(message.payload)
                "NEXT_ROUND"         -> if (!isHost && isHostOrigin(message)) _gameState.value = clearRoundEndUiState().copy(
                    myHand = emptyList(),
                    selectedCards = emptySet(),
                    myTableMelds = emptyList(),
                    opponentTableMelds = emptyList(),
                    discardPile = emptyList(),
                    turnCard = null,
                    pendingMeldTargets = null,
                    lastDrawnCardId = null,
                    opponentPickedMorto = false,
                    mortoNoticeSeat = null,
                    feedbackMessage = "Preparando nova rodada..."
                )
                "REQ_NEXT_ROUND"     -> {
                    if (isHost && _gameState.value.showRoundEndDialog &&
                        message.senderSeat?.let { it in 1 until currentConfig.maxPlayers } == true
                    ) {
                        publishNextRoundAndStart(resetScores = false)
                    }
                }
                "RESTART_MATCH"      -> if (!isHost && isHostOrigin(message)) {
                    val cancelled = message.payload == "CANCEL"
                    _gameState.value = _gameState.value.copy(
                        showRestartMatchDialog = !cancelled,
                        feedbackMessage = if (cancelled) "O reinício da partida foi cancelado." else _gameState.value.feedbackMessage
                    )
                }
                "REPLY_RESTART"      -> if (isHost) handleReplyRestartMatch(message)
                "REQ_RECONNECT"      -> if (isHost) handleReconnectRequest(message)
                "RECONNECT_STATE"    -> if (!isHost && isHostOrigin(message)) handleReconnectState(message.payload)
            }
        } catch (_: Exception) {
            // JSON malformado nao derruba a partida; regras invalidas sao recusadas antes daqui.
        }
    }

    /**
     * Cliente aplica a distribuição enviada pelo host.
     */
    private fun handleGameStart(payload: String) {
        if (payload.trim().startsWith("{")) {
            handleGameStartJson(payload)
            return
        }

        val parts = payload.split("|")
        if (parts.size < 3) return

        // Detecta se o primeiro segmento é o config serializado (contém vírgulas)
        val hasConfig = parts[0].contains(",")
        val offset = if (hasConfig) 1 else 0
        if (hasConfig) {
            try { currentConfig = MatchConfig.deserialize(parts[0]) } catch (e: Exception) { /* mantém atual */ }
        }

        val myHandIds = parts[0 + offset].split(";").first().split(",")
        val discardId = parts[1 + offset]
        val deckSize = parts[2 + offset].toIntOrNull() ?: 0
        localSeat = 1

        val hand = sortHandIfEnabled(myHandIds.mapNotNull { allCardsMap[it] })
        val discard = allCardsMap[discardId]
        val turnCard = if (currentConfig.gameType == GameType.CACHETA) discard else null

        mortos.clear()
        if (parts.size >= 4 + offset && parts[3 + offset].isNotBlank()) {
            parts[3 + offset].split(";").forEach { mStr ->
                val m = mStr.split(",").mapNotNull { allCardsMap[it] }
                if (m.isNotEmpty()) mortos.add(m)
            }
        }
        val mortosLeft = mortos.size
        pendingDiscardDrawCardId = null
        pendingDiscardDrawRest = emptyList()

        val (processedHand, initialMelds) = autoProcessThreeReds(hand, emptyList())
        val discardLocked = GameRulesEngine.isDiscardLocked(discard, currentConfig.gameType)
        val drawCheck = GameRulesEngine.canDrawFromDiscard(discard, currentConfig)

        _gameState.value = _gameState.value.copy(
            myHand = processedHand,
            dealEventId = _gameState.value.dealEventId + 1,
            myTableMelds = initialMelds,
            opponentTableMelds = emptyList(),
            discardPile = if (discard != null) listOf(discard) else emptyList(),
            turnCard = turnCard,
            deckSize = deckSize,
            mortosLeft = mortosLeft,
            playerSeat = localSeat,
            activeSeat = 1,
            turnPhase = TurnPhase.DRAW,
            feedbackMessage = if (localSeat == 1) {
                "Sua vez! Compre do Monte ou do Lixo."
            } else {
                "Aguardando oponente..."
            },
            isDiscardLocked = discardLocked,
            canDrawFromDiscard = drawCheck.allowed,
            drawDiscardBlockedReason = drawCheck.reason,
            showRoundEndDialog = false,
            roundEndDetails = null,
            lastMeldResult = "",
            pendingMeldTargets = null,
            lastDrawnCardId = null,
            opponentPickedMorto = false,
            mortoNoticeSeat = null,
            config = currentConfig
        )
    }

    private fun handleGameStartJson(payload: String) {
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return
        currentRoundId = json.optString("roundId").takeIf { it.isNotBlank() }
        currentConfig = runCatching {
            MatchConfig.deserialize(json.optString("config", currentConfig.serialize()))
        }.getOrElse { currentConfig }

        val myHandJson = json.optJSONArray("hand")
            ?: json.optJSONArray("hands")?.optJSONArray(0)
            ?: JSONArray()
        val discardId = json.optString("discard", "")
        val turnCardId = json.optString("turnCard", "")
        val deckSize = json.optInt("deckSize", 0)
        localSeat = json.optInt("seat", 1).coerceIn(1, currentConfig.maxPlayers - 1)
        val activeSeat = json.optInt("activeSeat", 1).coerceIn(0, currentConfig.maxPlayers - 1)

        mortos.clear()
        teamsThatPickedMorto.clear()
        teamsWithMortoServedByHost.clear()
        val mortosJson = json.optJSONArray("mortos") ?: JSONArray()
        repeat(mortosJson.length()) { index ->
            val morto = cardsFromJson(mortosJson.optJSONArray(index) ?: JSONArray())
            if (morto.isNotEmpty()) mortos.add(morto)
        }
        val mortosLeft = if (mortos.isNotEmpty()) mortos.size else json.optInt("mortosLeft", 0)
        pendingDiscardDrawCardId = null
        pendingDiscardDrawRest = emptyList()

        val hand = sortHandIfEnabled(cardsFromJson(myHandJson))
        val discard = allCardsMap[discardId]
        val turnCard = allCardsMap[turnCardId]
        val (processedHand, initialMelds) = autoProcessThreeReds(hand, emptyList())
        val discardLocked = GameRulesEngine.isDiscardLocked(discard, currentConfig.gameType)
        val drawCheck = GameRulesEngine.canDrawFromDiscard(discard, currentConfig)

        _gameState.value = _gameState.value.copy(
            myHand = processedHand,
            dealEventId = _gameState.value.dealEventId + 1,
            selectedCards = emptySet(),
            discardPile = if (discard != null) listOf(discard) else emptyList(),
            turnCard = turnCard,
            myTableMelds = initialMelds,
            opponentTableMelds = emptyList(),
            opponentHandCount = currentConfig.cardsPerPlayer,
            deckSize = deckSize,
            mortosLeft = mortosLeft,
            playerSeat = localSeat,
            activeSeat = activeSeat,
            turnPhase = if (localSeat == activeSeat) TurnPhase.DRAW else TurnPhase.WAITING_OPPONENT,
            feedbackMessage = if (localSeat == activeSeat) {
                "Sua vez! Compre do Monte ou do Lixo."
            } else {
                "Aguardando oponente..."
            },
            lastMeldResult = "",
            isDiscardLocked = discardLocked,
            canDrawFromDiscard = drawCheck.allowed,
            drawDiscardBlockedReason = drawCheck.reason,
            showRoundEndDialog = false,
            roundEndDetails = null,
            pendingMeldTargets = null,
            lastDrawnCardId = null,
            opponentPickedMorto = false,
            mortoNoticeSeat = null,
            config = currentConfig
        )
    }

    /**
     * Processa o descarte feito por um oponente e atualiza a mesa.
     */
    private fun handleRemoteDiscard(message: NetworkMessage) {
        if (!isHost) return
        val (cardId, claimedSeat) = parseDiscardPayload(message.payload)
        val actorSeat = trustedRemoteSeat(message, claimedSeat) ?: return
        val state = _gameState.value
        val card = allCardsMap[cardId] ?: return rejectRemoteAction("Descarte recusado: carta desconhecida.")
        val remoteHand = remoteHandsBySeat[actorSeat].orEmpty()

        if (state.activeSeat != actorSeat) return rejectRemoteAction("Descarte recusado: nao e o turno desse jogador.")
        if (!hasRemoteDrawnThisTurn(actorSeat)) return rejectRemoteAction("Descarte recusado: o jogador ainda nao comprou.")
        if (pendingRemoteDiscardDraws.containsKey(actorSeat)) {
            return rejectRemoteAction("Descarte recusado: o topo do lixo ainda precisa ser usado.")
        }
        if (remoteHand.none { it.id == card.id }) return rejectRemoteAction("Descarte recusado: a carta nao esta na mao do jogador.")
        if (!canRemoteDiscardWildcard(card, remoteHand, remoteTableForSeat(actorSeat), state.turnCard)) {
            return rejectRemoteAction("Descarte recusado: o curinga ainda pode ser usado em jogo.")
        }

        handleOpponentDiscard(message.payload)
        resolveRemoteEmptyHand(actorSeat, message.senderId, indirectMorto = true)
    }

    private fun handleClientReady(message: NetworkMessage) {
        val seat = message.senderSeat?.takeIf { it in 1 until currentConfig.maxPlayers } ?: return
        rememberRemoteSeat(seat, message.senderId)
        readyRemoteSeats.add(seat)
        if (gameStartRequested && readyRemoteSeats.size >= currentConfig.maxPlayers - 1) {
            startGame()
        } else if (!gameStartRequested && seat !in confirmedGameStartSeats) {
            gameStartMessagesBySeat[seat]?.let { startMessage ->
                sendGameStartToSeat(seat, startMessage)
            }
        }
    }

    private fun sendGameStartToSeat(seat: Int, message: NetworkMessage) {
        if (!pendingGameStartSeats.add(seat)) return
        networkRepository.sendMessageToSeatConfirmed(seat, message) { delivered ->
            viewModelScope.launch {
                pendingGameStartSeats.remove(seat)
                if (delivered) {
                    confirmedGameStartSeats.add(seat)
                } else {
                    _gameState.value = _gameState.value.copy(
                        feedbackMessage = "Distribuição não entregue ao jogador ${seat + 1}. Aguardando reconexão..."
                    )
                }
            }
        }
    }

    /**
     * Fecha todos os caminhos em que um cliente zera a mão. O host decide em um
     * único lugar se entrega o morto, confirma a vitória ou encerra por contagem.
     */
    private fun resolveRemoteEmptyHand(
        actorSeat: Int,
        remotePlayerId: String,
        indirectMorto: Boolean
    ): Boolean {
        if (remoteHandCountForSeat(actorSeat) != 0) return false
        if (pendingWinnerId != null) return true

        val team = teamForSeat(actorSeat)
        val teamPickedMorto = team in teamsThatPickedMorto
        if (currentConfig.gameType != GameType.CACHETA && !teamPickedMorto && mortos.isNotEmpty()) {
            serveMortoToRemote(actorSeat, remotePlayerId, indirect = indirectMorto)
            return true
        }

        val actorMelds = remoteTableForSeat(actorSeat)
        val canWin = GameRulesEngine.canDeclareWin(
            hand = emptyList(),
            tableMelds = actorMelds,
            hasMorto = currentConfig.gameType != GameType.CACHETA && !teamPickedMorto,
            config = currentConfig
        ).canWin
        if (canWin) {
            beginPendingRoundSummary(
                winnerId = remotePlayerId,
                winnerReport = RoundSeatReport(remotePlayerId, actorSeat, emptyList(), actorMelds)
            )
            if (currentConfig.maxPlayers > 2) {
                networkRepository.sendMessage(NetworkMessage(playerId, "COUNT_ROUND", ""))
            }
        } else {
            beginCountOnlyRound()
        }
        return true
    }

    private fun handleRemoteDiscardDraw(message: NetworkMessage) {
        if (!isHost) return
        val state = _gameState.value
        val claimedSeat = message.senderSeat ?: remotePlayerSeats[message.senderId] ?: state.activeSeat
        val actorSeat = trustedRemoteSeat(message, claimedSeat) ?: return
        val topDiscard = state.discardPile.lastOrNull()
            ?: return rejectRemoteAction("Compra recusada: o lixo esta vazio.")
        val requestedCardId = parseCardId(message.payload)

        if (state.activeSeat != actorSeat) return rejectRemoteAction("Compra recusada: nao e o turno desse jogador.")
        if (hasRemoteDrawnThisTurn(actorSeat)) return rejectRemoteAction("Compra recusada: o jogador ja comprou neste turno.")
        if (requestedCardId.isNotBlank() && requestedCardId != topDiscard.id) {
            return rejectRemoteAction("Compra recusada: o topo do lixo mudou.")
        }

        val drawCheck = GameRulesEngine.canDrawFromDiscard(topDiscard, currentConfig)
        if (!drawCheck.allowed) return rejectRemoteAction(drawCheck.reason)

        val remoteHand = remoteHandsBySeat[actorSeat].orEmpty()
        val tableMelds = remoteTableForSeat(actorSeat)
        if (currentConfig.gameType != GameType.CACHETA && !GameRulesEngine.canJustifyDiscardDraw(
                topDiscard = topDiscard,
                hand = remoteHand,
                tableMelds = tableMelds,
                config = currentConfig,
                cachetaTurnCard = state.turnCard
            )
        ) {
            return rejectRemoteAction("Compra recusada: o topo do lixo nao forma nem completa um jogo.")
        }

        val restOfDiscard = state.discardPile.dropLast(1)
        addRemoteCards(actorSeat, listOf(topDiscard))
        deckServedSeatsThisTurn.add(actorSeat)
        if (currentConfig.gameType != GameType.CACHETA) {
            pendingRemoteDiscardDraws[actorSeat] = PendingRemoteDiscardDraw(topDiscard.id, restOfDiscard)
        }

        _gameState.value = state.copy(
            discardPile = if (currentConfig.gameType == GameType.CACHETA) emptyList() else restOfDiscard,
            opponentHandCount = remoteHandCountForSeat(actorSeat)
        )
        publishPublicTableState()
    }

    private fun handleRemoteMeld(message: NetworkMessage) {
        if (!isHost) return
        val (resultingMeld, claimedSeat) = parseMeldPayload(message.payload)
        val actorSeat = trustedRemoteSeat(message, claimedSeat) ?: return
        val replaceIndex = parseMeldReplaceIndex(message.payload)
        if (resultingMeld.isEmpty()) return rejectRemoteAction("Jogo recusado: nenhuma carta valida foi recebida.")

        val state = _gameState.value
        val currentTable = remoteTableForSeat(actorSeat)
        val existingMeld = currentTable.getOrNull(replaceIndex)
        val cardsFromHand = if (existingMeld != null) {
            subtractCards(resultingMeld, existingMeld)
                ?: return rejectRemoteAction("Jogo recusado: o encaixe alterou cartas que ja estavam na mesa.")
        } else {
            if (replaceIndex >= 0) return rejectRemoteAction("Jogo recusado: destino inexistente.")
            resultingMeld
        }
        if (cardsFromHand.isEmpty()) return rejectRemoteAction("Jogo recusado: nenhuma carta nova foi baixada.")

        val remoteHand = remoteHandsBySeat[actorSeat].orEmpty()
        if (!containsCards(remoteHand, cardsFromHand)) {
            return rejectRemoteAction("Jogo recusado: uma ou mais cartas nao pertencem ao jogador.")
        }

        val automaticRedThree = cardsFromHand.size == 1 && resultingMeld.size == 1 &&
            isTrancaRedThree(cardsFromHand.first())
        if (!automaticRedThree) {
            if (state.activeSeat != actorSeat) return rejectRemoteAction("Jogo recusado: nao e o turno desse jogador.")
            if (!hasRemoteDrawnThisTurn(actorSeat)) return rejectRemoteAction("Jogo recusado: o jogador ainda nao comprou.")
            val validation = GameRulesEngine.validateMeld(resultingMeld, currentConfig, state.turnCard)
            if (!validation.isValid) return rejectRemoteAction("Jogo recusado: ${validation.reason}")
        }

        val pendingDraw = pendingRemoteDiscardDraws[actorSeat]
        if (pendingDraw != null && cardsFromHand.none { it.id == pendingDraw.topCardId }) {
            return rejectRemoteAction("Jogo recusado: use primeiro a carta comprada do lixo.")
        }

        removeRemoteCards(actorSeat, cardsFromHand)
        val updatedTable = applyRemoteMeld(currentTable, resultingMeld, replaceIndex)
        if (teamForSeat(actorSeat) == teamForSeat(localSeat)) {
            _gameState.value = state.copy(myTableMelds = updatedTable)
        } else {
            _gameState.value = state.copy(opponentTableMelds = updatedTable)
        }

        if (pendingDraw != null) {
            addRemoteCards(actorSeat, pendingDraw.remainingPile)
            pendingRemoteDiscardDraws.remove(actorSeat)
            _gameState.value = _gameState.value.copy(discardPile = emptyList())
        }
        _gameState.value = _gameState.value.copy(opponentHandCount = remoteHandCountForSeat(actorSeat))

        if (resolveRemoteEmptyHand(actorSeat, message.senderId, indirectMorto = false)) {
            publishPublicTableState()
            return
        }
        publishPublicTableState()
    }

    private fun handleOpponentDiscard(cardId: String) {
        val (parsedCardId, actorSeat) = parseDiscardPayload(cardId)
        val card = allCardsMap[parsedCardId] ?: return
        rememberRemoteSeat(actorSeat, null)
        removeRemoteCards(actorSeat, listOf(card))
        val newPile = _gameState.value.discardPile + card
        val discardLocked = GameRulesEngine.isDiscardLocked(card, currentConfig.gameType)
        val drawCheck = GameRulesEngine.canDrawFromDiscard(card, currentConfig)
        val nextSeat = nextSeatAfter(actorSeat)
        val isMyTurn = localSeat == nextSeat
        deckServedSeatsThisTurn.clear()
        pendingRemoteDiscardDraws.remove(actorSeat)

        _gameState.value = _gameState.value.copy(
            discardPile = newPile,
            opponentHandCount = remoteHandCountForSeat(actorSeat),
            activeSeat = nextSeat,
            turnPhase = if (isMyTurn) TurnPhase.DRAW else TurnPhase.WAITING_OPPONENT,
            feedbackMessage = when {
                !isMyTurn -> "Turno do jogador ${nextSeat + 1}."
                discardLocked -> "🔒 Lixo trancado! Compre do Monte."
                else -> "Sua vez! Compre do Monte ou do Lixo."
            },
            isDiscardLocked = discardLocked,
            canDrawFromDiscard = drawCheck.allowed,
            drawDiscardBlockedReason = drawCheck.reason
        )
        if (isHost) publishPublicTableState()
    }

    private fun handleOpponentDrewDeck(senderId: String) {
        val state = _gameState.value
        val actorSeat = remotePlayerSeats[senderId] ?: state.activeSeat
        val dummyCard = Card(Suit.SPADES, Rank.ACE)
        addRemoteCards(actorSeat, listOf(dummyCard))
        
        _gameState.value = _gameState.value.copy(
            deckSize = (state.deckSize - 1).coerceAtLeast(0),
            opponentHandCount = remoteHandCountForSeat(actorSeat)
        )
        if (isHost) publishPublicTableState()
    }

    private fun handleOpponentDrewDiscard(senderId: String, cardId: String) {
        val actorSeat = remotePlayerSeats[senderId] ?: _gameState.value.activeSeat
        rememberRemoteSeat(actorSeat, senderId)
        val drawnCards = if (currentConfig.gameType == GameType.CACHETA) {
            _gameState.value.discardPile.takeLast(1)
        } else {
            _gameState.value.discardPile
        }
        addRemoteCards(actorSeat, drawnCards)
        val newPile = if (currentConfig.gameType == GameType.CACHETA) {
            _gameState.value.discardPile.dropLast(1)
        } else {
            emptyList()
        }
        _gameState.value = _gameState.value.copy(
            discardPile = newPile,
            opponentHandCount = remoteHandCountForSeat(actorSeat)
        )
        if (isHost) publishPublicTableState()
    }

    /**
     * Cliente recebe a carta servida pelo host depois da compra do monte.
     */
    private fun handleServeCard(cardId: String) {
        val card = allCardsMap[cardId] ?: return
        val state = _gameState.value
        pendingDiscardDrawCardId = null
        pendingDiscardDrawRest = emptyList()
        val newHand = sortHandIfEnabled(state.myHand + card)
        val (processedHand, newMelds) = autoProcessThreeReds(
            hand = newHand,
            tableMelds = state.myTableMelds,
            broadcastMelds = false
        )
        val droppedThree = processedHand.size < newHand.size
        _gameState.value = state.copy(
            myHand = processedHand,
            myTableMelds = newMelds,
            deckSize = (state.deckSize - 1).coerceAtLeast(0),
            turnPhase = if (droppedThree) TurnPhase.DRAW else TurnPhase.ACTION,
            lastDrawnCardId = card.id,
            feedbackMessage = if (droppedThree) "3 Vermelho! Você deve comprar novamente." else "Você comprou do Monte. Baixe ou Descarte."
        )
    }

    /** Host serve carta para o cliente quando ele pede (REQ_DRAW_DECK) */
    private fun handleClientDrawRequest(message: NetworkMessage) {
        if (!isHost) return
        val requestingSeat = trustedRemoteSeat(message, parseSeatPayload(message.payload)) ?: return
        if (requestingSeat != _gameState.value.activeSeat) return
        rememberRemoteSeat(requestingSeat, message.senderId)
        if (!deckServedSeatsThisTurn.add(requestingSeat)) return

        if (networkRepository.isOnlineTransport) {
            networkRepository.requestServerDraw(requestingSeat) { rawJson ->
                viewModelScope.launch { applyServerDrawResultForRemoteSeat(requestingSeat, rawJson) }
            }
            return
        }

        if (masterDeck.isEmpty() && !prepareDeckForDraw(_gameState.value)) {
            deckServedSeatsThisTurn.remove(requestingSeat)
            return
        }

        val card = drawTopCard() ?: run {
            deckServedSeatsThisTurn.remove(requestingSeat)
            return
        }
        val remoteHandBeforeDraw = remoteHandsBySeat[requestingSeat].orEmpty()
        val remoteTableBeforeDraw = remoteTableForSeat(requestingSeat)
        val autoMeldedRedThree = isTrancaRedThree(card)

        if (autoMeldedRedThree) {
            setRemoteTableForSeat(requestingSeat, remoteTableBeforeDraw + listOf(listOf(card)))
        } else {
            addRemoteCards(requestingSeat, listOf(card))
        }
        networkRepository.sendMessageToPlayerConfirmed(
            message.senderId,
            NetworkMessage(playerId, "SERVE_CARD", card.id)
        ) { delivered ->
            viewModelScope.launch {
                if (delivered) {
                    if (autoMeldedRedThree) {
                        deckServedSeatsThisTurn.remove(requestingSeat)
                    }
                    _gameState.value = _gameState.value.copy(
                        deckSize = masterDeck.size,
                        opponentHandCount = remoteHandCountForSeat(requestingSeat)
                    )
                } else {
                    remoteHandsBySeat[requestingSeat] = remoteHandBeforeDraw
                    setRemoteTableForSeat(requestingSeat, remoteTableBeforeDraw)
                    masterDeck.add(card)
                    deckServedSeatsThisTurn.remove(requestingSeat)
                    _gameState.value = _gameState.value.copy(
                        deckSize = masterDeck.size,
                        opponentHandCount = remoteHandCountForSeat(requestingSeat),
                        feedbackMessage = "Carta não entregue. O monte foi restaurado; aguardando reconexão."
                    )
                }
                publishPublicTableState()
            }
        }
    }

    /**
     * Aplica o resultado de `online_draw_deck_card` na compra de um cliente
     * remoto. O servidor ja publicou o SERVE_CARD direto pro assento certo e
     * o PUBLIC_STATE atualizado sozinho -- aqui o host so espelha o mesmo
     * efeito local que sempre teve (remoteHandsBySeat/mesa da equipe/tamanho
     * do monte), sem precisar mandar nenhuma mensagem de rede ele mesmo.
     */
    private fun applyServerDrawResultForRemoteSeat(requestingSeat: Int, rawJson: String?) {
        val json = rawJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (json == null) {
            deckServedSeatsThisTurn.remove(requestingSeat)
            _gameState.value = _gameState.value.copy(
                feedbackMessage = "Não foi possível servir a carta. Tente novamente."
            )
            return
        }
        when (json.optString("status")) {
            "EXHAUSTED" -> {
                deckServedSeatsThisTurn.remove(requestingSeat)
                beginCountOnlyRound()
            }
            "OK" -> {
                if (json.optBoolean("mortoConsumedForRefill", false) && mortos.isNotEmpty()) {
                    mortos.removeAt(0)
                }
                val card = allCardsMap[json.optString("card")]
                if (card == null) {
                    deckServedSeatsThisTurn.remove(requestingSeat)
                    return
                }
                if (isTrancaRedThree(card)) {
                    setRemoteTableForSeat(requestingSeat, remoteTableForSeat(requestingSeat) + listOf(listOf(card)))
                    deckServedSeatsThisTurn.remove(requestingSeat)
                } else {
                    addRemoteCards(requestingSeat, listOf(card))
                }
                _gameState.value = _gameState.value.copy(
                    deckSize = json.optInt("deckSize", _gameState.value.deckSize),
                    mortosLeft = json.optInt("mortosLeft", _gameState.value.mortosLeft),
                    discardPile = json.optJSONArray("discardPile")?.let { cardsFromJson(it) } ?: _gameState.value.discardPile,
                    opponentHandCount = remoteHandCountForSeat(requestingSeat)
                )
            }
            else -> {
                deckServedSeatsThisTurn.remove(requestingSeat)
                _gameState.value = _gameState.value.copy(
                    feedbackMessage = "Não foi possível servir a carta. Tente novamente."
                )
            }
        }
    }

    /** Host serve um morto privado para o jogador ativo quando ele fica sem cartas. */
    /**
     * Host valida e entrega o morto para o cliente que zerou a mão.
     */
    private fun handleClientMortoRequest(message: NetworkMessage) {
        if (!isHost || mortos.isEmpty()) return
        val requestingSeat = trustedRemoteSeat(message, parseSeatPayload(message.payload)) ?: return
        val indirect = runCatching { JSONObject(message.payload).optBoolean("indirect", false) }.getOrDefault(false)
        serveMortoToRemote(requestingSeat, message.senderId, indirect)
    }

    private fun serveMortoToRemote(requestingSeat: Int, remotePlayerId: String, indirect: Boolean): Boolean {
        if (!isHost || currentConfig.gameType == GameType.CACHETA || mortos.isEmpty()) return false
        val team = teamForSeat(requestingSeat)
        if (teamsThatPickedMorto.contains(team)) return false
        rememberRemoteSeat(requestingSeat, remotePlayerId)
        if (remoteHandCountForSeat(requestingSeat) != 0) {
            _gameState.value = _gameState.value.copy(
                feedbackMessage = "Pedido de morto recusado: a mão do jogador ainda não está vazia."
            )
            return false
        }
        if (!teamsWithMortoServedByHost.add(team)) return false

        if (networkRepository.isOnlineTransport) {
            // O servidor decide o conteudo do morto (RPC online_take_morto) e
            // ja publica SERVE_MORTO pro assento certo e o PUBLIC_STATE
            // atualizado sozinho -- o host so espelha o efeito local quando a
            // resposta chegar, sem mandar nenhuma mensagem de rede ele mesmo.
            // `indirect` so importa pro payload que o servidor manda pro
            // cliente (afeta se a vez passa pro proximo assento ou fica com
            // quem pegou o morto); a copia local do host nao usa esse flag.
            networkRepository.requestServerMorto(requestingSeat, indirect) { rawJson ->
                viewModelScope.launch { applyServerMortoResultForRemoteSeat(requestingSeat, team, rawJson) }
            }
            return true
        }

        val stateBeforePickup = _gameState.value
        val deckBeforePickup = masterDeck.toMutableList()
        val previousRemoteHand = remoteHandsBySeat[requestingSeat]
        val rawMorto = mortos.removeAt(0)
        if (rawMorto.size != currentConfig.cardsPerPlayer) {
            teamsWithMortoServedByHost.remove(team)
            mortos.add(0, rawMorto)
            _gameState.value = _gameState.value.copy(
                feedbackMessage = "Morto inválido (${rawMorto.size} cartas). Rodada protegida; reinicie a rodada."
            )
            return false
        }

        val preparedMorto = prepareMortoForPickup(rawMorto)
        if (preparedMorto.hand.size != currentConfig.cardsPerPlayer) {
            masterDeck = deckBeforePickup
            teamsWithMortoServedByHost.remove(team)
            mortos.add(0, rawMorto)
            _gameState.value = stateBeforePickup.copy(
                feedbackMessage = "Não foi possível repor os 3 vermelhos do morto. Rodada protegida."
            )
            return false
        }

        val sameTeamAsHost = team == teamForSeat(localSeat)
        val updatedMyTable = if (sameTeamAsHost) {
            stateBeforePickup.myTableMelds + preparedMorto.redThreeMelds
        } else {
            stateBeforePickup.myTableMelds
        }
        val updatedOpponentTable = if (sameTeamAsHost) {
            stateBeforePickup.opponentTableMelds
        } else {
            stateBeforePickup.opponentTableMelds + preparedMorto.redThreeMelds
        }

        teamsThatPickedMorto.add(team)
        val mortosLeft = mortos.size
        remoteHandsBySeat[requestingSeat] = preparedMorto.hand
        
        networkRepository.sendMessageToPlayerConfirmed(
            remotePlayerId,
            NetworkMessage(
                playerId,
                "SERVE_MORTO",
                buildServeMortoPayload(
                    preparedMorto.hand,
                    mortosLeft,
                    indirect,
                    stateBeforePickup.activeSeat
                )
            )
        ) { delivered ->
            viewModelScope.launch {
                if (!delivered) {
                    if (previousRemoteHand == null) {
                        remoteHandsBySeat.remove(requestingSeat)
                    } else {
                        remoteHandsBySeat[requestingSeat] = previousRemoteHand
                    }
                    masterDeck = deckBeforePickup
                    mortos.add(0, rawMorto)
                    teamsThatPickedMorto.remove(team)
                    teamsWithMortoServedByHost.remove(team)
                    _gameState.value = stateBeforePickup.copy(
                        feedbackMessage = "Morto não entregue: aguardando o jogador reconectar."
                    )
                    publishPublicTableState()
                    return@launch
                }

                networkRepository.sendMessage(
                    NetworkMessage(playerId, "PICK_MORTO", buildMortosLeftPayload(mortosLeft, requestingSeat))
                )
                _gameState.value = stateBeforePickup.copy(
                    myTableMelds = updatedMyTable,
                    opponentTableMelds = updatedOpponentTable,
                    deckSize = masterDeck.size,
                    mortosLeft = mortosLeft,
                    opponentHandCount = remoteHandCountForSeat(requestingSeat),
                    opponentPickedMorto = if (team != teamForSeat(localSeat)) {
                        true
                    } else {
                        stateBeforePickup.opponentPickedMorto
                    },
                    mortoNoticeSeat = requestingSeat,
                    mortoNoticeId = stateBeforePickup.mortoNoticeId + 1
                )
                publishPublicTableState()
            }
        }
        return true
    }

    /**
     * Aplica o resultado de `online_take_morto` no pedido de um cliente
     * remoto. O servidor ja publicou o SERVE_MORTO direto pro assento certo
     * e o PUBLIC_STATE atualizado sozinho -- aqui o host so espelha o mesmo
     * efeito local que sempre teve (remoteHandsBySeat/mesa das equipes),
     * sem mandar nenhuma mensagem de rede ele mesmo.
     */
    private fun applyServerMortoResultForRemoteSeat(requestingSeat: Int, team: Int, rawJson: String?) {
        val json = rawJson?.let { runCatching { JSONObject(it) }.getOrNull() }
        if (json == null || json.optString("status") != "OK") {
            teamsWithMortoServedByHost.remove(team)
            _gameState.value = _gameState.value.copy(
                feedbackMessage = "Não foi possível entregar o morto agora. Tente novamente."
            )
            return
        }
        val hand = cardsFromJson(json.optJSONArray("hand") ?: JSONArray())
        if (hand.size != currentConfig.cardsPerPlayer) {
            teamsWithMortoServedByHost.remove(team)
            _gameState.value = _gameState.value.copy(feedbackMessage = "Morto inválido recebido do servidor.")
            return
        }
        if (mortos.isNotEmpty()) mortos.removeAt(0)
        val redThreeMelds = handsFromJson(json.optJSONArray("redThreeMelds") ?: JSONArray())
        val sameTeamAsHost = team == teamForSeat(localSeat)
        val state = _gameState.value
        val updatedMyTable = if (sameTeamAsHost) state.myTableMelds + redThreeMelds else state.myTableMelds
        val updatedOpponentTable = if (sameTeamAsHost) state.opponentTableMelds else state.opponentTableMelds + redThreeMelds

        teamsThatPickedMorto.add(team)
        remoteHandsBySeat[requestingSeat] = hand

        _gameState.value = state.copy(
            myTableMelds = updatedMyTable,
            opponentTableMelds = updatedOpponentTable,
            deckSize = json.optInt("deckSize", state.deckSize),
            mortosLeft = json.optInt("mortosLeft", state.mortosLeft),
            opponentHandCount = remoteHandCountForSeat(requestingSeat),
            opponentPickedMorto = if (team != teamForSeat(localSeat)) true else state.opponentPickedMorto,
            mortoNoticeSeat = requestingSeat,
            mortoNoticeId = state.mortoNoticeId + 1
        )
    }

    /**
     * Client: Recebe as cartas do morto enviadas pelo Host.
     */
    private fun handleServeMorto(payload: String) {
        val localTeam = teamForSeat(localSeat)
        if (teamsThatPickedMorto.contains(localTeam) && _gameState.value.myHand.isNotEmpty()) {
            return // Idempotência sem descartar uma entrega privada que chegou após o aviso público.
        }

        val json = runCatching { JSONObject(payload) }.getOrNull()
        val morto = if (json != null) {
            cardsFromJson(json.optJSONArray("hand") ?: JSONArray())
        } else {
            parseHandPayload(payload)
        }
        if (morto.size != currentConfig.cardsPerPlayer) {
            _gameState.value = _gameState.value.copy(
                feedbackMessage = "Morto inválido (${morto.size} cartas). Solicite nova sincronização da mesa."
            )
            return
        }
        val mortosLeft = json?.optInt("mortosLeft", (_gameState.value.mortosLeft - 1).coerceAtLeast(0))
            ?: (_gameState.value.mortosLeft - 1).coerceAtLeast(0)
        val isIndirect = json?.optBoolean("indirect", pendingMortoPickupIsIndirect)
            ?: pendingMortoPickupIsIndirect
        val nextSeat = nextSeatAfter(localSeat)
        val currentHand = _gameState.value.myHand
        if (currentHand.isNotEmpty()) {
            _gameState.value = _gameState.value.copy(
                feedbackMessage = "Morto recusado: sua mão ainda tem cartas. Sincronize a partida."
            )
            return
        }

        val mergedHand = sortHandIfEnabled(morto)
        val (processedHand, newMelds) = autoProcessThreeReds(mergedHand, _gameState.value.myTableMelds)

        teamsThatPickedMorto.add(localTeam)
        pendingMortoPickupIsIndirect = false

        val stateBeforeDelivery = _gameState.value
        _gameState.value = stateBeforeDelivery.copy(
            myHand = processedHand,
            mortosLeft = mortosLeft,
            myTableMelds = newMelds,
            activeSeat = if (isIndirect) nextSeat else localSeat,
            turnPhase = if (isIndirect) TurnPhase.WAITING_OPPONENT else TurnPhase.ACTION,
            mortoNoticeSeat = localSeat,
            mortoNoticeId = if (stateBeforeDelivery.mortoNoticeSeat == localSeat) {
                stateBeforeDelivery.mortoNoticeId
            } else {
                stateBeforeDelivery.mortoNoticeId + 1
            },
            feedbackMessage = "Você pegou o Morto!"
        )
    }

    // So o cliente chega aqui (dispatch: "MELD" -> handleRemoteMeld quando isHost).
    // O host valida e decide vitoria/contagem em handleRemoteMeld -> resolveRemoteEmptyHand,
    // que ja usa remoteTableForSeat(actorSeat) para ler o time certo.
    private fun handleOpponentMeld(payload: String) {
        val (meld, actorSeat) = parseMeldPayload(payload)
        val replaceIndex = parseMeldReplaceIndex(payload)
        if (meld.isNotEmpty()) {
            rememberRemoteSeat(actorSeat, null)
            removeRemoteCards(actorSeat, meld)
            val state = _gameState.value
            if (currentConfig.maxPlayers == 4 && teamForSeat(actorSeat) == teamForSeat(localSeat)) {
                _gameState.value = state.copy(myTableMelds = applyRemoteMeld(state.myTableMelds, meld, replaceIndex))
            } else {
                _gameState.value = state.copy(
                    opponentTableMelds = applyRemoteMeld(state.opponentTableMelds, meld, replaceIndex),
                    opponentHandCount = remoteHandCountForSeat(actorSeat)
                )
            }
        }
    }

    private fun handleOpponentPickMorto(payload: String = "") {
        if (mortos.isNotEmpty()) mortos.removeAt(0)
        val payloadCount = runCatching {
            JSONObject(payload).optInt("mortosLeft", -1)
        }.getOrDefault(-1)
        val pickedSeat = runCatching {
            JSONObject(payload).optInt("seat", -1)
        }.getOrDefault(-1)
        
        val state = _gameState.value
        if (pickedSeat >= 0) {
            val pickedTeam = teamForSeat(pickedSeat)
            if (pickedSeat != localSeat) {
                teamsThatPickedMorto.add(pickedTeam)
                // O cliente conhece somente a quantidade da mão dos outros assentos.
                remoteHandsBySeat[pickedSeat] = List(currentConfig.cardsPerPlayer) {
                    Card(Suit.SPADES, Rank.ACE)
                }
            }
        }
        val updatedCount = if (payloadCount >= 0) {
            payloadCount
        } else if (mortos.isNotEmpty()) {
            mortos.size
        } else {
            (state.mortosLeft - 1).coerceAtLeast(0)
        }
        
        val pickedByPlayer = pickedSeat >= 0
        val pickedByOpponentTeam = pickedByPlayer &&
            teamForSeat(pickedSeat) != teamForSeat(localSeat)
        val noticeAlreadyShown = state.mortoNoticeSeat == pickedSeat
        _gameState.value = state.copy(
            mortosLeft = updatedCount,
            opponentHandCount = if (pickedByOpponentTeam) {
                remoteHandCountForSeat(pickedSeat)
            } else {
                state.opponentHandCount
            },
            opponentPickedMorto = if (pickedByOpponentTeam) true else state.opponentPickedMorto,
            mortoNoticeSeat = if (pickedByPlayer) pickedSeat else state.mortoNoticeSeat,
            mortoNoticeId = if (pickedByPlayer && !noticeAlreadyShown) {
                state.mortoNoticeId + 1
            } else {
                state.mortoNoticeId
            }
        )
        if (isHost) publishPublicTableState()
    }

    // --- Helpers ---

    /**
     * Identifica se existem 3 vermelhos na mão do jogador (apenas no modo Tranca).
     * Se houver, baixa-os automaticamente na mesa e notifica o oponente.
     */
    private fun findAppendTarget(
        tableMelds: List<List<Card>>,
        selected: List<Card>,
        cachetaTurnCard: Card?
    ): Pair<Int, GameRulesEngine.MeldValidationResult>? {
        if (selected.isEmpty()) return null
        tableMelds.forEachIndexed { index, meld ->
            val result = GameRulesEngine.validateMeld(meld + selected, currentConfig, cachetaTurnCard)
            if (result.isValid) return index to result
        }
        return null
    }

    private fun findAppendTargets(
        tableMelds: List<List<Card>>,
        selected: List<Card>,
        cachetaTurnCard: Card?
    ): List<Int> {
        if (selected.isEmpty()) return emptyList()
        val targets = mutableListOf<Int>()
        tableMelds.forEachIndexed { index, meld ->
            val result = GameRulesEngine.validateMeld(meld + selected, currentConfig, cachetaTurnCard)
            if (result.isValid) {
                targets.add(index)
            }
        }
        return targets
    }

    /**
     * Processa automaticamente os 3 vermelhos (Tranca) e já os joga na mesa se as configurações permitirem.
     */
    private fun autoProcessThreeReds(
        hand: List<Card>,
        tableMelds: List<List<Card>>,
        broadcastMelds: Boolean = true
    ): Pair<List<Card>, List<List<Card>>> {
        if (!currentConfig.autoMeldTrancaRedThrees) return Pair(hand, tableMelds)

        val (newHand, newMelds) = GameRulesEngine.handleThreeReds(hand, tableMelds, currentConfig.gameType)
        val addedMeldsCount = newMelds.size - tableMelds.size
        if (addedMeldsCount > 0 && broadcastMelds) {
            val originalMeldIdsSet = tableMelds.map { m -> m.joinToString(",") { it.id } }.toSet()
            newMelds.forEach { meld ->
                val meldId = meld.joinToString(",") { it.id }
                if (meldId !in originalMeldIdsSet) {
                    networkRepository.sendMessage(NetworkMessage(playerId, "MELD", buildMeldPayload(meld, localSeat)))
                }
            }
        }
        return Pair(newHand, newMelds)
    }

    private fun updateDiscardState(
        hand: List<Card>,
        firstDiscard: Card?,
        turnCard: Card?,
        tableMelds: List<List<Card>> = emptyList(),
        activeSeat: Int = 0
    ) {
        val discardLocked = GameRulesEngine.isDiscardLocked(firstDiscard, currentConfig.gameType)
        val drawCheck = GameRulesEngine.canDrawFromDiscard(firstDiscard, currentConfig)

        _gameState.value = GameState(
            myHand = hand,
            dealEventId = _gameState.value.dealEventId + 1,
            discardPile = if (firstDiscard != null) listOf(firstDiscard) else emptyList(),
            turnCard = turnCard,
            myTableMelds = tableMelds,
            opponentTableMelds = emptyList(),
            opponentHandCount = remoteHandsBySeat.values.firstOrNull()?.size ?: currentConfig.cardsPerPlayer,
            deckSize = masterDeck.size,
            mortosLeft = mortos.size,
            playerSeat = localSeat,
            activeSeat = activeSeat,
            myScore = _gameState.value.myScore,
            opponentScore = _gameState.value.opponentScore,
            teamScores = _gameState.value.teamScores.ifEmpty { initialTeamScores(currentConfig) },
            turnPhase = TurnPhase.WAITING_OPPONENT,
            lastDrawnCardId = null,
            feedbackMessage = "Partida iniciada! Aguardando oponente...",
            isDiscardLocked = discardLocked,
            canDrawFromDiscard = drawCheck.allowed,
            drawDiscardBlockedReason = drawCheck.reason,
            config = currentConfig,
            opponentLabel = opponentDisplayLabel()
        )
    }

    private fun buildGameStartPayload(
        hand: List<Card>,
        firstDiscard: Card?,
        turnCard: Card?,
        playerSeat: Int
    ): String {
        return JSONObject()
            .put("v", 1)
            .put("roundId", currentRoundId.orEmpty())
            .put("config", currentConfig.serialize())
            .put("hand", cardsToJson(hand))
            .put("seat", playerSeat)
            .put("activeSeat", 1)
            .put("discard", firstDiscard?.id ?: "")
            .put("turnCard", turnCard?.id ?: "")
            .put("deckSize", masterDeck.size)
            .put("mortosLeft", mortos.size)
            .toString()
    }

    private fun publishPublicTableState() {
        if (!isHost) return
        networkRepository.sendMessage(NetworkMessage(playerId, "PUBLIC_STATE", buildPublicTableStatePayload()))
    }

    private fun buildPublicTableStatePayload(): String {
        val state = _gameState.value
        val handCounts = JSONArray().apply {
            repeat(currentConfig.maxPlayers.coerceAtLeast(2)) { seat ->
                put(publicHandCountForSeat(seat, state))
            }
        }

        return JSONObject()
            .put("v", 1)
            .put("activeSeat", state.activeSeat)
            .put("deckSize", state.deckSize)
            .put("discardCount", state.discardPile.size)
            .put("discardPile", cardsToJson(state.discardPile))
            .put("turnCard", state.turnCard?.id ?: "")
            .put("mortosLeft", state.mortosLeft)
            .put("handCounts", handCounts)
            .put("team0Melds", handsToJson(if (teamForSeat(localSeat) == 0) state.myTableMelds else state.opponentTableMelds))
            .put("team1Melds", handsToJson(if (teamForSeat(localSeat) == 1) state.myTableMelds else state.opponentTableMelds))
            .toString()
    }

    private fun handlePublicTableState(payload: String) {
        if (isHost) return
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val state = _gameState.value
        val activeSeat = json.optInt("activeSeat", state.activeSeat)
            .coerceIn(0, currentConfig.maxPlayers.coerceAtLeast(2) - 1)
        val discardPile = cardsFromJson(json.optJSONArray("discardPile") ?: JSONArray())
        val turnCard = allCardsMap[json.optString("turnCard", "")]
        val handCounts = json.optJSONArray("handCounts")
        val hasMeldSnapshot = json.has("team0Melds") && json.has("team1Melds")
        val team0Melds = handsFromJson(json.optJSONArray("team0Melds") ?: JSONArray())
        val team1Melds = handsFromJson(json.optJSONArray("team1Melds") ?: JSONArray())
        val localTeam = teamForSeat(localSeat)
        val syncedMyMelds = if (!hasMeldSnapshot) state.myTableMelds else if (localTeam == 0) team0Melds else team1Melds
        val syncedOpponentMelds = if (!hasMeldSnapshot) state.opponentTableMelds else if (localTeam == 0) team1Melds else team0Melds
        val opponentSeat = visibleOpponentSeatFor(state.playerSeat)
        val opponentHandCount = if (handCounts != null && opponentSeat in 0 until handCounts.length()) {
            handCounts.optInt(opponentSeat, state.opponentHandCount)
        } else {
            state.opponentHandCount
        }
        val topDiscard = discardPile.lastOrNull()
        val discardLocked = GameRulesEngine.isDiscardLocked(topDiscard, currentConfig.gameType)
        val drawCheck = GameRulesEngine.canDrawFromDiscard(topDiscard, currentConfig)
        val waitingPrivateCard = state.feedbackMessage.contains("Aguardando carta do host", ignoreCase = true)
        val syncedTurnPhase = when {
            state.showRoundEndDialog -> state.turnPhase
            localSeat != activeSeat -> TurnPhase.WAITING_OPPONENT
            waitingPrivateCard -> state.turnPhase
            state.turnPhase == TurnPhase.ACTION -> TurnPhase.ACTION
            else -> TurnPhase.DRAW
        }

        _gameState.value = state.copy(
            discardPile = discardPile,
            turnCard = turnCard,
            myTableMelds = syncedMyMelds,
            opponentTableMelds = syncedOpponentMelds,
            deckSize = json.optInt("deckSize", state.deckSize),
            mortosLeft = json.optInt("mortosLeft", state.mortosLeft),
            opponentHandCount = opponentHandCount,
            activeSeat = activeSeat,
            turnPhase = syncedTurnPhase,
            isDiscardLocked = discardLocked,
            canDrawFromDiscard = drawCheck.allowed,
            drawDiscardBlockedReason = drawCheck.reason
        )
    }

    private fun publicHandCountForSeat(seat: Int, state: GameState): Int {
        return if (seat == localSeat) {
            state.myHand.size
        } else {
            remoteHandsBySeat[seat]?.size ?: state.opponentHandCount
        }
    }

    private fun visibleOpponentSeatFor(playerSeat: Int): Int {
        val maxSeats = currentConfig.maxPlayers.coerceAtLeast(2)
        return (0 until maxSeats).firstOrNull { seat ->
            seat != playerSeat && teamForSeat(seat) != teamForSeat(playerSeat)
        } ?: nextSeatAfter(playerSeat)
    }

    private fun buildServeMortoPayload(
        morto: List<Card>,
        mortosLeft: Int,
        indirect: Boolean = false,
        activeSeat: Int = _gameState.value.activeSeat
    ): String {
        return JSONObject()
            .put("v", 1)
            .put("hand", cardsToJson(morto))
            .put("mortosLeft", mortosLeft)
            .put("indirect", indirect)
            .put("activeSeat", activeSeat)
            .toString()
    }

    private fun buildMortosLeftPayload(mortosLeft: Int, pickedSeat: Int): String {
        val json = JSONObject()
            .put("v", 1)
            .put("mortosLeft", mortosLeft)
        if (pickedSeat >= 0) {
            json
                .put("seat", pickedSeat)
                .put("team", teamForSeat(pickedSeat))
        }
        return json.toString()
    }

    private fun requestMorto() {
        val payload = JSONObject()
            .put("v", 1)
            .put("seat", localSeat)
            .put("indirect", pendingMortoPickupIsIndirect)
            .toString()
        networkRepository.sendMessage(NetworkMessage(playerId, "REQ_PICK_MORTO", payload))
    }

    private fun buildDiscardPayload(card: Card, seat: Int): String {
        return JSONObject()
            .put("v", 1)
            .put("card", card.id)
            .put("seat", seat)
            .toString()
    }

    private fun buildMeldPayload(cards: List<Card>, seat: Int, replaceIndex: Int = -1): String {
        return JSONObject()
            .put("v", 1)
            .put("cards", cardsToJson(cards))
            .put("seat", seat)
            .put("team", teamForSeat(seat))
            .put("replaceIndex", replaceIndex)
            .toString()
    }

    private fun buildRoundReportPayload(
        report: RoundSeatReport,
        winnerId: String? = null
    ): String {
        return JSONObject()
            .put("v", 1)
            .put("playerId", report.playerId)
            .put("seat", report.seat)
            .put("hand", cardsToJson(report.hand))
            .put("tableMelds", handsToJson(report.tableMelds))
            .apply { if (winnerId != null) put("winnerId", winnerId) }
            .toString()
    }

    private fun parseRoundReportPayload(payload: String): RoundSeatReport? {
        val trimmed = payload.trim()
        if (!trimmed.startsWith("{")) {
            return RoundSeatReport(
                playerId = "",
                seat = 1,
                hand = parseHandPayload(payload),
                tableMelds = emptyList()
            )
        }

        val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return null
        return RoundSeatReport(
            playerId = json.optString("playerId"),
            seat = json.optInt("seat", 1),
            hand = cardsFromJson(json.optJSONArray("hand") ?: JSONArray()),
            tableMelds = handsFromJson(json.optJSONArray("tableMelds") ?: JSONArray())
        )
    }

    private fun buildSeatPayload(seat: Int): String {
        return JSONObject()
            .put("v", 1)
            .put("seat", seat)
            .toString()
    }

    private fun parseSeatPayload(payload: String): Int {
        val trimmed = payload.trim()
        if (!trimmed.startsWith("{")) return -1
        return runCatching { JSONObject(trimmed).optInt("seat", -1) }.getOrDefault(-1)
    }

    private fun parseCardId(payload: String): String {
        val trimmed = payload.trim()
        if (!trimmed.startsWith("{")) return trimmed
        return runCatching { JSONObject(trimmed).optString("card", "") }.getOrDefault("")
    }

    private fun trustedRemoteSeat(message: NetworkMessage, claimedSeat: Int): Int? {
        if (!isHost) return claimedSeat
        val trustedSeat = message.senderSeat
            ?: remotePlayerSeats[message.senderId]
            ?: claimedSeat.takeIf { it in 1 until currentConfig.maxPlayers }
            ?: return null
        if (trustedSeat !in 1 until currentConfig.maxPlayers || claimedSeat != trustedSeat) {
            rejectRemoteAction("Acao recusada: identidade do jogador nao confere com o assento.")
            return null
        }
        val rememberedSeat = remotePlayerSeats[message.senderId]
        if (rememberedSeat != null && rememberedSeat != trustedSeat) {
            rejectRemoteAction("Acao recusada: o jogador tentou trocar de assento durante a partida.")
            return null
        }
        rememberRemoteSeat(trustedSeat, message.senderId)
        return trustedSeat
    }

    /**
     * Para mensagens sem campo de assento no payload, a identidade vem somente do transporte.
     * O fallback pelo mapa existe para manter a reconexao do mesmo jogador na sessao atual.
     */
    private fun authenticatedRemoteSeat(message: NetworkMessage): Int? {
        val seat = message.senderSeat ?: remotePlayerSeats[message.senderId] ?: return null
        if (seat !in 1 until currentConfig.maxPlayers) {
            rejectRemoteAction("Acao recusada: assento remoto invalido.")
            return null
        }
        val rememberedSeat = remotePlayerSeats[message.senderId]
        if (rememberedSeat != null && rememberedSeat != seat) {
            rejectRemoteAction("Acao recusada: o jogador tentou trocar de assento durante a partida.")
            return null
        }
        rememberRemoteSeat(seat, message.senderId)
        return seat
    }

    private fun isHostOrigin(message: NetworkMessage): Boolean {
        // null mantem compatibilidade com snapshots e testes antigos; os transportes atuais enviam 0.
        return message.senderSeat == null || message.senderSeat == 0
    }

    private fun rejectRemoteAction(reason: String) {
        _gameState.value = _gameState.value.copy(feedbackMessage = reason)
        publishPublicTableState()
    }

    private fun hasRemoteDrawnThisTurn(seat: Int): Boolean {
        return seat in deckServedSeatsThisTurn || seat in pendingRemoteDiscardDraws
    }

    private fun remoteTableForSeat(seat: Int): List<List<Card>> {
        return if (teamForSeat(seat) == teamForSeat(localSeat)) {
            _gameState.value.myTableMelds
        } else {
            _gameState.value.opponentTableMelds
        }
    }

    private fun setRemoteTableForSeat(seat: Int, melds: List<List<Card>>) {
        val state = _gameState.value
        _gameState.value = if (teamForSeat(seat) == teamForSeat(localSeat)) {
            state.copy(myTableMelds = melds)
        } else {
            state.copy(opponentTableMelds = melds)
        }
    }

    private fun containsCards(source: List<Card>, requested: List<Card>): Boolean {
        val remainingIds = source.map { it.id }.toMutableList()
        return requested.all { card ->
            val index = remainingIds.indexOf(card.id)
            if (index < 0) false else {
                remainingIds.removeAt(index)
                true
            }
        }
    }

    private fun subtractCards(fullMeld: List<Card>, existingMeld: List<Card>): List<Card>? {
        val remaining = fullMeld.toMutableList()
        existingMeld.forEach { existing ->
            val index = remaining.indexOfFirst { it.id == existing.id }
            if (index < 0) return null
            remaining.removeAt(index)
        }
        return remaining
    }

    private fun canRemoteDiscardWildcard(
        card: Card,
        hand: List<Card>,
        tableMelds: List<List<Card>>,
        turnCard: Card?
    ): Boolean {
        if (currentConfig.gameType == GameType.CACHETA) return true
        if (!GameRulesEngine.isWildcard(card, currentConfig.gameType)) return true

        val remaining = hand.toMutableList().also { cards ->
            val index = cards.indexOfFirst { it.id == card.id }
            if (index >= 0) cards.removeAt(index)
        }
        if (remaining.none { !GameRulesEngine.isWildcard(it, currentConfig.gameType) }) return true

        return !GameRulesEngine.canJustifyDiscardDraw(
            topDiscard = card,
            hand = remaining,
            tableMelds = tableMelds,
            config = currentConfig,
            cachetaTurnCard = turnCard
        )
    }

    private fun parseDiscardPayload(payload: String): Pair<String, Int> {
        val trimmed = payload.trim()
        if (!trimmed.startsWith("{")) return payload to 0

        val json = runCatching { JSONObject(trimmed) }.getOrNull()
            ?: return payload to 0
        return json.optString("card", "") to json.optInt("seat", 0)
    }

    private fun parseMeldPayload(payload: String): Pair<List<Card>, Int> {
        val trimmed = payload.trim()
        if (!trimmed.startsWith("{")) {
            return trimmed.split(",")
                .filter { it.isNotBlank() }
                .mapNotNull { allCardsMap[it] } to 1
        }

        val json = runCatching { JSONObject(trimmed) }.getOrNull()
            ?: return emptyList<Card>() to 1
        return cardsFromJson(json.optJSONArray("cards") ?: JSONArray()) to json.optInt("seat", 1)
    }

    private fun parseMeldReplaceIndex(payload: String): Int {
        val trimmed = payload.trim()
        if (!trimmed.startsWith("{")) return -1
        return runCatching { JSONObject(trimmed).optInt("replaceIndex", -1) }.getOrDefault(-1)
    }

    private fun applyRemoteMeld(
        currentMelds: List<List<Card>>,
        meld: List<Card>,
        replaceIndex: Int
    ): List<List<Card>> {
        val sortedMeld = GameRulesEngine.sortMeld(meld, currentConfig, _gameState.value.turnCard)
        return if (replaceIndex in currentMelds.indices) {
            currentMelds.mapIndexed { index, existing -> if (index == replaceIndex) sortedMeld else existing }
        } else {
            currentMelds + listOf(sortedMeld)
        }
    }

    private fun rememberRemoteSeat(seat: Int, remotePlayerId: String?) {
        if (!isHost || seat !in 1 until currentConfig.maxPlayers) return
        if (remotePlayerId != null) {
            remotePlayerSeats[remotePlayerId] = seat
        }
        remoteHandsBySeat.putIfAbsent(seat, emptyList())
    }

    private fun addRemoteCards(seat: Int, cards: List<Card>) {
        if (!isHost || cards.isEmpty() || seat !in 1 until currentConfig.maxPlayers) return
        val updatedHand = remoteHandsBySeat[seat].orEmpty() + cards
        remoteHandsBySeat[seat] = sortHandIfEnabled(updatedHand)
    }

    private fun removeRemoteCards(seat: Int, cards: List<Card>) {
        if (!isHost || cards.isEmpty() || seat !in 1 until currentConfig.maxPlayers) return
        val remaining = remoteHandsBySeat[seat].orEmpty().toMutableList()
        cards.forEach { card ->
            val index = remaining.indexOfFirst { it.id == card.id }
            if (index >= 0) remaining.removeAt(index)
        }
        remoteHandsBySeat[seat] = sortHandIfEnabled(remaining)
    }

    private fun remoteHandCountForSeat(seat: Int): Int {
        return remoteHandsBySeat[seat]?.size
            ?: _gameState.value.opponentHandCount
    }

    private fun isTrancaRedThree(card: Card): Boolean {
        return currentConfig.gameType == GameType.TRANCA &&
            currentConfig.autoMeldTrancaRedThrees &&
            card.rank == Rank.THREE &&
            (card.suit == Suit.HEARTS || card.suit == Suit.DIAMONDS)
    }

    private fun canDiscardWildcardNow(card: Card, state: GameState): Boolean {
        if (currentConfig.gameType == GameType.CACHETA) return true
        if (!GameRulesEngine.isWildcard(card, currentConfig.gameType)) return true

        val remainingHand = state.myHand.filter { it.id != card.id }
        val hasOtherDiscardOption = remainingHand.any { !GameRulesEngine.isWildcard(it, currentConfig.gameType) }
        if (!hasOtherDiscardOption) return true

        val canUseInExistingOrNewGame = GameRulesEngine.canJustifyDiscardDraw(
            topDiscard = card,
            hand = remainingHand,
            tableMelds = state.myTableMelds,
            config = currentConfig,
            cachetaTurnCard = state.turnCard
        )
        val hasCleanTargetWithoutWildcard = state.myTableMelds.any { meld ->
            meld.size >= 3 && meld.none { GameRulesEngine.isWildcard(it, currentConfig.gameType) }
        }
        return !canUseInExistingOrNewGame && !hasCleanTargetWithoutWildcard
    }

    private fun nextSeatAfter(seat: Int): Int {
        val maxSeats = currentConfig.maxPlayers.coerceAtLeast(2)
        return (seat + 1).floorMod(maxSeats)
    }

    private fun Int.floorMod(mod: Int): Int = ((this % mod) + mod) % mod

    private fun initialTeamScores(config: MatchConfig): List<Int> {
        val initial = if (config.gameType == GameType.CACHETA) config.pointLimit else 0
        return listOf(initial, initial)
    }

    private fun teamForSeat(seat: Int): Int = seat.floorMod(2)

    private fun opposingTeam(team: Int): Int = if (team == 0) 1 else 0

    private fun teamLabel(team: Int): String = if (team == 0) "A" else "B"

    private fun applyRoundToTeamScores(
        currentScores: List<Int>,
        winnerTeam: Int,
        winnerRoundScore: Int,
        loserTeam: Int,
        loserRoundScore: Int
    ): List<Int> {
        val scores = currentScores.ifEmpty { initialTeamScores(currentConfig) }.toMutableList()
        while (scores.size < 2) scores.add(if (currentConfig.gameType == GameType.CACHETA) currentConfig.pointLimit else 0)
        scores[winnerTeam] = scores[winnerTeam] + winnerRoundScore
        scores[loserTeam] = scores[loserTeam] + loserRoundScore
        return scores
    }

    private fun applyCountRoundToTeamScores(
        currentScores: List<Int>,
        roundScores: List<Int>
    ): List<Int> {
        val scores = currentScores.ifEmpty { initialTeamScores(currentConfig) }.toMutableList()
        while (scores.size < 2) scores.add(if (currentConfig.gameType == GameType.CACHETA) currentConfig.pointLimit else 0)
        roundScores.forEachIndexed { team, roundScore ->
            if (team < scores.size) scores[team] += roundScore
        }
        return scores
    }

    /**
     * Verifica se a partida terminou.
     * - Cacheta: uma equipe ficou com 0 ou menos vidas.
     * - Buraco/Tranca: uma equipe atingiu ou superou o limite de pontos configurado.
     */
    private fun isMatchOver(teamScores: List<Int>): Boolean {
        return if (currentConfig.gameType == GameType.CACHETA) {
            // Na Cacheta os pontos começam em pointLimit e decrescem; acaba quando alguém chega em 0
            teamScores.any { it <= 0 }
        } else {
            // No Buraco/Tranca os pontos começam em 0 e crescem; acaba quando alguém atinge o limite
            teamScores.any { it >= currentConfig.pointLimit }
        }
    }

    /**
     * Monta a estrutura de dados (JSON) com o resumo da rodada para enviar aos clientes.
     */
    private fun buildRoundSummaryPayload(
        winnerId: String,
        winnerRoundScore: Int,
        loserRoundScore: Int,
        winnerTotal: Int,
        loserTotal: Int,
        isMatchOver: Boolean,
        breakdown: String,
        teamScores: List<Int>,
        winnerTeam: Int,
        noWinner: Boolean = false,
        teamRoundScores: List<Int> = emptyList()
    ): String {
        return JSONObject()
            .put("v", 1)
            .put("winnerId", winnerId)
            .put("winnerRoundScore", winnerRoundScore)
            .put("loserRoundScore", loserRoundScore)
            .put("winnerTotal", winnerTotal)
            .put("loserTotal", loserTotal)
            .put("isMatchOver", isMatchOver)
            .put("breakdown", breakdown)
            .put("teamScores", JSONArray().apply { teamScores.forEach { put(it) } })
            .put("winnerTeam", winnerTeam)
            .put("noWinner", noWinner)
            .put("teamRoundScores", JSONArray().apply { teamRoundScores.forEach { put(it) } })
            .toString()
    }

    private fun handleRoundSummaryJson(payload: String) {
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val noWinner = json.optBoolean("noWinner", false)
        val amIWinner = json.optString("winnerId") == playerId
        val winScore = json.optInt("winnerRoundScore")
        val loseScore = json.optInt("loserRoundScore")
        val winTotal = json.optInt("winnerTotal")
        val loseTotal = json.optInt("loserTotal")
        val over = json.optBoolean("isMatchOver")
        val breakdown = json.optString("breakdown")
        val winnerTeam = json.optInt(
            "winnerTeam",
            if (amIWinner) teamForSeat(localSeat) else opposingTeam(teamForSeat(localSeat))
        )
        val state = _gameState.value
        val roundScores = parseTeamScores(json.optJSONArray("teamRoundScores"))
        val teamScores = parseTeamScores(json.optJSONArray("teamScores"))
        val localTeam = teamForSeat(localSeat)
        val opponentTeam = opposingTeam(localTeam)
        val resolvedTeamScores = teamScores ?: if (noWinner) {
            applyCountRoundToTeamScores(state.teamScores, roundScores ?: listOf(0, 0))
        } else {
            applyRoundToTeamScores(
                currentScores = state.teamScores,
                winnerTeam = winnerTeam,
                winnerRoundScore = winScore,
                loserTeam = opposingTeam(winnerTeam),
                loserRoundScore = loseScore
            )
        }
        val myRound = if (noWinner) roundScores?.getOrElse(localTeam) { 0 } ?: 0 else if (localTeam == winnerTeam) winScore else loseScore
        val oppRound = if (noWinner) roundScores?.getOrElse(opponentTeam) { 0 } ?: 0 else if (localTeam == winnerTeam) loseScore else winScore
        val myNew = resolvedTeamScores.getOrElse(localTeam) { if (localTeam == winnerTeam) winTotal else loseTotal }
        val oppNew = resolvedTeamScores.getOrElse(opponentTeam) { if (localTeam == winnerTeam) loseTotal else winTotal }

        _gameState.value = state.copy(
            myScore = myNew,
            opponentScore = oppNew,
            teamScores = resolvedTeamScores,
            showRoundEndDialog = true,
            roundEndDetails = RoundEndDetails(
                winnerName = when {
                    noWinner -> "Contagem"
                    amIWinner -> "Você"
                    localTeam == winnerTeam -> "Sua equipe"
                    else -> opponentDisplayLabel()
                },
                myRoundScore = myRound,
                opponentRoundScore = oppRound,
                myNewTotal = myNew,
                opponentNewTotal = oppNew,
                isMatchOver = over,
                breakdown = formatBreakdownForLocal(breakdown, localTeam),
                teamScores = resolvedTeamScores,
                winnerTeam = if (noWinner) null else winnerTeam,
                localTeam = localTeam,
                opponentLabel = opponentDisplayLabel()
            )
        )

    }

    private fun parseTeamScores(jsonArray: JSONArray?): List<Int>? {
        jsonArray ?: return null
        if (jsonArray.length() < 2) return null
        return List(jsonArray.length()) { index -> jsonArray.optInt(index) }
    }

    private fun parseHandPayload(payload: String): List<Card> {
        val trimmed = payload.trim()
        if (trimmed.startsWith("{")) {
            val json = runCatching { JSONObject(trimmed) }.getOrNull() ?: return emptyList()
            return cardsFromJson(json.optJSONArray("hand") ?: JSONArray())
        }

        return trimmed.split(",")
            .filter { it.isNotBlank() }
            .mapNotNull { allCardsMap[it] }
    }

    private fun handsToJson(hands: List<List<Card>>): JSONArray {
        return JSONArray().apply {
            hands.forEach { hand -> put(cardsToJson(hand)) }
        }
    }

    private fun handsFromJson(hands: JSONArray): List<List<Card>> {
        return buildList {
            repeat(hands.length()) { index ->
                add(cardsFromJson(hands.optJSONArray(index) ?: JSONArray()))
            }
        }
    }

    private fun cardsToJson(cards: List<Card>): JSONArray {
        return JSONArray().apply {
            cards.forEach { card -> put(card.id) }
        }
    }

    private fun cardsFromJson(cards: JSONArray): List<Card> {
        return buildList {
            repeat(cards.length()) { index ->
                allCardsMap[cards.optString(index)]?.let { add(it) }
            }
        }
    }

    // --- Persistência e reconexão ---

    private fun snapshotFile(): File? =
        appContextRef?.get()?.filesDir?.let { File(it, "game_snapshot_${config.gameType.name}.json") }

    /**
     * Salva um retrato local da partida para recuperar queda ou fechamento do app.
     */
    private fun buildSnapshotJson(state: GameState): String {
        val remoteHandsJson = JSONObject().apply {
            remoteHandsBySeat.forEach { (seat, hand) -> put(seat.toString(), cardsToJson(hand)) }
        }
        val mortosJson = JSONArray().apply {
            mortos.forEach { put(cardsToJson(it)) }
        }
        return JSONObject()
            .put("myHand", cardsToJson(state.myHand))
            .put("myTableMelds", handsToJson(state.myTableMelds))
            .put("opponentTableMelds", handsToJson(state.opponentTableMelds))
            .put("discardPile", cardsToJson(state.discardPile))
            .put("deckSize", state.deckSize)
            .put("mortosLeft", state.mortosLeft)
            .put("playerSeat", state.playerSeat)
            .put("activeSeat", state.activeSeat)
            .put("myScore", state.myScore)
            .put("opponentScore", state.opponentScore)
            .put("teamScores", JSONArray(state.teamScores))
            .put("config", currentConfig.serialize())
            .put("isHost", isHost)
            .put("masterDeck", cardsToJson(masterDeck))
            .put("mortos", mortosJson)
            .put("remoteHands", remoteHandsJson)
            .put("teamsThatPickedMorto", JSONArray(teamsThatPickedMorto.toList()))
            .put("currentRoundId", currentRoundId ?: "")
            .put("remotePlayerSeats", JSONObject().apply {
                remotePlayerSeats.forEach { (id, seat) -> put(id, seat) }
            })
            .toString()
    }

    private fun saveGameSnapshot(state: GameState) {
        val file = snapshotFile() ?: return
        viewModelScope.launch {
            try {
                file.writeText(buildSnapshotJson(state))
            } catch (_: Exception) {}
        }
    }

    /**
     * Remove o retrato local quando a partida terminou de verdade.
     */
    fun clearGameSnapshot() {
        snapshotFile()?.delete()
    }

    /**
     * Tenta restaurar uma partida em andamento após uma falha ou fechamento do app.
     */
    fun tryRestoreFromSnapshot(): Boolean {
        val file = snapshotFile() ?: return false
        if (!file.exists()) return false
        return try {
            applySnapshotJson(file.readText())
        } catch (e: Exception) {
            false
        }
    }

    private fun applySnapshotJson(jsonText: String): Boolean {
        return try {
            val json = JSONObject(jsonText)
            val handJson = json.optJSONArray("myHand") ?: JSONArray()
            val myTableMeldsJson = json.optJSONArray("myTableMelds") ?: JSONArray()
            val opponentTableMeldsJson = json.optJSONArray("opponentTableMelds") ?: JSONArray()
            val discardPileJson = json.optJSONArray("discardPile") ?: JSONArray()
            val deckSize = json.optInt("deckSize", 0)
            val mortosLeft = json.optInt("mortosLeft", 0)
            val playerSeat = json.optInt("playerSeat", 0)
            val activeSeat = json.optInt("activeSeat", 0)
            val myScore = json.optInt("myScore", 0)
            val opponentScore = json.optInt("opponentScore", 0)
            val teamScoresJson = json.optJSONArray("teamScores")
            val configStr = json.optString("config", "")
            val masterDeckJson = json.optJSONArray("masterDeck") ?: JSONArray()
            val savedMortosJson = json.optJSONArray("mortos") ?: JSONArray()
            val remoteHandsJson = json.optJSONObject("remoteHands")
            val teamsThatPickedMortoJson = json.optJSONArray("teamsThatPickedMorto")
            val savedRoundId = json.optString("currentRoundId", "")

            if (configStr.isNotBlank()) {
                currentConfig = MatchConfig.deserialize(configStr)
            }
            localSeat = playerSeat
            val teamScores = if (teamScoresJson != null) {
                buildList { repeat(teamScoresJson.length()) { i -> add(teamScoresJson.optInt(i)) } }
            } else emptyList()

            // Estado autoritativo real (só o host usa isso pra valer, mas é
            // inofensivo restaurar vazio no cliente): sem isso, o host "recuperado"
            // continuaria a mesa sem monte, sem mortos e sem saber a mão dos outros
            // assentos, quebrando qualquer compra ou validação depois do restore.
            masterDeck = cardsFromJson(masterDeckJson).toMutableList()
            mortos = buildList {
                repeat(savedMortosJson.length()) { index ->
                    val morto = cardsFromJson(savedMortosJson.optJSONArray(index) ?: JSONArray())
                    if (morto.isNotEmpty()) add(morto)
                }
            }.toMutableList()
            remoteHandsBySeat.clear()
            if (remoteHandsJson != null) {
                for (seat in 1 until currentConfig.maxPlayers) {
                    val seatHandJson = remoteHandsJson.optJSONArray(seat.toString())
                    if (seatHandJson != null) {
                        remoteHandsBySeat[seat] = cardsFromJson(seatHandJson)
                    }
                }
            }
            teamsThatPickedMorto.clear()
            if (teamsThatPickedMortoJson != null) {
                repeat(teamsThatPickedMortoJson.length()) { i -> teamsThatPickedMorto.add(teamsThatPickedMortoJson.optInt(i)) }
            }
            currentRoundId = savedRoundId.takeIf { it.isNotBlank() }
            remotePlayerSeats.clear()
            json.optJSONObject("remotePlayerSeats")?.let { seatsJson ->
                val keysIter = seatsJson.keys()
                while (keysIter.hasNext()) {
                    val id = keysIter.next()
                    remotePlayerSeats[id] = seatsJson.optInt(id)
                }
            }

            val hand = sortHandIfEnabled(cardsFromJson(handJson))
            val myMelds = handsFromJson(myTableMeldsJson)
            val oppMelds = handsFromJson(opponentTableMeldsJson)
            val discardPile = cardsFromJson(discardPileJson)
            val discard = discardPile.lastOrNull()
            val discardLocked = GameRulesEngine.isDiscardLocked(discard, currentConfig.gameType)
            val drawCheck = GameRulesEngine.canDrawFromDiscard(discard, currentConfig)

            _gameState.value = GameState(
                myHand = hand,
                myTableMelds = myMelds,
                opponentTableMelds = oppMelds,
                discardPile = discardPile,
                deckSize = deckSize,
                mortosLeft = mortosLeft,
                playerSeat = playerSeat,
                activeSeat = activeSeat,
                myScore = myScore,
                opponentScore = opponentScore,
                teamScores = teamScores,
                turnPhase = if (playerSeat == activeSeat) TurnPhase.DRAW else TurnPhase.WAITING_OPPONENT,
                feedbackMessage = "Partida recuperada localmente!",
                isDiscardLocked = discardLocked,
                canDrawFromDiscard = drawCheck.allowed,
                drawDiscardBlockedReason = drawCheck.reason,
                config = currentConfig,
                opponentLabel = opponentDisplayLabel()
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Cliente pede ao host o estado atual da mesa depois de uma reconexão. */
    fun requestReconnect() {
        _gameState.value = _gameState.value.copy(
            feedbackMessage = "Reconectando... aguardando estado do servidor."
        )
        networkRepository.sendMessage(NetworkMessage(playerId, "REQ_RECONNECT", buildSeatPayload(localSeat)))
    }

    /** Host reenvia a mão correta e o estado público para o jogador reconectado. */
    private fun handleReconnectRequest(message: NetworkMessage) {
        if (!isHost) return
        val state = _gameState.value
        val requestedSeat = parseSeatPayload(message.payload)
        val opponentSeat = trustedRemoteSeat(message, requestedSeat) ?: return
        val requestingPlayerId = message.senderId
        val remoteHand = remoteHandsBySeat[opponentSeat].orEmpty()
        // Em 4 jogadores, quem reconecta pode ser o parceiro do host (mesma equipe):
        // nesse caso a "minha mesa" dele e a mesa do host, nao a do time adversario.
        val reconnectingSameTeamAsHost = currentConfig.maxPlayers == 4 &&
            teamForSeat(opponentSeat) == teamForSeat(localSeat)
        val reconnectingOwnMelds = if (reconnectingSameTeamAsHost) state.myTableMelds else state.opponentTableMelds
        val reconnectingOtherMelds = if (reconnectingSameTeamAsHost) state.opponentTableMelds else state.myTableMelds
        val reconnectPayload = JSONObject()
            .put("v", 2)
            .put("roundId", currentRoundId.orEmpty())
            .put("config", currentConfig.serialize())
            .put("seat", opponentSeat)
            .put("activeSeat", state.activeSeat)
            .put("hand", cardsToJson(remoteHand))
            .put("myTableMelds", handsToJson(reconnectingOwnMelds))
            .put("hostTableMelds", handsToJson(reconnectingOtherMelds))
            .put("discard", state.discardPile.lastOrNull()?.id ?: "")
            .put("discardPile", cardsToJson(state.discardPile))
            .put("turnCard", state.turnCard?.id ?: "")
            .put("deckSize", state.deckSize)
            .put("mortosLeft", state.mortosLeft)
            .put("teamScores", JSONArray(state.teamScores))
            .put("isReconnect", true)
            .toString()
        networkRepository.sendMessageToPlayer(
            requestingPlayerId,
            NetworkMessage(playerId, "RECONNECT_STATE", reconnectPayload)
        )
    }

    /** Cliente recebe o estado do host e o aplica */
    private fun handleReconnectState(payload: String) {
        val json = runCatching { JSONObject(payload) }.getOrNull() ?: return
        val isReconnect = json.optBoolean("isReconnect", false)
        if (!isReconnect) return
        json.optString("roundId").takeIf { it.isNotBlank() }?.let { currentRoundId = it }

        currentConfig = runCatching {
            MatchConfig.deserialize(json.optString("config", currentConfig.serialize()))
        }.getOrElse { currentConfig }

        val seat = json.optInt("seat", localSeat)
        val activeSeat = json.optInt("activeSeat", 1)
        val handJson = json.optJSONArray("hand") ?: JSONArray()
        val myTableMeldsJson = json.optJSONArray("myTableMelds") ?: JSONArray()
        val hostTableMeldsJson = json.optJSONArray("hostTableMelds") ?: JSONArray()
        val discardId = json.optString("discard", "")
        val discardPileJson = json.optJSONArray("discardPile")
        val turnCardId = json.optString("turnCard", "")
        val deckSize = json.optInt("deckSize", 0)
        val mortosLeft = json.optInt("mortosLeft", 0)
        val teamScoresJson = json.optJSONArray("teamScores")

        if (seat in 0 until currentConfig.maxPlayers) {
            localSeat = seat
        }
        val hand = sortHandIfEnabled(cardsFromJson(handJson))
        val myMelds = handsFromJson(myTableMeldsJson)
        val hostMelds = handsFromJson(hostTableMeldsJson)
        val discardPile = discardPileJson
            ?.let { cardsFromJson(it) }
            ?: allCardsMap[discardId]?.let { listOf(it) }
            ?: emptyList()
        val discard = discardPile.lastOrNull()
        val turnCard = allCardsMap[turnCardId]
        val teamScores = if (teamScoresJson != null) {
            buildList { repeat(teamScoresJson.length()) { i -> add(teamScoresJson.optInt(i)) } }
        } else _gameState.value.teamScores

        val discardLocked = GameRulesEngine.isDiscardLocked(discard, currentConfig.gameType)
        val drawCheck = GameRulesEngine.canDrawFromDiscard(discard, currentConfig)

        _gameState.value = _gameState.value.copy(
            myHand = hand,
            selectedCards = emptySet(),
            myTableMelds = myMelds,
            opponentTableMelds = hostMelds,
            discardPile = discardPile,
            turnCard = turnCard,
            deckSize = deckSize,
            mortosLeft = mortosLeft,
            playerSeat = localSeat,
            activeSeat = activeSeat,
            teamScores = teamScores,
            turnPhase = if (localSeat == activeSeat) TurnPhase.DRAW else TurnPhase.WAITING_OPPONENT,
            feedbackMessage = "Reconectado! Continuando a partida...",
            isDiscardLocked = discardLocked,
            canDrawFromDiscard = drawCheck.allowed,
            drawDiscardBlockedReason = drawCheck.reason,
            config = currentConfig
        )
    }

    fun dispose() {
        // A tela cria esta ViewModel manualmente; ao sair, nenhuma coleta antiga
        // pode continuar processando mensagens da proxima partida.
        viewModelScope.cancel()
    }

    private fun formatBreakdownForLocal(breakdown: String, localTeam: Int): String {
        var formatted = breakdown
        repeat(currentConfig.maxPlayers.coerceAtLeast(2)) { seat ->
            val team = teamForSeat(seat)
            formatted = formatted.replace("[TEAM_$team]", teamDisplayLabel(team, localTeam))
            formatted = formatted.replace("[PLAYER_$seat]", seatDisplayLabel(seat, localSeat))
        }
        return formatted
    }

    companion object {
        // So pra contar tamanho do monte online (ver applyServerDeal); nunca
        // representa uma carta real e nunca deve ser lido por conteudo.
        private val PLACEHOLDER_DECK_CARD = Card(Suit.SPADES, Rank.ACE)

        fun hasSavedGame(context: Context): Boolean {
            return context.filesDir.listFiles { _, name -> name.startsWith("game_snapshot_") && name.endsWith(".json") }?.isNotEmpty() == true
        }

        fun getSavedGameInfo(context: Context): Pair<Boolean, MatchConfig>? {
            val file = context.filesDir.listFiles { _, name -> name.startsWith("game_snapshot_") && name.endsWith(".json") }?.firstOrNull() ?: return null
            return try {
                val json = JSONObject(file.readText())
                val configStr = json.optString("config", "")
                val config = if (configStr.isNotBlank()) MatchConfig.deserialize(configStr) else MatchConfig()
                val playerSeat = json.optInt("playerSeat", -1)
                val isHost = if (json.has("isHost")) json.optBoolean("isHost") else playerSeat == 0
                Pair(isHost, config)
            } catch (e: Exception) {
                null
            }
        }

        /**
         * Mapa playerId -> assento salvo pelo host, usado para re-semear
         * `rememberedPlayerSeats` do transporte antes de reabrir a sala: sem isso,
         * o primeiro cliente a reconectar depois de um reinício pegaria sempre o
         * assento 1, embaralhando quem senta onde numa partida de 4.
         */
        fun getSavedPlayerSeats(context: Context): Map<String, Int> {
            val file = context.filesDir.listFiles { _, name -> name.startsWith("game_snapshot_") && name.endsWith(".json") }?.firstOrNull() ?: return emptyMap()
            return try {
                val json = JSONObject(file.readText())
                val seatsJson = json.optJSONObject("remotePlayerSeats") ?: return emptyMap()
                buildMap {
                    val keysIter = seatsJson.keys()
                    while (keysIter.hasNext()) {
                        val id = keysIter.next()
                        put(id, seatsJson.optInt(id))
                    }
                }
            } catch (e: Exception) {
                emptyMap()
            }
        }

        fun clearSavedGame(context: Context) {
            context.filesDir.listFiles { _, name -> name.startsWith("game_snapshot_") && name.endsWith(".json") }?.forEach { it.delete() }
        }
    }
}

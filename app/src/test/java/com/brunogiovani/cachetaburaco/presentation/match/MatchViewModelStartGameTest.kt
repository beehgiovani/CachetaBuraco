package com.brunogiovani.cachetaburaco.presentation.match

import com.brunogiovani.cachetaburaco.data.network.SoloBotNetworkRepository
import com.brunogiovani.cachetaburaco.domain.models.BotDifficulty
import com.brunogiovani.cachetaburaco.domain.models.GameType
import com.brunogiovani.cachetaburaco.domain.models.MatchConfig
import com.brunogiovani.cachetaburaco.domain.models.Card
import com.brunogiovani.cachetaburaco.domain.models.Rank
import com.brunogiovani.cachetaburaco.domain.models.Suit
import com.brunogiovani.cachetaburaco.domain.repositories.ConnectionStatus
import com.brunogiovani.cachetaburaco.domain.repositories.DiscoveredRoom
import com.brunogiovani.cachetaburaco.domain.repositories.LocalNetworkRepository
import com.brunogiovani.cachetaburaco.domain.repositories.NetworkMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description

@OptIn(ExperimentalCoroutinesApi::class)
class MatchViewModelStartGameTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `host waits for client collector before sending game start`() = runTest {
        val repo = FakeLocalNetworkRepository(requiresClientReadyHandshake = true)
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.TRANCA, maxPlayers = 2)
        )

        viewModel.startGame()
        runCurrent()

        assertTrue(repo.privateClientMessages.isEmpty())
        assertTrue(viewModel.gameState.value.feedbackMessage.contains("Aguardando"))

        repo.emitIncoming(NetworkMessage("client-1", "CLIENT_READY", "", senderSeat = 1))
        runCurrent()

        assertEquals(1, repo.privateClientMessages.count { it.message.type == "GAME_START" })
        val startMessage = repo.privateClientMessages.single().message
        val startPayload = JSONObject(startMessage.payload)
        assertEquals(1, startPayload.getInt("seat"))
        assertTrue(startPayload.getString("roundId").isNotBlank())
        assertEquals(startPayload.getString("roundId"), startMessage.roundId)
    }

    @Test
    fun `restart cancellation closes dialog without clearing the current round`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "client",
            isHost = false,
            config = MatchConfig(gameType = GameType.TRANCA, maxPlayers = 2)
        )

        repo.emitIncoming(NetworkMessage("host", "RESTART_MATCH", "", senderSeat = 0))
        runCurrent()
        assertTrue(viewModel.gameState.value.showRestartMatchDialog)

        repo.emitIncoming(NetworkMessage("host", "RESTART_MATCH", "CANCEL", senderSeat = 0))
        runCurrent()

        assertFalse(viewModel.gameState.value.showRestartMatchDialog)
        assertTrue(viewModel.gameState.value.feedbackMessage.contains("cancelado"))
    }

    @Test
    fun `disposed view model stops consuming messages from later match`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "client",
            isHost = false,
            config = MatchConfig(gameType = GameType.TRANCA, maxPlayers = 2)
        )
        runCurrent()
        val stateBeforeDispose = viewModel.gameState.value

        viewModel.dispose()
        repo.emitIncoming(
            NetworkMessage(
                senderId = "host",
                type = "GAME_START",
                payload = gameStartPayload(
                    gameType = GameType.TRANCA,
                    seat = 1,
                    hand = listOf("ACE_CLUBS_BLACK"),
                    maxPlayers = 2
                ),
                senderSeat = 0
            )
        )
        runCurrent()

        assertEquals(stateBeforeDispose, viewModel.gameState.value)
    }

    @Test
    fun `cacheta starts with correct hand vira empty discard and no mortos`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.CACHETA, maxPlayers = 2)
        )

        viewModel.startGame()
        advanceUntilIdle()

        val state = viewModel.gameState.value
        assertEquals(9, state.myHand.size)
        assertEquals(0, state.mortosLeft)
        assertTrue(state.discardPile.isEmpty())
        assertNotNull(state.turnCard)
        assertEquals(85, state.deckSize)
        assertEquals(TurnPhase.WAITING_OPPONENT, state.turnPhase)

        val clientStart = JSONObject(repo.privateClientMessages.single().message.payload)
        assertEquals(9, clientStart.getJSONArray("hand").length())
        assertEquals("", clientStart.getString("discard"))
        assertNotEquals("", clientStart.getString("turnCard"))
        assertEquals(0, clientStart.getInt("mortosLeft"))
        assertFalse(clientStart.has("mortos"))
    }

    @Test
    fun `cacheta normalizes legacy room hand size and still deals nine cards`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val legacyConfig = MatchConfig.deserialize(
            "CACHETA,2,true,true,true,7,false,true,true,true,true,false,NORMAL,FREE,5"
        )
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = legacyConfig
        )

        viewModel.startGame()
        advanceUntilIdle()

        val state = viewModel.gameState.value
        assertEquals(9, legacyConfig.cardsPerPlayer)
        assertEquals(9, state.myHand.size)
        assertEquals(0, state.mortosLeft)
        assertEquals(85, state.deckSize)

        val clientStart = JSONObject(repo.privateClientMessages.single().message.payload)
        assertEquals(9, clientStart.getJSONArray("hand").length())
        assertEquals(0, clientStart.getInt("mortosLeft"))
    }

    @Test
    fun `buraco starts with eleven cards discard and hidden mortos`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 2)
        )

        viewModel.startGame()
        advanceUntilIdle()

        val state = viewModel.gameState.value
        assertEquals(11, state.myHand.size)
        assertEquals(2, state.mortosLeft)
        assertEquals(1, state.discardPile.size)
        assertEquals(null, state.turnCard)
        assertEquals(59, state.deckSize)

        val clientStart = JSONObject(repo.privateClientMessages.single().message.payload)
        assertEquals(11, clientStart.getJSONArray("hand").length())
        assertNotEquals("", clientStart.getString("discard"))
        assertEquals("", clientStart.getString("turnCard"))
        assertEquals(2, clientStart.getInt("mortosLeft"))
        assertFalse("Cliente não pode receber cartas dos mortos no start", clientStart.has("mortos"))
    }

    @Test
    fun `online host requests server deal instead of dealing locally and applies hand mortos and deck from it`() = runTest {
        // No transporte online, quem embaralha e distribui e a RPC start_online_round
        // (0020-0023), nao o proprio aparelho. O host so aplica o que o servidor
        // decidiu -- nao manda GAME_START pelo caminho de sempre porque a RPC ja fez
        // isso sozinha do lado do banco.
        val repo = FakeLocalNetworkRepository(isOnlineTransport = true)
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 2)
        )

        val hostHand = listOf(cardId(Rank.FOUR, Suit.HEARTS), cardId(Rank.FIVE, Suit.HEARTS))
        val remoteHand = listOf(
            cardId(Rank.SIX, Suit.HEARTS),
            cardId(Rank.SEVEN, Suit.HEARTS),
            cardId(Rank.EIGHT, Suit.HEARTS)
        )
        val morto0 = listOf(cardId(Rank.NINE, Suit.HEARTS))
        val morto1 = listOf(cardId(Rank.TEN, Suit.HEARTS))
        val deck = listOf(cardId(Rank.JACK, Suit.HEARTS), cardId(Rank.QUEEN, Suit.HEARTS))

        repo.serverDealResult = JSONObject().apply {
            put("roundId", "round-server-1")
            put("hand", JSONArray(hostHand))
            put("hands", JSONObject().apply {
                put("0", JSONArray(hostHand))
                put("1", JSONArray(remoteHand))
            })
            put("seat", 0)
            put("activeSeat", 1)
            put("discard", cardId(Rank.TWO, Suit.CLUBS))
            put("turnCard", "")
            put("deck", JSONArray(deck))
            put("deckSize", deck.size)
            put("mortosLeft", 2)
            put("mortos", JSONArray().apply {
                put(JSONArray(morto0))
                put(JSONArray(morto1))
            })
            put("team0Melds", JSONArray())
            put("team1Melds", JSONArray())
        }.toString()

        viewModel.startGame()
        runCurrent()

        assertEquals(1, repo.serverDealCalls)
        val state = viewModel.gameState.value
        assertEquals(hostHand.toSet(), state.myHand.map { it.id }.toSet())
        assertEquals(2, state.mortosLeft)
        assertEquals(deck.size, state.deckSize)
        assertEquals(remoteHand.size, state.opponentHandCount)
        assertEquals(cardId(Rank.TWO, Suit.CLUBS), state.discardPile.single().id)
        // A RPC ja publicou GAME_START/PUBLIC_STATE do lado do banco -- o host nao
        // manda de novo pelo caminho local de sempre.
        assertTrue(repo.privateClientMessages.isEmpty())
        assertTrue(repo.broadcastMessages.none { it.type == "GAME_START" })
    }

    @Test
    fun `online tranca host applies team melds from server deal for red threes already melded`() = runTest {
        // Cobre o outro caso que so o retorno da RPC resolve: o host nunca ve a
        // propria transmissao de PUBLIC_STATE de volta, entao os jogos de 3
        // vermelho ja formados pelo servidor (0023) precisam vir no retorno da
        // RPC, nao so no evento de rede.
        val repo = FakeLocalNetworkRepository(isOnlineTransport = true)
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.TRANCA, maxPlayers = 2)
        )

        val hostHand = listOf(cardId(Rank.FOUR, Suit.CLUBS), cardId(Rank.FIVE, Suit.CLUBS))
        val remoteHand = listOf(cardId(Rank.SIX, Suit.CLUBS), cardId(Rank.SEVEN, Suit.CLUBS))
        val hostRedThree = cardId(Rank.THREE, Suit.HEARTS)

        repo.serverDealResult = JSONObject().apply {
            put("roundId", "round-server-2")
            put("hand", JSONArray(hostHand))
            put("hands", JSONObject().apply {
                put("0", JSONArray(hostHand))
                put("1", JSONArray(remoteHand))
            })
            put("seat", 0)
            put("activeSeat", 1)
            put("discard", cardId(Rank.TWO, Suit.SPADES))
            put("turnCard", "")
            put("deck", JSONArray(listOf(cardId(Rank.JACK, Suit.SPADES))))
            put("deckSize", 1)
            put("mortosLeft", 2)
            put("mortos", JSONArray().apply {
                put(JSONArray(listOf(cardId(Rank.NINE, Suit.SPADES))))
                put(JSONArray(listOf(cardId(Rank.TEN, Suit.SPADES))))
            })
            // Time do host (seat 0) e time 0 -- team0Melds e a mesa da propria equipe.
            put("team0Melds", JSONArray().apply { put(JSONArray(listOf(hostRedThree))) })
            put("team1Melds", JSONArray())
        }.toString()

        viewModel.startGame()
        runCurrent()

        val state = viewModel.gameState.value
        assertEquals(1, state.myTableMelds.size)
        assertEquals(hostRedThree, state.myTableMelds.single().single().id)
        assertTrue(state.opponentTableMelds.isEmpty())
    }

    @Test
    fun `online host requests server draw for its own turn instead of drawing from local deck`() = runTest {
        // Fase 3a: online_draw_deck_card (0025) decide a compra durante a
        // rodada -- o host nao chama mais drawTopCard()/prepareDeckForDraw()
        // pra si mesmo, so aplica o que a RPC devolveu.
        val repo = FakeLocalNetworkRepository(isOnlineTransport = true)
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 2)
        )
        viewModel.startGame()
        runCurrent()

        viewModel.mutableGameState().value = viewModel.gameState.value.copy(
            turnPhase = TurnPhase.DRAW,
            activeSeat = 0
        )
        val handSizeBeforeDraw = viewModel.gameState.value.myHand.size
        val drawnCardId = cardId(Rank.KING, Suit.SPADES)
        repo.serverDrawResult = JSONObject().apply {
            put("status", "OK")
            put("card", drawnCardId)
            put("deckSize", 9)
            put("mortosLeft", 1)
            put("discardPile", JSONArray())
        }.toString()

        viewModel.drawFromDeck()
        runCurrent()

        assertEquals(listOf(0), repo.serverDrawCalls)
        assertEquals(handSizeBeforeDraw + 1, viewModel.gameState.value.myHand.size)
        assertTrue(viewModel.gameState.value.myHand.any { it.id == drawnCardId })
        assertEquals(9, viewModel.gameState.value.deckSize)
        assertEquals(1, viewModel.gameState.value.mortosLeft)
        assertEquals(TurnPhase.ACTION, viewModel.gameState.value.turnPhase)
    }

    @Test
    fun `online host draw exhausted status begins count only round instead of failing silently`() = runTest {
        val repo = FakeLocalNetworkRepository(isOnlineTransport = true)
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 2)
        )
        viewModel.startGame()
        runCurrent()

        viewModel.mutableGameState().value = viewModel.gameState.value.copy(
            turnPhase = TurnPhase.DRAW,
            activeSeat = 0
        )
        repo.serverDrawResult = JSONObject().apply { put("status", "EXHAUSTED") }.toString()

        viewModel.drawFromDeck()
        runCurrent()

        assertTrue(viewModel.gameState.value.feedbackMessage.contains("contagem", ignoreCase = true))
    }

    @Test
    fun `online host serves remote client draw via server RPC without sending SERVE_CARD itself`() = runTest {
        // A propria RPC ja publica SERVE_CARD direto pro assento certo (ver
        // migration 0025) -- o host so espelha o efeito local, sem mandar
        // outra mensagem de rede pra isso.
        val repo = FakeLocalNetworkRepository(isOnlineTransport = true)
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 2)
        )
        viewModel.startGame()
        runCurrent()
        viewModel.mutableGameState().value = viewModel.gameState.value.copy(activeSeat = 1)

        val drawnCardId = cardId(Rank.TEN, Suit.CLUBS)
        repo.serverDrawResult = JSONObject().apply {
            put("status", "OK")
            put("card", drawnCardId)
            put("deckSize", 8)
            put("mortosLeft", 2)
            put("discardPile", JSONArray())
        }.toString()

        repo.emitIncoming(
            NetworkMessage("client-1", "REQ_DRAW_DECK", JSONObject().put("seat", 1).toString(), senderSeat = 1)
        )
        runCurrent()

        assertEquals(listOf(1), repo.serverDrawCalls)
        assertEquals(listOf(drawnCardId), viewModel.remoteHandsForTest()[1]?.map { it.id })
        assertEquals(8, viewModel.gameState.value.deckSize)
        assertTrue(repo.privatePlayerMessages.none { it.message.type == "SERVE_CARD" })
    }

    @Test
    fun `online host applies server morto result to its own hand and red three melds`() = runTest {
        // Fase 3b: online_take_morto (0026) decide o conteudo do morto -- o
        // host nao chama mais mortos.removeAt(0)/prepareMortoForPickup()
        // sozinho pra si mesmo, so aplica o que a RPC devolveu.
        val repo = FakeLocalNetworkRepository(isOnlineTransport = true)
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.TRANCA, maxPlayers = 2)
        )
        viewModel.startGame()
        runCurrent()
        viewModel.mortosForTest().add(listOf(Card(suit = Suit.SPADES, rank = Rank.NINE)))

        val mortoHand = List(11) { index -> cardId(Rank.entries[index % 13], Suit.HEARTS) }
        val redThree = cardId(Rank.THREE, Suit.DIAMONDS)
        val rawJson = JSONObject().apply {
            put("status", "OK")
            put("hand", JSONArray(mortoHand))
            put("redThreeMelds", JSONArray().apply { put(JSONArray(listOf(redThree))) })
            put("team", 0)
            put("mortosLeft", 0)
            put("deckSize", 15)
        }.toString()

        viewModel.invokeApplyServerMortoResultForOwnSeat(rawJson, "Jogo baixado!")

        val state = viewModel.gameState.value
        assertEquals(mortoHand.toSet(), state.myHand.map { it.id }.toSet())
        assertEquals(1, state.myTableMelds.size)
        assertEquals(redThree, state.myTableMelds.single().single().id)
        assertEquals(0, state.mortosLeft)
        assertEquals(15, state.deckSize)
        assertTrue(viewModel.teamsThatPickedMortoForTest().contains(0))
        assertTrue(viewModel.mortosForTest().isEmpty())
    }

    @Test
    fun `online host applies server morto result for remote seat mirroring team tables`() = runTest {
        val repo = FakeLocalNetworkRepository(isOnlineTransport = true)
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.TRANCA, maxPlayers = 4)
        )
        viewModel.startGame()
        runCurrent()
        viewModel.mortosForTest().add(listOf(Card(suit = Suit.SPADES, rank = Rank.NINE)))

        val mortoHand = List(11) { index -> cardId(Rank.entries[index % 13], Suit.CLUBS) }
        val redThree = cardId(Rank.THREE, Suit.HEARTS)
        val rawJson = JSONObject().apply {
            put("status", "OK")
            put("hand", JSONArray(mortoHand))
            put("redThreeMelds", JSONArray().apply { put(JSONArray(listOf(redThree))) })
            put("team", 1)
            put("mortosLeft", 1)
            put("deckSize", 20)
        }.toString()

        // Time 1 (assentos 1 e 3) e o adversario do host (assento 0, time 0).
        viewModel.invokeApplyServerMortoResultForRemoteSeat(1, 1, rawJson)

        val state = viewModel.gameState.value
        assertEquals(mortoHand.toSet(), viewModel.remoteHandsForTest()[1]?.map { it.id }?.toSet())
        assertEquals(1, state.opponentTableMelds.size)
        assertEquals(redThree, state.opponentTableMelds.single().single().id)
        assertTrue(state.opponentPickedMorto)
        assertTrue(viewModel.teamsThatPickedMortoForTest().contains(1))
    }

    @Test
    fun `online host serves remote client morto request via server RPC without sending SERVE_MORTO itself`() = runTest {
        // A propria RPC ja publica SERVE_MORTO direto pro assento certo (ver
        // migration 0026) -- o host so espelha o efeito local, sem mandar
        // outra mensagem de rede pra isso.
        val repo = FakeLocalNetworkRepository(isOnlineTransport = true)
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.TRANCA, maxPlayers = 2)
        )
        viewModel.startGame()
        runCurrent()
        viewModel.mortosForTest().add(listOf(Card(suit = Suit.SPADES, rank = Rank.NINE)))
        viewModel.remoteHandsForTest()[1] = emptyList()

        val mortoHand = List(11) { index -> cardId(Rank.entries[index % 13], Suit.CLUBS) }
        repo.serverMortoResult = JSONObject().apply {
            put("status", "OK")
            put("hand", JSONArray(mortoHand))
            put("redThreeMelds", JSONArray())
            put("team", 1)
            put("mortosLeft", 0)
            put("deckSize", 20)
        }.toString()

        repo.emitIncoming(
            NetworkMessage(
                "client-1",
                "REQ_PICK_MORTO",
                JSONObject().put("v", 1).put("seat", 1).put("indirect", false).toString(),
                senderSeat = 1
            )
        )
        runCurrent()

        assertEquals(listOf(1), repo.serverMortoCalls)
        assertEquals(mortoHand.toSet(), viewModel.remoteHandsForTest()[1]?.map { it.id }?.toSet())
        assertTrue(repo.privatePlayerMessages.none { it.message.type == "SERVE_MORTO" })
    }

    @Test
    fun `tranca without automatic red threes deals exactly eleven physical cards`() = runTest {
        val viewModel = MatchViewModel(
            networkRepository = FakeLocalNetworkRepository(),
            playerId = "host",
            isHost = true,
            config = MatchConfig(
                gameType = GameType.TRANCA,
                maxPlayers = 2,
                autoMeldTrancaRedThrees = false
            )
        )
        val redThree = Card(Suit.HEARTS, Rank.THREE)
        val controlledDeck = listOf(
            redThree,
            Card(Suit.CLUBS, Rank.ACE),
            Card(Suit.CLUBS, Rank.TWO),
            Card(Suit.CLUBS, Rank.FOUR),
            Card(Suit.CLUBS, Rank.FIVE),
            Card(Suit.CLUBS, Rank.SIX),
            Card(Suit.CLUBS, Rank.SEVEN),
            Card(Suit.CLUBS, Rank.EIGHT),
            Card(Suit.CLUBS, Rank.NINE),
            Card(Suit.CLUBS, Rank.TEN),
            Card(Suit.CLUBS, Rank.JACK)
        )
        viewModel.setPrivateField("masterDeck", controlledDeck.toMutableList())
        val method = MatchViewModel::class.java.getDeclaredMethod(
            "dealCards",
            Int::class.javaPrimitiveType,
            Boolean::class.javaPrimitiveType
        )
        method.isAccessible = true

        @Suppress("UNCHECKED_CAST")
        val hand = method.invoke(viewModel, 11, true) as List<Card>

        assertEquals(11, hand.size)
        assertTrue(hand.contains(redThree))
    }

    @Test
    fun `host serves morto privately without broadcasting hidden cards`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 2)
        )
        viewModel.startGame()
        advanceUntilIdle()

        repo.emitIncoming(NetworkMessage("client-1", "REQ_PICK_MORTO", """{"v":1,"seat":1}""", messageId = "early-morto-request", senderSeat = 1))
        advanceUntilIdle()
        assertEquals(0, repo.privatePlayerMessages.count { it.playerId == "client-1" && it.message.type == "SERVE_MORTO" })

        // O host acompanha a mao privada do cliente; aqui preparo o estado canonico
        // de uma jogada que acabou de zerar a mao, sem forjar um jogo invalido.
        viewModel.remoteHandsForTest()[1] = emptyList()

        repo.emitIncoming(NetworkMessage("client-1", "REQ_PICK_MORTO", """{"v":1,"seat":1}""", senderSeat = 1))
        advanceUntilIdle()
        repo.emitIncoming(NetworkMessage("client-1", "REQ_PICK_MORTO", """{"v":1,"seat":1}""", messageId = "second-morto-request", senderSeat = 1))
        advanceUntilIdle()

        assertEquals(1, repo.privatePlayerMessages.count { it.playerId == "client-1" && it.message.type == "SERVE_MORTO" })
        val servedMorto = repo.privatePlayerMessages.single { it.playerId == "client-1" && it.message.type == "SERVE_MORTO" }.message
        val servedPayload = JSONObject(servedMorto.payload)
        assertEquals(11, servedPayload.getJSONArray("hand").length())
        assertEquals(1, servedPayload.getInt("mortosLeft"))

        val publicPick = repo.broadcastMessages.last { it.type == "PICK_MORTO" }
        val publicPayload = JSONObject(publicPick.payload)
        assertEquals(1, publicPayload.getInt("mortosLeft"))
        assertFalse(publicPayload.has("hand"))
        assertEquals(1, viewModel.gameState.value.mortosLeft)
    }

    @Test
    fun `client ignores duplicated serve morto even with different message ids`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "client-1",
            isHost = false,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 2)
        )
        val morto = mortoPayload()

        repo.emitIncoming(NetworkMessage("host", "GAME_START", gameStartPayload(
            seat = 1,
            hand = emptyList(),
            activeSeat = 1,
            maxPlayers = 2
        )))
        advanceUntilIdle()

        repo.emitIncoming(NetworkMessage("host", "SERVE_MORTO", morto, messageId = "morto-a"))
        repo.emitIncoming(NetworkMessage("host", "SERVE_MORTO", morto, messageId = "morto-b"))
        advanceUntilIdle()

        assertEquals(11, viewModel.gameState.value.myHand.size)
        assertEquals(1, viewModel.gameState.value.mortosLeft)
    }

    @Test
    fun `host restores morto when private delivery is not confirmed`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 2)
        )
        viewModel.startGame()
        advanceUntilIdle()
        val originalMorto = viewModel.mortosForTest().first().map(Card::id)
        viewModel.remoteHandsForTest()[1] = emptyList()
        repo.confirmedDeliveryResult = false

        repo.emitIncoming(
            NetworkMessage(
                "client-1",
                "REQ_PICK_MORTO",
                """{"v":1,"seat":1}""",
                messageId = "morto-without-ack",
                senderSeat = 1
            )
        )
        advanceUntilIdle()

        assertEquals(2, viewModel.gameState.value.mortosLeft)
        assertEquals(originalMorto, viewModel.mortosForTest().first().map(Card::id))
        assertTrue(viewModel.remoteHandsForTest()[1].orEmpty().isEmpty())
        assertFalse(viewModel.teamsThatPickedMortoForTest().contains(1))
        assertTrue(repo.confirmedPlayerAttempts.any { it.message.type == "SERVE_MORTO" })
        assertFalse(repo.privatePlayerMessages.any { it.message.type == "SERVE_MORTO" })
    }

    @Test
    fun `host restores deck card when private draw delivery is not confirmed`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 2)
        )
        viewModel.startGame()
        advanceUntilIdle()
        val remoteHandBefore = viewModel.remoteHandsForTest().getValue(1).map(Card::id)
        val reservedCard = Card(Suit.CLUBS, Rank.QUEEN)
        viewModel.masterDeckForTest().apply {
            clear()
            add(reservedCard)
        }
        viewModel.mutableGameState().value = viewModel.gameState.value.copy(
            activeSeat = 1,
            deckSize = 1,
            opponentHandCount = remoteHandBefore.size
        )
        repo.confirmedDeliveryResult = false

        repo.emitIncoming(
            NetworkMessage(
                "client-1",
                "REQ_DRAW_DECK",
                """{"v":1,"seat":1}""",
                messageId = "draw-without-ack",
                senderSeat = 1
            )
        )
        advanceUntilIdle()

        assertEquals(listOf(reservedCard.id), viewModel.masterDeckForTest().map(Card::id))
        assertEquals(remoteHandBefore, viewModel.remoteHandsForTest().getValue(1).map(Card::id))
        assertFalse(viewModel.deckServedSeatsForTest().contains(1))
        assertEquals(1, viewModel.gameState.value.deckSize)
        assertTrue(repo.confirmedPlayerAttempts.any { it.message.type == "SERVE_CARD" })
        assertFalse(repo.privatePlayerMessages.any { it.message.type == "SERVE_CARD" })
    }

    @Test
    fun `client accepts private morto even when public notice arrives first`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "client-1",
            isHost = false,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 2)
        )
        repo.emitIncoming(
            NetworkMessage(
                senderId = "host",
                type = "GAME_START",
                payload = gameStartPayload(seat = 1, hand = emptyList(), activeSeat = 1, maxPlayers = 2),
                senderSeat = 0
            )
        )
        advanceUntilIdle()

        repo.emitIncoming(
            NetworkMessage(
                senderId = "host",
                type = "PICK_MORTO",
                payload = """{"v":1,"mortosLeft":1,"seat":1}""",
                senderSeat = 0
            )
        )
        repo.emitIncoming(
            NetworkMessage(
                senderId = "host",
                type = "SERVE_MORTO",
                payload = mortoPayload(),
                senderSeat = 0
            )
        )
        advanceUntilIdle()

        assertEquals(11, viewModel.gameState.value.myHand.size)
        assertEquals(1, viewModel.gameState.value.mortosLeft)
        assertFalse(viewModel.gameState.value.opponentPickedMorto)
        assertEquals(1, viewModel.gameState.value.mortoNoticeSeat)
        assertEquals(1, viewModel.gameState.value.mortoNoticeId)
    }

    @Test
    fun `team is not penalized when either partner picked morto`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 4)
        )
        viewModel.startGame()
        advanceUntilIdle()

        viewModel.teamsThatPickedMortoForTest().add(0)
        viewModel.invokeBeginCountOnlyRound()
        (1..3).forEach { seat ->
            repo.emitIncoming(
                NetworkMessage(
                    senderId = "player-$seat",
                    type = "REPLY_COUNT_ROUND",
                    payload = roundReportPayload("player-$seat", seat),
                    senderSeat = seat
                )
            )
        }
        advanceUntilIdle()

        val summary = JSONObject(repo.broadcastMessages.last { it.type == "ROUND_SUMMARY" }.payload)
        val breakdown = summary.getString("breakdown")

        assertFalse(breakdown.contains("Equipe [TEAM_0]: morto não pego -100"))
        assertTrue(breakdown.contains("Equipe [TEAM_1]: morto não pego -100"))
    }

    @Test
    fun `team is penalized once when no partner picked morto`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 4)
        )
        viewModel.startGame()
        advanceUntilIdle()

        viewModel.invokeBeginCountOnlyRound()
        (1..3).forEach { seat ->
            repo.emitIncoming(
                NetworkMessage(
                    senderId = "player-$seat",
                    type = "REPLY_COUNT_ROUND",
                    payload = roundReportPayload("player-$seat", seat),
                    senderSeat = seat
                )
            )
        }
        advanceUntilIdle()

        val summary = JSONObject(repo.broadcastMessages.last { it.type == "ROUND_SUMMARY" }.payload)
        val breakdown = summary.getString("breakdown")

        assertTrue(breakdown.contains("Equipe [TEAM_0]: morto não pego -100"))
        assertTrue(breakdown.contains("Equipe [TEAM_1]: morto não pego -100"))
    }

    @Test
    fun `duplicate network message id is ignored`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "client-1",
            isHost = false,
            config = MatchConfig(gameType = GameType.TRANCA, maxPlayers = 2)
        )
        repo.emitIncoming(
            NetworkMessage(
                senderId = "host",
                type = "GAME_START",
                payload = gameStartPayload(
                    gameType = GameType.TRANCA,
                    seat = 1,
                    activeSeat = 1,
                    maxPlayers = 2
                ),
                senderSeat = 0
            )
        )
        advanceUntilIdle()

        val meldCards = listOf(
            Card(suit = Suit.HEARTS, rank = Rank.FOUR),
            Card(suit = Suit.HEARTS, rank = Rank.FIVE),
            Card(suit = Suit.HEARTS, rank = Rank.SIX)
        )
        val cardsJson = JSONArray().apply {
            meldCards.forEach { put(it.id) }
        }
        val payload = JSONObject()
            .put("v", 1)
            .put("cards", cardsJson)
            .put("seat", 0)
            .put("team", 0)
            .put("replaceIndex", -1)
            .toString()
        val duplicated = NetworkMessage(
            senderId = "host",
            type = "MELD",
            payload = payload,
            messageId = "same-message-id",
            senderSeat = 0
        )

        repo.emitIncoming(duplicated)
        repo.emitIncoming(duplicated)
        advanceUntilIdle()

        assertEquals(1, viewModel.gameState.value.opponentTableMelds.size)
        assertEquals(meldCards.map { it.id }, viewModel.gameState.value.opponentTableMelds.single().map { it.id })
    }

    @Test
    fun `partner meld appears on my team table and opponent meld appears on opponent table`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "client-2",
            isHost = false,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 4)
        )
        repo.emitIncoming(NetworkMessage("host", "GAME_START", gameStartPayload(seat = 2)))
        advanceUntilIdle()

        repo.emitIncoming(NetworkMessage("partner-seat-0", "MELD", meldPayload(seat = 0)))
        advanceUntilIdle()

        assertEquals(1, viewModel.gameState.value.myTableMelds.size)
        assertEquals(0, viewModel.gameState.value.opponentTableMelds.size)

        repo.emitIncoming(NetworkMessage("opponent-seat-1", "MELD", meldPayload(seat = 1)))
        advanceUntilIdle()

        assertEquals(1, viewModel.gameState.value.myTableMelds.size)
        assertEquals(1, viewModel.gameState.value.opponentTableMelds.size)
    }

    @Test
    fun `host resolves partner win using partner team table not opponent team table`() = runTest {
        // resolveRemoteEmptyHand (chamada por handleRemoteMeld, o caminho real do host)
        // ja usa remoteTableForSeat(actorSeat), que escolhe myTableMelds/opponentTableMelds
        // pelo time do ator. Este teste protege esse comportamento contra regressao.
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 4)
        )
        viewModel.startGame()
        advanceUntilIdle()

        // Time do host = assentos 0 e 2. A mesa do parceiro (assento 2) precisa
        // ser lida de myTableMelds, nunca de opponentTableMelds (equipe adversaria).
        viewModel.mutableGameState().value = viewModel.mutableGameState().value.copy(
            myTableMelds = emptyList(),
            opponentTableMelds = emptyList(),
            activeSeat = 2
        )
        viewModel.mortosForTest().clear()
        viewModel.teamsThatPickedMortoForTest().add(0)
        viewModel.deckServedSeatsForTest().add(2)
        val meldCards = cleanCanastraCards()
        viewModel.remoteHandsForTest()[2] = meldCards

        val payload = JSONObject()
            .put("v", 1)
            .put("cards", JSONArray().apply { meldCards.forEach { put(it.id) } })
            .put("seat", 2)
            .put("team", 0)
            .put("replaceIndex", -1)
            .toString()
        repo.emitIncoming(NetworkMessage("partner-seat-2", "MELD", payload, senderSeat = 2))
        advanceUntilIdle()

        assertEquals(0, viewModel.remoteHandsForTest()[2]?.size ?: -1)
        assertEquals(1, viewModel.gameState.value.myTableMelds.size)
        assertFalse(
            "Parceiro fechou com canastra limpa na propria mesa (myTableMelds): deveria declarar vitoria, nao contagem.",
            viewModel.pendingCountOnlyRoundForTest()
        )
    }

    @Test
    fun `host resends reconnecting partner their own team table not the opposing team table`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 4)
        )
        viewModel.startGame()
        advanceUntilIdle()

        val hostTeamMeld = listOf(Card(suit = Suit.CLUBS, rank = Rank.FOUR))
        val opposingTeamMeld = listOf(Card(suit = Suit.SPADES, rank = Rank.FIVE))
        viewModel.mutableGameState().value = viewModel.mutableGameState().value.copy(
            myTableMelds = listOf(hostTeamMeld),
            opponentTableMelds = listOf(opposingTeamMeld)
        )

        // Assento 2 e o parceiro do host (mesmo time: 0 e 2 sao pares).
        repo.emitIncoming(
            NetworkMessage(
                senderId = "partner-seat-2",
                type = "REQ_RECONNECT",
                payload = JSONObject().put("v", 1).put("seat", 2).toString(),
                senderSeat = 2
            )
        )
        advanceUntilIdle()

        val reconnectState = repo.privatePlayerMessages.last { it.message.type == "RECONNECT_STATE" }.message
        val payload = JSONObject(reconnectState.payload)
        val myMeldsReceived = payload.getJSONArray("myTableMelds")
        val hostMeldsReceived = payload.getJSONArray("hostTableMelds")

        assertEquals(1, myMeldsReceived.length())
        assertEquals(
            "O parceiro deve receber a mesa do PROPRIO time (hostTeamMeld) como myTableMelds, nao a mesa adversaria.",
            hostTeamMeld.single().id,
            myMeldsReceived.getJSONArray(0).getString(0)
        )
        assertEquals(
            "O parceiro deve receber a mesa adversaria como hostTableMelds (viram opponentTableMelds no cliente).",
            opposingTeamMeld.single().id,
            hostMeldsReceived.getJSONArray(0).getString(0)
        )
    }

    @Test
    fun `opponent label reflects bot repository flag instead of transport class name`() = runTest {
        val botViewModel = MatchViewModel(
            networkRepository = FakeLocalNetworkRepository(isBotRepository = true),
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 2)
        )
        assertEquals("Máquina", botViewModel.gameState.value.opponentLabel)

        val humanViewModel = MatchViewModel(
            networkRepository = FakeLocalNetworkRepository(),
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 2)
        )
        assertEquals("Oponente", humanViewModel.gameState.value.opponentLabel)
    }

    @Test
    fun `local snapshot save and restore is only allowed for wifi local transport`() = runTest {
        // Bug real: "Continuar Partida Salva" no menu sempre reconectava pelo
        // transporte Wi-Fi local, nao importa qual transporte a partida salva
        // realmente usava -- pra bot/online isso deixava a mesa visivel mas sem
        // nenhuma conexao de verdade por tras (mesa travada). Bot ja tinha essa
        // exclusao (via isMachineMatch()); faltava a mesma coisa pro online, que
        // tem um jeito proprio de reconectar (REQ_RECONNECT) e nao deveria nem
        // gravar um retrato local que pode ficar desatualizado com o baralho
        // agora vivendo no servidor.
        fun canUseLocalSnapshot(viewModel: MatchViewModel): Boolean {
            val method = MatchViewModel::class.java.getDeclaredMethod("canUseLocalSnapshot")
            method.isAccessible = true
            return method.invoke(viewModel) as Boolean
        }

        val wifiViewModel = MatchViewModel(
            networkRepository = FakeLocalNetworkRepository(),
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 2)
        )
        assertTrue(canUseLocalSnapshot(wifiViewModel))

        val botViewModel = MatchViewModel(
            networkRepository = FakeLocalNetworkRepository(isBotRepository = true),
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 2)
        )
        assertFalse(canUseLocalSnapshot(botViewModel))

        val onlineViewModel = MatchViewModel(
            networkRepository = FakeLocalNetworkRepository(isOnlineTransport = true),
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 2)
        )
        assertFalse(canUseLocalSnapshot(onlineViewModel))
    }

    @Test
    fun `snapshot json round trip restores full authoritative state not just display fields`() = runTest {
        // Bug real: o retrato salvo so guardava campos de exibicao (mao propria,
        // contadores). Depois de "Continuar Partida Salva" o host recuperado
        // ficava sem monte, sem mortos e sem saber a mao dos outros assentos --
        // qualquer compra ou validacao subsequente quebrava. Este teste garante
        // que masterDeck, mortos, remoteHandsBySeat, remotePlayerSeats e
        // currentRoundId sobrevivem a uma volta completa serializar/restaurar.
        val host = MatchViewModel(
            networkRepository = FakeLocalNetworkRepository(),
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 2)
        )
        host.startGame()
        runCurrent()

        assertTrue(host.masterDeckForTest().isNotEmpty())
        assertTrue(host.mortosForTest().isNotEmpty())
        assertTrue(host.remoteHandsForTest()[1]?.isNotEmpty() == true)

        host.remotePlayerSeatsForTest()["client-1"] = 1

        val json = host.invokeBuildSnapshotJson(host.gameState.value)

        val restored = MatchViewModel(
            networkRepository = FakeLocalNetworkRepository(),
            playerId = "client-1",
            isHost = false,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 2)
        )
        assertTrue(restored.invokeApplySnapshotJson(json))

        assertEquals(host.masterDeckForTest().map { it.id }, restored.masterDeckForTest().map { it.id })
        assertEquals(
            host.mortosForTest().map { morto -> morto.map { it.id } },
            restored.mortosForTest().map { morto -> morto.map { it.id } }
        )
        assertEquals(host.remoteHandsForTest()[1]?.map { it.id }, restored.remoteHandsForTest()[1]?.map { it.id })
        assertEquals(mapOf("client-1" to 1), restored.remotePlayerSeatsForTest())
        assertEquals(host.currentRoundIdForTest(), restored.currentRoundIdForTest())
        assertNotNull(restored.currentRoundIdForTest())
    }

    // ─── Matriz de tentativas adulteradas (host local/bot) ──────────────────
    // O lado Supabase ja cobre esses cenarios nas migrations 0012-0019; estes
    // testes provam que o mesmo host-autoridade tambem os recusa no transporte
    // local (Wi-Fi/maquina), que nao passa pelas checagens em SQL.

    @Test
    fun `host rejects discard of unknown card id`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 2)
        )
        viewModel.startGame()
        advanceUntilIdle()
        // Buraco abre com uma carta no lixo (vira inicial); a rejeicao nao pode somar outra.
        val discardSizeBeforeAttack = viewModel.gameState.value.discardPile.size

        viewModel.mutableGameState().value = viewModel.mutableGameState().value.copy(activeSeat = 1)
        viewModel.deckServedSeatsForTest().add(1)

        val payload = JSONObject().put("card", "NOT_A_REAL_CARD_ID").put("seat", 1).toString()
        repo.emitIncoming(NetworkMessage("client-1", "DISCARD", payload, senderSeat = 1))
        advanceUntilIdle()

        assertEquals("Descarte recusado: carta desconhecida.", viewModel.gameState.value.feedbackMessage)
        assertEquals(discardSizeBeforeAttack, viewModel.gameState.value.discardPile.size)
    }

    @Test
    fun `host rejects discard of card the seat does not actually hold`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 2)
        )
        viewModel.startGame()
        advanceUntilIdle()

        viewModel.mutableGameState().value = viewModel.mutableGameState().value.copy(activeSeat = 1)
        viewModel.deckServedSeatsForTest().add(1)
        viewModel.remoteHandsForTest()[1] = listOf(Card(suit = Suit.CLUBS, rank = Rank.TWO))

        val phantomCard = Card(suit = Suit.SPADES, rank = Rank.KING)
        val payload = JSONObject().put("card", phantomCard.id).put("seat", 1).toString()
        repo.emitIncoming(NetworkMessage("client-1", "DISCARD", payload, senderSeat = 1))
        advanceUntilIdle()

        assertEquals(
            "Descarte recusado: a carta nao esta na mao do jogador.",
            viewModel.gameState.value.feedbackMessage
        )
        assertEquals(1, viewModel.remoteHandsForTest()[1]?.size)
    }

    @Test
    fun `host rejects action when claimed seat does not match authenticated transport seat`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 2)
        )
        viewModel.startGame()
        advanceUntilIdle()

        viewModel.mutableGameState().value = viewModel.mutableGameState().value.copy(activeSeat = 1)
        viewModel.deckServedSeatsForTest().add(1)
        val realCard = Card(suit = Suit.CLUBS, rank = Rank.TWO)
        viewModel.remoteHandsForTest()[1] = listOf(realCard)

        // O transporte autentica o remetente no assento 1, mas o payload alega o assento 0 (o host).
        val payload = JSONObject().put("card", realCard.id).put("seat", 0).toString()
        repo.emitIncoming(NetworkMessage("client-1", "DISCARD", payload, senderSeat = 1))
        advanceUntilIdle()

        assertEquals(
            "Acao recusada: identidade do jogador nao confere com o assento.",
            viewModel.gameState.value.feedbackMessage
        )
        assertEquals(1, viewModel.remoteHandsForTest()[1]?.size)
    }

    @Test
    fun `host rejects discard attempted out of turn`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 2)
        )
        viewModel.startGame()
        advanceUntilIdle()

        // Turno do host (assento 0), nao do cliente (assento 1).
        viewModel.mutableGameState().value = viewModel.mutableGameState().value.copy(activeSeat = 0)
        viewModel.deckServedSeatsForTest().add(1)
        val realCard = Card(suit = Suit.CLUBS, rank = Rank.TWO)
        viewModel.remoteHandsForTest()[1] = listOf(realCard)

        val payload = JSONObject().put("card", realCard.id).put("seat", 1).toString()
        repo.emitIncoming(NetworkMessage("client-1", "DISCARD", payload, senderSeat = 1))
        advanceUntilIdle()

        assertEquals("Descarte recusado: nao e o turno desse jogador.", viewModel.gameState.value.feedbackMessage)
        assertEquals(1, viewModel.remoteHandsForTest()[1]?.size)
    }

    @Test
    fun `host ignores duplicated discard retry with same message id`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 2)
        )
        viewModel.startGame()
        advanceUntilIdle()
        // Buraco abre com uma carta no lixo (vira inicial); o retry nao pode somar uma segunda.
        val discardSizeBeforeDiscard = viewModel.gameState.value.discardPile.size

        viewModel.mutableGameState().value = viewModel.mutableGameState().value.copy(activeSeat = 1)
        viewModel.deckServedSeatsForTest().add(1)
        // Sem mortos disponiveis: zerar a mao aciona so a contagem, nao a entrega
        // automatica do morto, que mudaria o tamanho da mao por um motivo alheio ao teste.
        viewModel.mortosForTest().clear()
        val realCard = Card(suit = Suit.CLUBS, rank = Rank.TWO)
        viewModel.remoteHandsForTest()[1] = listOf(realCard)

        val payload = JSONObject().put("card", realCard.id).put("seat", 1).toString()
        val message = NetworkMessage("client-1", "DISCARD", payload, messageId = "retry-fixed-id", senderSeat = 1)
        repo.emitIncoming(message)
        repo.emitIncoming(message)
        advanceUntilIdle()

        assertEquals(discardSizeBeforeDiscard + 1, viewModel.gameState.value.discardPile.size)
        assertEquals(0, viewModel.remoteHandsForTest()[1]?.size ?: -1)
    }

    @Test
    fun `host rejects forged win claim that does not match its own authoritative table`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 2)
        )
        viewModel.startGame()
        advanceUntilIdle()

        // O host so confia na propria mao/mesa rastreadas para o assento, nunca
        // no que o cliente reivindica no payload do WIN_ROUND.
        viewModel.remoteHandsForTest()[1] = listOf(Card(suit = Suit.CLUBS, rank = Rank.TWO))
        viewModel.mortosForTest().clear()
        viewModel.teamsThatPickedMortoForTest().add(1)

        val forgedPayload = roundReportPayload(
            playerId = "client-1",
            seat = 1,
            hand = emptyList(),
            tableMelds = listOf(JSONArray().apply { cleanCanastraCards().forEach { put(it.id) } })
        )
        repo.emitIncoming(NetworkMessage("client-1", "WIN_ROUND", forgedPayload, senderSeat = 1))
        advanceUntilIdle()

        assertTrue(viewModel.gameState.value.feedbackMessage.startsWith("Vitoria recusada"))
        assertFalse(viewModel.gameState.value.showRoundEndDialog)
    }

    @Test
    fun `client start respects auto sort hand option`() = runTest {
        val unsortedHand = listOf(
            cardId(Rank.KING, Suit.SPADES),
            cardId(Rank.ACE, Suit.HEARTS),
            cardId(Rank.FIVE, Suit.CLUBS)
        )

        val manualRepo = FakeLocalNetworkRepository()
        val manualViewModel = MatchViewModel(
            networkRepository = manualRepo,
            playerId = "manual",
            isHost = false,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 4, autoSortHand = false)
        )
        manualRepo.emitIncoming(NetworkMessage("host", "GAME_START", gameStartPayload(seat = 2, hand = unsortedHand, autoSortHand = false)))
        advanceUntilIdle()

        assertEquals(unsortedHand, manualViewModel.gameState.value.myHand.map { it.id })

        val sortedRepo = FakeLocalNetworkRepository()
        val sortedViewModel = MatchViewModel(
            networkRepository = sortedRepo,
            playerId = "sorted",
            isHost = false,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 4, autoSortHand = true)
        )
        sortedRepo.emitIncoming(NetworkMessage("host", "GAME_START", gameStartPayload(seat = 2, hand = unsortedHand, autoSortHand = true)))
        advanceUntilIdle()

        assertEquals(
            listOf(
                cardId(Rank.ACE, Suit.HEARTS),
                cardId(Rank.FIVE, Suit.CLUBS),
                cardId(Rank.KING, Suit.SPADES)
            ),
            sortedViewModel.gameState.value.myHand.map { it.id }
        )
    }

    @Test
    fun `client sends only one deck draw request while waiting host card`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "client-2",
            isHost = false,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 4)
        )
        repo.emitIncoming(NetworkMessage("host", "GAME_START", gameStartPayload(seat = 2, activeSeat = 2)))
        advanceUntilIdle()

        viewModel.drawFromDeck()
        viewModel.drawFromDeck()
        advanceUntilIdle()

        assertEquals(1, repo.broadcastMessages.count { it.type == "REQ_DRAW_DECK" })
        assertEquals(TurnPhase.WAITING_OPPONENT, viewModel.gameState.value.turnPhase)
    }

    @Test
    fun `client applies host public table counts`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "client-1",
            isHost = false,
            config = MatchConfig(gameType = GameType.TRANCA, maxPlayers = 2)
        )
        val discard = cardId(Rank.SEVEN, Suit.HEARTS)

        repo.emitIncoming(NetworkMessage("host", "GAME_START", gameStartPayload(
            gameType = GameType.TRANCA,
            seat = 1,
            hand = listOf(cardId(Rank.ACE, Suit.CLUBS)),
            activeSeat = 0,
            maxPlayers = 2
        )))
        advanceUntilIdle()

        val publicState = JSONObject()
            .put("v", 1)
            .put("activeSeat", 0)
            .put("deckSize", 37)
            .put("discardPile", JSONArray().put(discard))
            .put("turnCard", "")
            .put("mortosLeft", 1)
            .put("handCounts", JSONArray().put(8).put(6))
            .toString()

        repo.emitIncoming(NetworkMessage("host", "PUBLIC_STATE", publicState))
        advanceUntilIdle()

        val state = viewModel.gameState.value
        assertEquals(8, state.opponentHandCount)
        assertEquals(37, state.deckSize)
        assertEquals(1, state.discardPile.size)
        assertEquals(discard, state.discardPile.single().id)
        assertEquals(1, state.mortosLeft)
        assertEquals(TurnPhase.WAITING_OPPONENT, state.turnPhase)
    }

    @Test
    fun `host serves only one deck card per active seat turn`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 4)
        )
        viewModel.startGame()
        advanceUntilIdle()

        repo.emitIncoming(NetworkMessage("player-1", "REQ_DRAW_DECK", """{"v":1,"seat":1}"""))
        advanceUntilIdle()
        repo.emitIncoming(NetworkMessage("player-1", "REQ_DRAW_DECK", """{"v":1,"seat":1}"""))
        advanceUntilIdle()

        assertEquals(1, repo.privatePlayerMessages.count { it.message.type == "SERVE_CARD" })
    }

    @Test
    fun `host allows tranca client to draw again after red three from deck`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.TRANCA, maxPlayers = 2)
        )
        val redThree = Card(suit = Suit.HEARTS, rank = Rank.THREE)
        val nextCard = Card(suit = Suit.CLUBS, rank = Rank.ACE)
        viewModel.startGame()
        advanceUntilIdle()
        val remoteHandBeforeDraw = viewModel.remoteHandsForTest().getValue(1)
        val remoteMeldsBeforeDraw = viewModel.gameState.value.opponentTableMelds
        viewModel.setPrivateField("masterDeck", mutableListOf(nextCard, redThree))

        repo.emitIncoming(NetworkMessage("client-1", "REQ_DRAW_DECK", """{"v":1,"seat":1}"""))
        advanceUntilIdle()
        repo.emitIncoming(NetworkMessage("client-1", "REQ_DRAW_DECK", """{"v":1,"seat":1}"""))
        advanceUntilIdle()

        val servedCards = repo.privatePlayerMessages
            .filter { it.message.type == "SERVE_CARD" }
            .map { it.message.payload }
        assertEquals(listOf(redThree.id, nextCard.id), servedCards)
        assertEquals(remoteHandBeforeDraw.size + 1, viewModel.remoteHandsForTest().getValue(1).size)
        assertFalse(viewModel.remoteHandsForTest().getValue(1).any { it.id == redThree.id })
        assertTrue(viewModel.remoteHandsForTest().getValue(1).any { it.id == nextCard.id })
        assertEquals(remoteMeldsBeforeDraw.size + 1, viewModel.gameState.value.opponentTableMelds.size)
        assertEquals(listOf(redThree.id), viewModel.gameState.value.opponentTableMelds.last().map(Card::id))
    }

    @Test
    fun `host keeps red three in client hand when automatic meld is disabled`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(
                gameType = GameType.TRANCA,
                maxPlayers = 2,
                autoMeldTrancaRedThrees = false
            )
        )
        val redThree = Card(suit = Suit.HEARTS, rank = Rank.THREE)
        viewModel.startGame()
        advanceUntilIdle()
        val remoteHandBeforeDraw = viewModel.remoteHandsForTest().getValue(1)
        val remoteMeldsBeforeDraw = viewModel.gameState.value.opponentTableMelds
        viewModel.masterDeckForTest().apply {
            clear()
            add(redThree)
        }
        viewModel.mutableGameState().value = viewModel.gameState.value.copy(
            activeSeat = 1,
            deckSize = 1,
            opponentHandCount = remoteHandBeforeDraw.size
        )

        repo.emitIncoming(
            NetworkMessage(
                senderId = "client-1",
                type = "REQ_DRAW_DECK",
                payload = """{"v":1,"seat":1}""",
                senderSeat = 1
            )
        )
        advanceUntilIdle()
        repo.emitIncoming(
            NetworkMessage(
                senderId = "client-1",
                type = "REQ_DRAW_DECK",
                payload = """{"v":1,"seat":1}""",
                senderSeat = 1
            )
        )
        advanceUntilIdle()

        val servedCards = repo.privatePlayerMessages
            .filter { it.message.type == "SERVE_CARD" }
            .map { it.message.payload }
        assertEquals(listOf(redThree.id), servedCards)
        assertEquals(remoteHandBeforeDraw.size + 1, viewModel.remoteHandsForTest().getValue(1).size)
        assertTrue(viewModel.remoteHandsForTest().getValue(1).any { it.id == redThree.id })
        assertEquals(remoteMeldsBeforeDraw, viewModel.gameState.value.opponentTableMelds)
    }

    @Test
    fun `host rolls back automatic red three when private delivery fails`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.TRANCA, maxPlayers = 2)
        )
        val redThree = Card(suit = Suit.DIAMONDS, rank = Rank.THREE)
        viewModel.startGame()
        advanceUntilIdle()
        val remoteHandBeforeDraw = viewModel.remoteHandsForTest().getValue(1)
        val remoteMeldsBeforeDraw = viewModel.gameState.value.opponentTableMelds
        viewModel.masterDeckForTest().apply {
            clear()
            add(redThree)
        }
        viewModel.mutableGameState().value = viewModel.gameState.value.copy(
            activeSeat = 1,
            deckSize = 1,
            opponentHandCount = remoteHandBeforeDraw.size
        )
        repo.confirmedDeliveryResult = false

        repo.emitIncoming(
            NetworkMessage(
                senderId = "client-1",
                type = "REQ_DRAW_DECK",
                payload = """{"v":1,"seat":1}""",
                messageId = "red-three-without-ack",
                senderSeat = 1
            )
        )
        advanceUntilIdle()

        assertEquals(remoteHandBeforeDraw, viewModel.remoteHandsForTest().getValue(1))
        assertEquals(remoteMeldsBeforeDraw, viewModel.gameState.value.opponentTableMelds)
        assertEquals(listOf(redThree.id), viewModel.masterDeckForTest().map(Card::id))
        assertFalse(viewModel.deckServedSeatsForTest().contains(1))
        assertEquals(1, viewModel.gameState.value.deckSize)
    }

    @Test
    fun `tranca client reflects served red three without sending duplicate meld`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "client-1",
            isHost = false,
            config = MatchConfig(gameType = GameType.TRANCA, maxPlayers = 2)
        )
        val handCard = Card(suit = Suit.CLUBS, rank = Rank.FOUR)
        val redThree = Card(suit = Suit.HEARTS, rank = Rank.THREE)
        repo.emitIncoming(
            NetworkMessage(
                senderId = "host",
                type = "GAME_START",
                payload = gameStartPayload(
                    gameType = GameType.TRANCA,
                    seat = 1,
                    hand = listOf(handCard.id),
                    activeSeat = 1,
                    maxPlayers = 2
                ),
                senderSeat = 0
            )
        )
        advanceUntilIdle()
        val meldsBeforeDraw = viewModel.gameState.value.myTableMelds

        repo.emitIncoming(
            NetworkMessage(
                senderId = "host",
                type = "SERVE_CARD",
                payload = redThree.id,
                senderSeat = 0
            )
        )
        advanceUntilIdle()

        val state = viewModel.gameState.value
        assertEquals(listOf(handCard.id), state.myHand.map(Card::id))
        assertEquals(meldsBeforeDraw.size + 1, state.myTableMelds.size)
        assertEquals(listOf(redThree.id), state.myTableMelds.last().map(Card::id))
        assertEquals(TurnPhase.DRAW, state.turnPhase)
        assertFalse(repo.broadcastMessages.any { it.type == "MELD" })
    }

    @Test
    fun `buraco uses a morto as new deck when stock is empty`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 2)
        )
        viewModel.startGame()
        advanceUntilIdle()
        viewModel.setPrivateField("masterDeck", mutableListOf<Any>())

        repo.emitIncoming(NetworkMessage("client-1", "REQ_DRAW_DECK", """{"v":1,"seat":1}"""))
        advanceUntilIdle()

        assertEquals(1, repo.privatePlayerMessages.count { it.message.type == "SERVE_CARD" })
        assertEquals(1, viewModel.gameState.value.mortosLeft)
        assertTrue(viewModel.gameState.value.deckSize > 0)
    }

    @Test
    fun `tranca uses a morto as new deck when stock is empty`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.TRANCA, maxPlayers = 2)
        )
        viewModel.startGame()
        advanceUntilIdle()
        viewModel.setPrivateField("masterDeck", mutableListOf<Any>())

        repo.emitIncoming(NetworkMessage("client-1", "REQ_DRAW_DECK", """{"v":1,"seat":1}"""))
        advanceUntilIdle()

        assertEquals(1, repo.privatePlayerMessages.count { it.message.type == "SERVE_CARD" })
        assertEquals(1, viewModel.gameState.value.mortosLeft)
        assertTrue(viewModel.gameState.value.deckSize > 0)
    }

    @Test
    fun `client does not show opponent morto notice when morto becomes deck`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "client-1",
            isHost = false,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 2)
        )
        repo.emitIncoming(NetworkMessage("host", "GAME_START", gameStartPayload(seat = 1, maxPlayers = 2)))
        advanceUntilIdle()

        repo.emitIncoming(NetworkMessage("host", "PICK_MORTO", """{"v":1,"mortosLeft":1,"seat":-1}"""))
        advanceUntilIdle()

        val state = viewModel.gameState.value
        assertEquals(1, state.mortosLeft)
        assertFalse(state.opponentPickedMorto)
        assertNull(state.mortoNoticeSeat)
        assertEquals(0, state.mortoNoticeId)
    }

    @Test
    fun `client shows morto notice when opponent really picks morto`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "client-1",
            isHost = false,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 2)
        )
        repo.emitIncoming(NetworkMessage("host", "GAME_START", gameStartPayload(seat = 1, maxPlayers = 2)))
        advanceUntilIdle()

        repo.emitIncoming(NetworkMessage("host", "PICK_MORTO", """{"v":1,"mortosLeft":1,"seat":0}"""))
        advanceUntilIdle()

        val state = viewModel.gameState.value
        assertEquals(1, state.mortosLeft)
        assertTrue(state.opponentPickedMorto)
        assertEquals(0, state.mortoNoticeSeat)
        assertEquals(1, state.mortoNoticeId)
    }

    @Test
    fun `tranca starts count round when stock and mortos are empty`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.TRANCA, maxPlayers = 2)
        )
        viewModel.startGame()
        advanceUntilIdle()
        viewModel.setPrivateField("masterDeck", mutableListOf<Any>())
        viewModel.setPrivateField("mortos", mutableListOf<List<Any>>())

        repo.emitIncoming(NetworkMessage("client-1", "REQ_DRAW_DECK", """{"v":1,"seat":1}"""))
        advanceUntilIdle()

        assertEquals(0, repo.privatePlayerMessages.count { it.message.type == "SERVE_CARD" })
        assertTrue(repo.broadcastMessages.any { it.type == "COUNT_ROUND" })

        repo.emitIncoming(NetworkMessage("client-1", "REPLY_COUNT_ROUND", roundReportPayload("client-1", 1)))
        advanceUntilIdle()

        val summary = JSONObject(repo.broadcastMessages.last { it.type == "ROUND_SUMMARY" }.payload)
        assertTrue(summary.getBoolean("noWinner"))
        assertTrue(summary.getString("breakdown").contains("Rodada encerrada por contagem"))
        assertEquals("Contagem", viewModel.gameState.value.roundEndDetails?.winnerName)
    }

    @Test
    fun `cacheta recycles discard pile into a fresh deck when stock runs out`() = runTest {
        // Cacheta nao tem morto: quando o monte esvazia, o lixo (menos o topo)
        // vira o novo monte embaralhado. Buraco/Tranca usam morto para isso;
        // este e o equivalente de "fim de monte" especifico da Cacheta.
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.CACHETA, maxPlayers = 2)
        )
        viewModel.startGame()
        advanceUntilIdle()

        val discardBottom = listOf(
            Card(suit = Suit.HEARTS, rank = Rank.FOUR),
            Card(suit = Suit.HEARTS, rank = Rank.FIVE),
            Card(suit = Suit.HEARTS, rank = Rank.SIX)
        )
        val discardTop = Card(suit = Suit.SPADES, rank = Rank.KING)
        viewModel.mutableGameState().value = viewModel.gameState.value.copy(
            discardPile = discardBottom + discardTop,
            turnPhase = TurnPhase.DRAW
        )
        viewModel.masterDeckForTest().clear()
        val handSizeBeforeDraw = viewModel.gameState.value.myHand.size

        viewModel.drawFromDeck()
        advanceUntilIdle()

        assertEquals(listOf(discardTop), viewModel.gameState.value.discardPile)
        assertEquals(handSizeBeforeDraw + 1, viewModel.gameState.value.myHand.size)
        assertEquals(discardBottom.size - 1, viewModel.gameState.value.deckSize)
    }

    @Test
    fun `cacheta draw safely no-ops when deck and discard pile are both insufficient to recycle`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.CACHETA, maxPlayers = 2)
        )
        viewModel.startGame()
        advanceUntilIdle()

        val onlyDiscard = Card(suit = Suit.SPADES, rank = Rank.KING)
        viewModel.mutableGameState().value = viewModel.gameState.value.copy(
            discardPile = listOf(onlyDiscard),
            turnPhase = TurnPhase.DRAW
        )
        viewModel.masterDeckForTest().clear()
        val handSizeBeforeDraw = viewModel.gameState.value.myHand.size

        viewModel.drawFromDeck()
        advanceUntilIdle()

        assertEquals(handSizeBeforeDraw, viewModel.gameState.value.myHand.size)
        assertEquals(listOf(onlyDiscard), viewModel.gameState.value.discardPile)
        assertEquals(0, viewModel.gameState.value.deckSize)
    }

    @Test
    fun `next round clears table and redeals while preserving accumulated scores`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 2)
        )
        viewModel.startGame()
        advanceUntilIdle()
        val staleCard = Card(suit = Suit.SPADES, rank = Rank.KING, isJoker = true)
        val scoredState = viewModel.gameState.value.copy(
            myHand = listOf(staleCard),
            selectedCards = setOf(staleCard),
            discardPile = listOf(staleCard),
            myTableMelds = listOf(listOf(staleCard)),
            opponentTableMelds = listOf(listOf(staleCard)),
            myScore = 340,
            opponentScore = 120,
            teamScores = listOf(340, 120),
            showRoundEndDialog = true,
            roundEndDetails = RoundEndDetails(
                winnerName = "Você",
                myRoundScore = 340,
                opponentRoundScore = 120,
                myNewTotal = 340,
                opponentNewTotal = 120,
                isMatchOver = false,
                breakdown = "Rodada anterior"
            )
        )
        viewModel.mutableGameState().value = scoredState

        viewModel.nextRound()
        advanceUntilIdle()

        val state = viewModel.gameState.value
        assertEquals(11, state.myHand.size)
        assertEquals(1, state.discardPile.size)
        assertFalse(state.myHand.contains(staleCard))
        assertFalse(state.discardPile.contains(staleCard))
        assertFalse(state.myTableMelds.flatten().contains(staleCard))
        assertTrue(state.opponentTableMelds.isEmpty())
        assertTrue(state.selectedCards.isEmpty())
        assertEquals(2, state.mortosLeft)
        assertEquals(340, state.myScore)
        assertEquals(120, state.opponentScore)
        assertEquals(listOf(340, 120), state.teamScores)
        assertFalse(state.showRoundEndDialog)
        assertNull(state.roundEndDetails)
        assertTrue(repo.broadcastMessages.any { it.type == "NEXT_ROUND" })
        assertEquals(2, repo.privateClientMessages.count { it.message.type == "GAME_START" })
        assertTrue(repo.eventLog.indexOf("broadcast:NEXT_ROUND") < repo.eventLog.lastIndexOf("client:GAME_START"))
    }

    @Test
    fun `next round in four player mode keeps team scores by team not by seat`() = runTest {
        // O teste de 2 jogadores acima ja cobre placar preservado, mas so existe um
        // time de cada lado (indice de time e indice de assento coincidem). Em modo
        // de dupla teamScores e indexado por TIME (0/1), nao por assento (0..3) --
        // faltava garantir que "proxima rodada" (nao reinicio completo) preserva isso
        // certo e nao confunde indice de assento com indice de time.
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 4)
        )
        viewModel.startGame()
        advanceUntilIdle()

        viewModel.mutableGameState().value = viewModel.gameState.value.copy(
            myScore = 275,
            opponentScore = 610,
            teamScores = listOf(275, 610),
            showRoundEndDialog = true,
            roundEndDetails = RoundEndDetails(
                winnerName = "Equipe B",
                myRoundScore = 0,
                opponentRoundScore = 0,
                myNewTotal = 275,
                opponentNewTotal = 610,
                isMatchOver = false,
                breakdown = "Rodada anterior"
            )
        )

        viewModel.nextRound()
        advanceUntilIdle()

        val state = viewModel.gameState.value
        assertEquals(listOf(275, 610), state.teamScores)
        // O host e assento 0 -> time 0. myScore/opponentScore continuam refletindo a
        // perspectiva do time do host, nao a soma nem o placar de outro assento.
        assertEquals(275, state.myScore)
        assertEquals(610, state.opponentScore)
        assertEquals(11, state.myHand.size)
        // 3 assentos remotos recebem GAME_START na distribuicao inicial (startGame)
        // e de novo na redistribuicao da proxima rodada (nextRound) -- 3+3=6.
        assertEquals(6, repo.privateClientMessages.count { it.message.type == "GAME_START" })
    }

    @Test
    fun `tranca four player deal never leaves a red three stuck in any seat hand and melds it alone`() = runTest {
        // Gap que ficou pendente da varredura da matriz de regras: o 3 vermelho
        // automatico na Tranca em 4 jogadores passa pelo parceiro (mesmo time),
        // nao só pelo host. A logica em teamForSeat(seat)==teamForSeat(localSeat)
        // ja foi validada a fundo do lado do servidor (RPC start_online_round,
        // testada com Postgres local rodando de verdade em varias rodadas
        // aleatorias, inclusive com os dois times recebendo 3 vermelho na mesma
        // rodada) -- essa logica no Kotlin e a mesma comparação. startGame()
        // embaralha um baralho novo a cada chamada e não dá pra fixar a ordem sem
        // duplicar a distribuição aqui no teste, então repito até algum dos 4
        // assentos sortear um 3 vermelho e confirmo que ele nunca fica preso na
        // mão de ninguém e sempre vira uma baixa isolada (uma carta só) em
        // alguma das duas mesas.
        var checkedAtLeastOnce = false
        val isRedThree: (Card) -> Boolean = { it.rank == Rank.THREE && (it.suit == Suit.HEARTS || it.suit == Suit.DIAMONDS) }

        repeat(30) {
            val viewModel = MatchViewModel(
                networkRepository = FakeLocalNetworkRepository(),
                playerId = "host",
                isHost = true,
                config = MatchConfig(gameType = GameType.TRANCA, maxPlayers = 4)
            )
            viewModel.startGame()
            advanceUntilIdle()

            val state = viewModel.gameState.value
            val redThreeMelds = (state.myTableMelds + state.opponentTableMelds).filter { it.any(isRedThree) }
            if (redThreeMelds.isEmpty()) return@repeat

            checkedAtLeastOnce = true
            assertFalse("3 vermelho não pode sobrar na mão do host", state.myHand.any(isRedThree))
            for (seat in 1..3) {
                assertFalse(
                    "3 vermelho não pode sobrar na mão do assento $seat",
                    viewModel.remoteHandsForTest()[seat]?.any(isRedThree) ?: false
                )
            }
            redThreeMelds.forEach { meld ->
                assertEquals("3 vermelho vira sempre uma baixa isolada", 1, meld.size)
            }
        }
        assertTrue(
            "Nenhuma das 30 rodadas sorteou um 3 vermelho -- probabilidade quase nula, ajuste a repetição",
            checkedAtLeastOnce
        )
    }

    @Test
    fun `full restart waits all players then clears table redeals and resets scores`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 4)
        )
        viewModel.startGame()
        advanceUntilIdle()
        val staleCard = Card(suit = Suit.HEARTS, rank = Rank.QUEEN, isJoker = true)
        viewModel.mutableGameState().value = viewModel.gameState.value.copy(
            myHand = listOf(staleCard),
            discardPile = listOf(staleCard),
            myTableMelds = listOf(listOf(staleCard)),
            opponentTableMelds = listOf(listOf(staleCard)),
            myScore = 600,
            opponentScore = 250,
            teamScores = listOf(600, 250)
        )

        viewModel.requestRestartMatch()
        advanceUntilIdle()
        repo.emitIncoming(NetworkMessage("client-1", "REPLY_RESTART", "YES", senderSeat = 1))
        advanceUntilIdle()
        repo.emitIncoming(NetworkMessage("client-2", "REPLY_RESTART", "YES", senderSeat = 2))
        advanceUntilIdle()

        assertEquals(listOf(600, 250), viewModel.gameState.value.teamScores)
        assertFalse(repo.broadcastMessages.any { it.type == "NEXT_ROUND" })

        repo.emitIncoming(NetworkMessage("client-3", "REPLY_RESTART", "YES", senderSeat = 3))
        advanceUntilIdle()

        val state = viewModel.gameState.value
        assertEquals(11, state.myHand.size)
        assertEquals(1, state.discardPile.size)
        assertFalse(state.myHand.contains(staleCard))
        assertFalse(state.discardPile.contains(staleCard))
        assertFalse(state.myTableMelds.flatten().contains(staleCard))
        assertTrue(state.opponentTableMelds.isEmpty())
        assertEquals(listOf(0, 0), state.teamScores)
        assertEquals(0, state.myScore)
        assertEquals(0, state.opponentScore)
        assertEquals(2, state.mortosLeft)
        assertTrue(repo.broadcastMessages.any { it.type == "NEXT_ROUND" })
        assertEquals(6, repo.privateClientMessages.count { it.message.type == "GAME_START" })
    }

    @Test
    fun `client receives indirect morto but waits next turn`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "client-1",
            isHost = false,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 2)
        )
        val lastCard = Card(suit = Suit.CLUBS, rank = Rank.ACE)
        val mortoIds = mortoCardIds()

        repo.emitIncoming(NetworkMessage("host", "GAME_START", gameStartPayload(
            seat = 1,
            hand = listOf(lastCard.id),
            activeSeat = 1,
            maxPlayers = 2
        )))
        advanceUntilIdle()
        viewModel.mutableGameState().value = viewModel.gameState.value.copy(turnPhase = TurnPhase.ACTION)

        viewModel.discardCard(lastCard)
        advanceUntilIdle()

        repo.emitIncoming(NetworkMessage("host", "SERVE_MORTO", JSONObject()
            .put("v", 1)
            .put("hand", org.json.JSONArray().apply { mortoIds.forEach { put(it) } })
            .put("mortosLeft", 1)
            .toString()))
        advanceUntilIdle()

        val state = viewModel.gameState.value
        assertEquals(11, state.myHand.size)
        assertEquals(mortoIds.sorted(), state.myHand.map { it.id }.sorted())
        assertEquals(TurnPhase.WAITING_OPPONENT, state.turnPhase)
        assertEquals(0, state.activeSeat)
    }

    @Test
    fun `solo bot replies round count so host can finish stock empty round`() = runTest {
        val repo = SoloBotNetworkRepository()
        val messages = mutableListOf<NetworkMessage>()
        val collectJob = launch {
            repo.incomingMessages.collect { messages += it }
        }
        runCurrent()

        repo.sendMessageToClient(
            0,
            NetworkMessage(
                "host",
                "GAME_START",
                gameStartPayload(
                    gameType = GameType.TRANCA,
                    seat = 1,
                    hand = listOf(cardId(Rank.FIVE, Suit.HEARTS), cardId(Rank.SIX, Suit.HEARTS)),
                    activeSeat = 0,
                    maxPlayers = 2
                )
            )
        )

        repo.sendMessage(NetworkMessage("host", "COUNT_ROUND", ""))
        advanceUntilIdle()

        val reply = messages.lastOrNull { it.type == "REPLY_COUNT_ROUND" }
        assertNotNull(reply)
        val payload = JSONObject(reply!!.payload)
        assertEquals(1, payload.getInt("seat"))
        assertEquals(2, payload.getJSONArray("hand").length())
        collectJob.cancel()
    }

    @Test
    fun `solo bot accepts restart match request so host is never stuck waiting`() = runTest {
        val repo = SoloBotNetworkRepository()
        val messages = mutableListOf<NetworkMessage>()
        val collectJob = launch {
            repo.incomingMessages.collect { messages += it }
        }
        runCurrent()

        repo.sendMessageToClient(
            0,
            NetworkMessage(
                "host",
                "GAME_START",
                gameStartPayload(
                    gameType = GameType.CACHETA,
                    seat = 1,
                    hand = listOf(cardId(Rank.FIVE, Suit.HEARTS), cardId(Rank.SIX, Suit.HEARTS)),
                    activeSeat = 0,
                    maxPlayers = 2
                )
            )
        )

        repo.sendMessage(NetworkMessage("host", "RESTART_MATCH", ""))
        advanceUntilIdle()

        val reply = messages.lastOrNull { it.type == "REPLY_RESTART" }
        assertNotNull(reply)
        assertEquals("YES", reply!!.payload)
        collectJob.cancel()
    }

    @Test
    fun `solo bot does not reply to a cancelled restart request`() = runTest {
        val repo = SoloBotNetworkRepository()
        val messages = mutableListOf<NetworkMessage>()
        val collectJob = launch {
            repo.incomingMessages.collect { messages += it }
        }
        runCurrent()

        repo.sendMessageToClient(
            0,
            NetworkMessage(
                "host",
                "GAME_START",
                gameStartPayload(
                    gameType = GameType.CACHETA,
                    seat = 1,
                    hand = listOf(cardId(Rank.FIVE, Suit.HEARTS), cardId(Rank.SIX, Suit.HEARTS)),
                    activeSeat = 0,
                    maxPlayers = 2
                )
            )
        )

        repo.sendMessage(NetworkMessage("host", "RESTART_MATCH", "CANCEL"))
        advanceUntilIdle()

        assertTrue(messages.none { it.type == "REPLY_RESTART" })
        collectJob.cancel()
    }

    @Test
    fun `normal solo bot buys discard when top card creates a valid tranca meld`() = runTest {
        val repo = SoloBotNetworkRepository()
        val messages = mutableListOf<NetworkMessage>()
        val collectJob = launch {
            repo.incomingMessages.collect { messages += it }
        }
        runCurrent()

        repo.sendMessageToClient(
            0,
            NetworkMessage(
                "host",
                "GAME_START",
                gameStartPayload(
                    gameType = GameType.TRANCA,
                    seat = 1,
                    hand = listOf(cardId(Rank.FIVE, Suit.HEARTS), cardId(Rank.SIX, Suit.HEARTS), cardId(Rank.KING, Suit.SPADES)),
                    activeSeat = 0,
                    maxPlayers = 2,
                    botDifficulty = BotDifficulty.NORMAL
                )
            )
        )

        repo.sendMessage(
            NetworkMessage(
                "host",
                "DISCARD",
                JSONObject()
                    .put("v", 1)
                    .put("card", cardId(Rank.FOUR, Suit.HEARTS))
                    .put("seat", 0)
                    .toString()
            )
        )
        advanceTimeBy(700)
        advanceUntilIdle()

        assertTrue(messages.any { it.type == "DRAW_DISCARD" && it.payload == cardId(Rank.FOUR, Suit.HEARTS) })
        assertFalse(messages.any { it.type == "REQ_DRAW_DECK" })
        collectJob.cancel()
    }

    @Test
    fun `easy solo bot avoids obvious discard into opponent table`() = runTest {
        val repo = SoloBotNetworkRepository()
        val messages = mutableListOf<NetworkMessage>()
        val collectJob = launch {
            repo.incomingMessages.collect { messages += it }
        }
        runCurrent()

        repo.sendMessageToClient(
            0,
            NetworkMessage(
                "host",
                "GAME_START",
                gameStartPayload(
                    gameType = GameType.TRANCA,
                    seat = 1,
                    hand = listOf(cardId(Rank.KING, Suit.HEARTS), cardId(Rank.ACE, Suit.SPADES)),
                    activeSeat = 0,
                    maxPlayers = 2,
                    botDifficulty = BotDifficulty.EASY
                )
            )
        )
        repo.sendMessage(
            NetworkMessage(
                "host",
                "MELD",
                JSONObject()
                    .put("v", 1)
                    .put("seat", 0)
                    .put("team", 0)
                    .put("replaceIndex", -1)
                    .put(
                        "cards",
                        JSONArray()
                            .put(cardId(Rank.JACK, Suit.HEARTS))
                            .put(cardId(Rank.QUEEN, Suit.HEARTS))
                    )
                    .toString()
            )
        )

        repo.sendMessageToPlayer("client-1", NetworkMessage("host", "SERVE_CARD", cardId(Rank.FOUR, Suit.CLUBS)))
        advanceTimeBy(900)
        advanceUntilIdle()

        val discard = messages.lastOrNull { it.type == "DISCARD" }
        assertNotNull(discard)
        assertTrue(
            discard!!.payload.contains(cardId(Rank.FOUR, Suit.CLUBS)) ||
                discard.payload.contains(cardId(Rank.ACE, Suit.SPADES))
        )
        assertFalse(discard.payload.contains(cardId(Rank.KING, Suit.HEARTS)))
        collectJob.cancel()
    }

    @Test
    fun `solo bot requests only one replacement after drawing a red three`() = runTest {
        val repo = SoloBotNetworkRepository()
        val messages = mutableListOf<NetworkMessage>()
        val collectJob = launch {
            repo.incomingMessages.collect { messages += it }
        }
        runCurrent()

        repo.sendMessageToClient(
            0,
            NetworkMessage(
                "host",
                "GAME_START",
                gameStartPayload(
                    gameType = GameType.TRANCA,
                    seat = 1,
                    hand = listOf(cardId(Rank.FOUR, Suit.CLUBS)),
                    activeSeat = 1,
                    maxPlayers = 2,
                    botDifficulty = BotDifficulty.EASY
                )
            )
        )
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(1, messages.count { it.type == "REQ_DRAW_DECK" })

        repo.sendMessageToPlayer(
            "machine-1",
            NetworkMessage("host", "SERVE_CARD", cardId(Rank.THREE, Suit.HEARTS))
        )
        val publicState = JSONObject()
            .put("v", 1)
            .put("activeSeat", 1)
            .put("deckSize", 40)
            .put("mortosLeft", 2)
            .put("discardPile", JSONArray())
            .put("team0Melds", JSONArray())
            .put("team1Melds", JSONArray())
            .toString()
        repeat(3) {
            repo.sendMessage(NetworkMessage("host", "PUBLIC_STATE", publicState))
        }
        advanceTimeBy(700)
        runCurrent()

        assertEquals(2, messages.count { it.type == "REQ_DRAW_DECK" })
        assertEquals(0, messages.count { it.type == "MELD" && it.payload.contains("THREE_HEARTS") })
        collectJob.cancel()
    }

    @Test
    fun `solo bot that already picked morto does not request a second one`() = runTest {
        val repo = SoloBotNetworkRepository()
        val messages = mutableListOf<NetworkMessage>()
        val collectJob = launch {
            repo.incomingMessages.collect { messages += it }
        }
        runCurrent()
        repo.startHosting(
            playerName = "host",
            config = MatchConfig(gameType = GameType.TRANCA, maxPlayers = 2)
        )
        repo.setPrivateField("hand", emptyList<Card>())
        repo.setPrivateField("tableMelds", listOf(cleanCanastraCards()))
        repo.setPrivateField("hasPickedMorto", true)
        repo.setPrivateField("mortosLeft", 1)
        val method = SoloBotNetworkRepository::class.java.getDeclaredMethod(
            "finishOrAskMorto",
            Boolean::class.javaPrimitiveType
        )
        method.isAccessible = true

        method.invoke(repo, false)
        runCurrent()

        assertFalse(messages.any { it.type == "REQ_PICK_MORTO" })
        assertTrue(messages.any { it.type == "WIN_ROUND" })
        collectJob.cancel()
    }

    @Test
    fun `discard draw card must be melded before discarding`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "client-1",
            isHost = false,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 2)
        )
        val topDiscard = Card(suit = Suit.HEARTS, rank = Rank.THREE)
        val four = Card(suit = Suit.HEARTS, rank = Rank.FOUR)
        val five = Card(suit = Suit.HEARTS, rank = Rank.FIVE)
        val extra = Card(suit = Suit.SPADES, rank = Rank.KING)
        val spare = Card(suit = Suit.CLUBS, rank = Rank.QUEEN)

        repo.emitIncoming(NetworkMessage("host", "GAME_START", gameStartPayload(
            seat = 1,
            hand = listOf(four.id, five.id, extra.id, spare.id),
            discardId = topDiscard.id,
            activeSeat = 1,
            maxPlayers = 2
        )))
        advanceUntilIdle()

        viewModel.drawFromDiscard()
        advanceUntilIdle()
        viewModel.discardCard(extra)
        advanceUntilIdle()

        assertEquals(TurnPhase.ACTION, viewModel.gameState.value.turnPhase)
        assertTrue(viewModel.gameState.value.feedbackMessage.contains("carta comprada do lixo"))

        listOf(topDiscard, four, five).forEach { viewModel.toggleCardSelection(it) }
        viewModel.meldSelectedCards()
        advanceUntilIdle()
        viewModel.discardCard(extra)
        advanceUntilIdle()

        assertEquals(TurnPhase.WAITING_OPPONENT, viewModel.gameState.value.turnPhase)
    }

    @Test
    fun `discard top card can create a new meld in tranca`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "client-1",
            isHost = false,
            config = MatchConfig(gameType = GameType.TRANCA, maxPlayers = 2)
        )
        val topDiscard = Card(suit = Suit.HEARTS, rank = Rank.FOUR)
        val five = Card(suit = Suit.HEARTS, rank = Rank.FIVE)
        val six = Card(suit = Suit.HEARTS, rank = Rank.SIX)
        val extra = Card(suit = Suit.SPADES, rank = Rank.KING)

        repo.emitIncoming(NetworkMessage("host", "GAME_START", gameStartPayload(
            gameType = GameType.TRANCA,
            seat = 1,
            hand = listOf(five.id, six.id, extra.id),
            discardId = topDiscard.id,
            activeSeat = 1,
            maxPlayers = 2
        )))
        advanceUntilIdle()

        viewModel.drawFromDiscard()
        advanceUntilIdle()
        listOf(topDiscard, five, six).forEach { viewModel.toggleCardSelection(it) }
        viewModel.meldSelectedCards()
        advanceUntilIdle()

        assertEquals(listOf(topDiscard, five, six), viewModel.gameState.value.myTableMelds.single())
        assertEquals(listOf(extra), viewModel.gameState.value.myHand)
        val payload = JSONObject(repo.broadcastMessages.last { it.type == "MELD" }.payload)
        assertEquals(-1, payload.getInt("replaceIndex"))
        assertEquals(3, payload.getJSONArray("cards").length())
    }

    @Test
    fun `discard top card can create a new meld in cacheta`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "client-1",
            isHost = false,
            config = MatchConfig(gameType = GameType.CACHETA, maxPlayers = 2)
        )
        val topDiscard = Card(suit = Suit.CLUBS, rank = Rank.NINE)
        val nineHearts = Card(suit = Suit.HEARTS, rank = Rank.NINE)
        val nineSpades = Card(suit = Suit.SPADES, rank = Rank.NINE)
        val extra = Card(suit = Suit.DIAMONDS, rank = Rank.KING)

        repo.emitIncoming(NetworkMessage("host", "GAME_START", gameStartPayload(
            gameType = GameType.CACHETA,
            seat = 1,
            hand = listOf(nineHearts.id, nineSpades.id, extra.id),
            discardId = topDiscard.id,
            activeSeat = 1,
            maxPlayers = 2
        )))
        advanceUntilIdle()

        viewModel.drawFromDiscard()
        advanceUntilIdle()
        listOf(topDiscard, nineHearts, nineSpades).forEach { viewModel.toggleCardSelection(it) }
        viewModel.meldSelectedCards()
        advanceUntilIdle()

        assertEquals(1, viewModel.gameState.value.myTableMelds.size)
        assertEquals(listOf(extra), viewModel.gameState.value.myHand)
        val payload = JSONObject(repo.broadcastMessages.last { it.type == "MELD" }.payload)
        assertEquals(-1, payload.getInt("replaceIndex"))
        assertEquals(3, payload.getJSONArray("cards").length())
    }

    @Test
    fun `cacheta stock card can be discarded without being used in a meld`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "client-1",
            isHost = false,
            config = MatchConfig(gameType = GameType.CACHETA, maxPlayers = 2)
        )
        val drawn = Card(suit = Suit.SPADES, rank = Rank.ACE)
        val handCards = listOf(
            Card(suit = Suit.HEARTS, rank = Rank.FOUR),
            Card(suit = Suit.CLUBS, rank = Rank.SEVEN),
            Card(suit = Suit.DIAMONDS, rank = Rank.KING)
        )

        repo.emitIncoming(NetworkMessage("host", "GAME_START", gameStartPayload(
            gameType = GameType.CACHETA,
            seat = 1,
            hand = handCards.map { it.id },
            activeSeat = 1,
            maxPlayers = 2
        )))
        advanceUntilIdle()

        viewModel.drawFromDeck()
        advanceUntilIdle()
        repo.emitIncoming(NetworkMessage("host", "SERVE_CARD", drawn.id))
        advanceUntilIdle()
        viewModel.discardCard(drawn)
        advanceUntilIdle()

        assertEquals(TurnPhase.WAITING_OPPONENT, viewModel.gameState.value.turnPhase)
        assertEquals(drawn, viewModel.gameState.value.discardPile.last())
        assertTrue(repo.broadcastMessages.any { it.type == "DISCARD" && it.payload.contains(drawn.id) })
        assertFalse(repo.broadcastMessages.any { it.type == "MELD" && it.payload.contains(drawn.id) })
    }

    @Test
    fun `cacheta discard top can be discarded without being used in a meld`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "client-1",
            isHost = false,
            config = MatchConfig(gameType = GameType.CACHETA, maxPlayers = 2)
        )
        val topDiscard = Card(suit = Suit.SPADES, rank = Rank.ACE)
        val handCards = listOf(
            Card(suit = Suit.HEARTS, rank = Rank.FOUR),
            Card(suit = Suit.CLUBS, rank = Rank.SEVEN),
            Card(suit = Suit.DIAMONDS, rank = Rank.KING)
        )

        repo.emitIncoming(NetworkMessage("host", "GAME_START", gameStartPayload(
            gameType = GameType.CACHETA,
            seat = 1,
            hand = handCards.map { it.id },
            discardId = topDiscard.id,
            activeSeat = 1,
            maxPlayers = 2
        )))
        advanceUntilIdle()

        viewModel.drawFromDiscard()
        advanceUntilIdle()
        viewModel.discardCard(topDiscard)
        advanceUntilIdle()

        assertEquals(TurnPhase.WAITING_OPPONENT, viewModel.gameState.value.turnPhase)
        assertEquals(topDiscard, viewModel.gameState.value.discardPile.last())
        assertTrue(repo.broadcastMessages.any { it.type == "DRAW_DISCARD" && it.payload == topDiscard.id })
        assertTrue(repo.broadcastMessages.any { it.type == "DISCARD" && it.payload.contains(topDiscard.id) })
    }

    @Test
    fun `buraco discard draw still requires top card to be usable`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "client-1",
            isHost = false,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 2)
        )
        val topDiscard = Card(suit = Suit.SPADES, rank = Rank.ACE)
        val handCards = listOf(
            Card(suit = Suit.HEARTS, rank = Rank.FOUR),
            Card(suit = Suit.CLUBS, rank = Rank.SEVEN),
            Card(suit = Suit.DIAMONDS, rank = Rank.KING)
        )

        repo.emitIncoming(NetworkMessage("host", "GAME_START", gameStartPayload(
            gameType = GameType.BURACO,
            seat = 1,
            hand = handCards.map { it.id },
            discardId = topDiscard.id,
            activeSeat = 1,
            maxPlayers = 2
        )))
        advanceUntilIdle()

        viewModel.drawFromDiscard()
        advanceUntilIdle()

        assertEquals(TurnPhase.DRAW, viewModel.gameState.value.turnPhase)
        assertEquals(listOf(topDiscard), viewModel.gameState.value.discardPile)
        assertFalse(repo.broadcastMessages.any { it.type == "DRAW_DISCARD" })
        assertTrue(viewModel.gameState.value.feedbackMessage.contains("não pode comprar do lixo"))
    }

    @Test
    fun `solo bot completes opening turn and gives control back to host`() = runTest {
        repeat(16) {
            val repo = SoloBotNetworkRepository()
            val viewModel = MatchViewModel(
                networkRepository = repo,
                playerId = "host",
                isHost = true,
                config = MatchConfig(gameType = GameType.TRANCA, maxPlayers = 2)
            )

            viewModel.startGame()
            advanceUntilIdle()

            val state = viewModel.gameState.value
            assertEquals(0, state.activeSeat)
            assertEquals(TurnPhase.DRAW, state.turnPhase)
            assertTrue(state.opponentHandCount > 0)
            assertTrue(state.discardPile.isNotEmpty())
        }
    }

    @Test
    fun `tranca opening red three returns to stock and another card opens discard`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.TRANCA, maxPlayers = 2)
        )
        val safeOpening = Card(Suit.CLUBS, Rank.SEVEN)
        val redThree = Card(Suit.HEARTS, Rank.THREE)
        viewModel.setPrivateField("masterDeck", mutableListOf(safeOpening, redThree))

        val method = MatchViewModel::class.java.getDeclaredMethod("drawOpeningDiscard")
        method.isAccessible = true
        val opening = method.invoke(viewModel) as Card?

        assertEquals(safeOpening.id, opening?.id)
        val remainingDeck = viewModel.masterDeckForTest()
        assertEquals(1, remainingDeck.size)
        assertEquals(redThree.id, remainingDeck.single().id)
    }

    @Test
    fun `tranca host removes initial red threes before sending remote hand`() = runTest {
        repeat(24) {
            val repo = FakeLocalNetworkRepository()
            val viewModel = MatchViewModel(
                networkRepository = repo,
                playerId = "host",
                isHost = true,
                config = MatchConfig(gameType = GameType.TRANCA, maxPlayers = 2)
            )

            viewModel.startGame()
            advanceUntilIdle()

            val payload = JSONObject(repo.privateClientMessages.single().message.payload)
            val hand = payload.getJSONArray("hand")
            assertEquals(11, hand.length())
            repeat(hand.length()) { index ->
                val id = hand.getString(index)
                assertFalse(id.startsWith("THREE_HEARTS") || id.startsWith("THREE_DIAMONDS"))
            }
            assertEquals(11, viewModel.remoteHandsForTest().getValue(1).size)
            assertTrue(viewModel.mortosForTest().all { it.size == 11 })
        }
    }

    @Test
    fun `host proactively serves direct morto when remote meld empties hand`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.TRANCA, maxPlayers = 2)
        )
        viewModel.startGame()
        advanceUntilIdle()

        val redThree = Card(Suit.HEARTS, Rank.THREE)
        val controlledMorto = listOf(
            redThree,
            Card(Suit.CLUBS, Rank.ACE),
            Card(Suit.CLUBS, Rank.TWO),
            Card(Suit.CLUBS, Rank.FOUR),
            Card(Suit.CLUBS, Rank.FIVE),
            Card(Suit.CLUBS, Rank.SIX),
            Card(Suit.CLUBS, Rank.SEVEN),
            Card(Suit.CLUBS, Rank.EIGHT),
            Card(Suit.CLUBS, Rank.NINE),
            Card(Suit.CLUBS, Rank.TEN),
            Card(Suit.CLUBS, Rank.JACK)
        )
        val reserveMorto = viewModel.mortosForTest().last()
        viewModel.mortosForTest().apply {
            clear()
            add(controlledMorto)
            add(reserveMorto)
        }
        viewModel.masterDeckForTest().apply {
            clear()
            add(Card(Suit.SPADES, Rank.QUEEN))
        }

        val cards = listOf(
            Card(Suit.HEARTS, Rank.FOUR),
            Card(Suit.HEARTS, Rank.FIVE),
            Card(Suit.HEARTS, Rank.SIX)
        )
        viewModel.remoteHandsForTest()[1] = cards
        viewModel.deckServedSeatsForTest().add(1)
        viewModel.mutableGameState().value = viewModel.gameState.value.copy(
            activeSeat = 1,
            opponentHandCount = cards.size,
            opponentTableMelds = emptyList()
        )
        val payload = JSONObject()
            .put("v", 1)
            .put("cards", JSONArray().apply { cards.forEach { put(it.id) } })
            .put("seat", 1)
            .put("team", 1)
            .put("replaceIndex", -1)
            .toString()

        repo.emitIncoming(NetworkMessage("machine-1", "MELD", payload, senderSeat = 1))
        advanceUntilIdle()

        assertTrue(
            "Morto nao servido. Estado: ${viewModel.gameState.value.feedbackMessage}",
            repo.privatePlayerMessages.any { it.message.type == "SERVE_MORTO" }
        )
        val served = repo.privatePlayerMessages.single { it.message.type == "SERVE_MORTO" }
        val servedPayload = JSONObject(served.message.payload)
        assertEquals(11, servedPayload.getJSONArray("hand").length())
        assertFalse(
            (0 until servedPayload.getJSONArray("hand").length()).any { index ->
                servedPayload.getJSONArray("hand").getString(index) == redThree.id
            }
        )
        assertFalse(servedPayload.getBoolean("indirect"))
        assertEquals(11, viewModel.gameState.value.opponentHandCount)
        assertEquals(1, viewModel.gameState.value.mortosLeft)
        assertTrue(viewModel.gameState.value.opponentTableMelds.flatten().contains(redThree))
    }

    @Test
    fun `host serves indirect morto after remote discards last card`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 2)
        )
        viewModel.startGame()
        advanceUntilIdle()

        val lastCard = Card(Suit.CLUBS, Rank.KING)
        viewModel.remoteHandsForTest()[1] = listOf(lastCard)
        viewModel.deckServedSeatsForTest().add(1)
        viewModel.mutableGameState().value = viewModel.gameState.value.copy(
            activeSeat = 1,
            opponentHandCount = 1
        )
        val payload = JSONObject()
            .put("v", 1)
            .put("card", lastCard.id)
            .put("seat", 1)
            .toString()

        repo.emitIncoming(NetworkMessage("machine-1", "DISCARD", payload, senderSeat = 1))
        advanceUntilIdle()

        val served = repo.privatePlayerMessages.single { it.message.type == "SERVE_MORTO" }
        val servedPayload = JSONObject(served.message.payload)
        assertEquals(11, servedPayload.getJSONArray("hand").length())
        assertTrue(servedPayload.getBoolean("indirect"))
        assertEquals(0, servedPayload.getInt("activeSeat"))
        assertEquals(11, viewModel.gameState.value.opponentHandCount)
        assertEquals(0, viewModel.gameState.value.activeSeat)
    }

    @Test
    fun `cacheta remote last discard closes the round`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.CACHETA, maxPlayers = 2)
        )
        viewModel.startGame()
        advanceUntilIdle()

        val lastCard = Card(Suit.CLUBS, Rank.FOUR)
        viewModel.remoteHandsForTest()[1] = listOf(lastCard)
        viewModel.deckServedSeatsForTest().add(1)
        viewModel.mutableGameState().value = viewModel.gameState.value.copy(
            activeSeat = 1,
            opponentHandCount = 1,
            opponentTableMelds = emptyList()
        )
        val payload = JSONObject()
            .put("v", 1)
            .put("card", lastCard.id)
            .put("seat", 1)
            .toString()

        repo.emitIncoming(NetworkMessage("machine-1", "DISCARD", payload, senderSeat = 1))
        advanceUntilIdle()

        val summary = JSONObject(repo.broadcastMessages.last { it.type == "ROUND_SUMMARY" }.payload)
        assertEquals("machine-1", summary.getString("winnerId"))
        assertTrue(viewModel.gameState.value.showRoundEndDialog)

        repo.emitIncoming(
            NetworkMessage(
                senderId = "machine-1",
                type = "WIN_ROUND",
                payload = roundReportPayload("machine-1", 1, winnerId = "machine-1"),
                senderSeat = 1
            )
        )
        advanceUntilIdle()
        assertEquals(1, repo.broadcastMessages.count { it.type == "ROUND_SUMMARY" })
    }

    @Test
    fun `cacheta stock and recycled discard both exhausted blocks draw without forcing a count round`() = runTest {
        // Cacheta nao tem morto nem rodada por contagem: quando o monte acaba, ela recicla o
        // lixo (gap ja fechado antes). Mas se o lixo tambem estiver reduzido a so a carta do
        // topo, nao ha o que reciclar. Diferente de Buraco/Tranca (que abrem COUNT_ROUND quando
        // monte e mortos acabam), a Cacheta deve travar a compra com um aviso e NAO deve inventar
        // um vencedor arbitrario via a rodada por contagem.
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.CACHETA, maxPlayers = 2)
        )
        viewModel.startGame()
        advanceUntilIdle()

        viewModel.setPrivateField("masterDeck", mutableListOf<Any>())
        val onlyTopDiscard = Card(suit = Suit.SPADES, rank = Rank.JACK)
        val handBeforeDraw = viewModel.gameState.value.myHand
        viewModel.mutableGameState().value = viewModel.gameState.value.copy(
            discardPile = listOf(onlyTopDiscard),
            deckSize = 0,
            turnPhase = TurnPhase.DRAW,
            activeSeat = 0
        )

        viewModel.drawFromDeck()
        advanceUntilIdle()

        val state = viewModel.gameState.value
        assertEquals(handBeforeDraw, state.myHand)
        assertEquals(listOf(onlyTopDiscard), state.discardPile)
        assertTrue(state.feedbackMessage.contains("insuficiente"))
        assertFalse(repo.broadcastMessages.any { it.type == "COUNT_ROUND" })
        assertFalse(repo.broadcastMessages.any { it.type == "ROUND_SUMMARY" })
    }

    @Test
    fun `tranca remote last discard without canastra ends by count`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.TRANCA, maxPlayers = 2)
        )
        viewModel.startGame()
        advanceUntilIdle()

        val lastCard = Card(Suit.CLUBS, Rank.FOUR)
        viewModel.mortosForTest().clear()
        viewModel.teamsThatPickedMortoForTest().add(1)
        viewModel.remoteHandsForTest()[1] = listOf(lastCard)
        viewModel.deckServedSeatsForTest().add(1)
        viewModel.mutableGameState().value = viewModel.gameState.value.copy(
            activeSeat = 1,
            opponentHandCount = 1,
            opponentTableMelds = emptyList(),
            mortosLeft = 0
        )
        val payload = JSONObject()
            .put("v", 1)
            .put("card", lastCard.id)
            .put("seat", 1)
            .toString()

        repo.emitIncoming(NetworkMessage("client-1", "DISCARD", payload, senderSeat = 1))
        advanceUntilIdle()

        assertTrue(repo.broadcastMessages.any { it.type == "COUNT_ROUND" })
        repo.emitIncoming(
            NetworkMessage(
                senderId = "client-1",
                type = "REPLY_COUNT_ROUND",
                payload = roundReportPayload("client-1", 1),
                senderSeat = 1
            )
        )
        advanceUntilIdle()
        val summary = JSONObject(repo.broadcastMessages.last { it.type == "ROUND_SUMMARY" }.payload)
        assertTrue(summary.getBoolean("noWinner"))
    }

    @Test
    fun `tranca discard draw releases rest only after top card is melded`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "client-1",
            isHost = false,
            config = MatchConfig(gameType = GameType.TRANCA, maxPlayers = 2)
        )
        val topDiscard = Card(suit = Suit.HEARTS, rank = Rank.FOUR)
        val lowerDiscard = Card(suit = Suit.CLUBS, rank = Rank.KING)
        val fiveHearts = Card(suit = Suit.HEARTS, rank = Rank.FIVE)
        val sixHearts = Card(suit = Suit.HEARTS, rank = Rank.SIX)

        repo.emitIncoming(NetworkMessage("host", "GAME_START", gameStartPayload(
            gameType = GameType.TRANCA,
            seat = 1,
            hand = listOf(fiveHearts.id, sixHearts.id),
            discardId = topDiscard.id,
            activeSeat = 1,
            maxPlayers = 2
        )))
        advanceUntilIdle()
        viewModel.mutableGameState().value = viewModel.gameState.value.copy(
            discardPile = listOf(lowerDiscard, topDiscard)
        )

        viewModel.drawFromDiscard()
        advanceUntilIdle()

        assertTrue(viewModel.gameState.value.myHand.contains(topDiscard))
        assertFalse(viewModel.gameState.value.myHand.contains(lowerDiscard))
        assertEquals(listOf(lowerDiscard), viewModel.gameState.value.discardPile)

        listOf(topDiscard, fiveHearts, sixHearts).forEach { viewModel.toggleCardSelection(it) }
        viewModel.meldSelectedCards()
        advanceUntilIdle()

        assertFalse(viewModel.gameState.value.myHand.contains(topDiscard))
        assertTrue(viewModel.gameState.value.myHand.contains(lowerDiscard))
        assertTrue(viewModel.gameState.value.discardPile.isEmpty())
        assertTrue(repo.broadcastMessages.any { it.type == "DRAW_DISCARD" && it.payload == topDiscard.id })
        assertTrue(repo.broadcastMessages.any { it.type == "MELD" && it.payload.contains(topDiscard.id) })
    }

    @Test
    fun `tranca discard draw cannot be justified by lower discard cards`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "client-1",
            isHost = false,
            config = MatchConfig(gameType = GameType.TRANCA, maxPlayers = 2)
        )
        val topDiscard = Card(suit = Suit.HEARTS, rank = Rank.FOUR)
        val lowerDiscard = Card(suit = Suit.HEARTS, rank = Rank.SIX)
        val fiveHearts = Card(suit = Suit.HEARTS, rank = Rank.FIVE)

        repo.emitIncoming(NetworkMessage("host", "GAME_START", gameStartPayload(
            gameType = GameType.TRANCA,
            seat = 1,
            hand = listOf(fiveHearts.id),
            discardId = topDiscard.id,
            activeSeat = 1,
            maxPlayers = 2
        )))
        advanceUntilIdle()
        viewModel.mutableGameState().value = viewModel.gameState.value.copy(
            discardPile = listOf(lowerDiscard, topDiscard)
        )

        viewModel.drawFromDiscard()
        advanceUntilIdle()

        assertEquals(TurnPhase.DRAW, viewModel.gameState.value.turnPhase)
        assertEquals(listOf(lowerDiscard, topDiscard), viewModel.gameState.value.discardPile)
        assertFalse(repo.broadcastMessages.any { it.type == "DRAW_DISCARD" })
    }

    @Test
    fun `cacheta cannot append a fourth card to a table trinca`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "client-1",
            isHost = false,
            config = MatchConfig(gameType = GameType.CACHETA, maxPlayers = 2)
        )
        val trinca = listOf(
            Card(suit = Suit.HEARTS, rank = Rank.NINE),
            Card(suit = Suit.SPADES, rank = Rank.NINE),
            Card(suit = Suit.CLUBS, rank = Rank.NINE)
        )
        val fourthNine = Card(suit = Suit.DIAMONDS, rank = Rank.NINE)

        repo.emitIncoming(NetworkMessage("host", "GAME_START", gameStartPayload(
            gameType = GameType.CACHETA,
            seat = 1,
            hand = listOf(fourthNine.id),
            activeSeat = 1,
            maxPlayers = 2
        )))
        advanceUntilIdle()
        viewModel.mutableGameState().value = viewModel.gameState.value.copy(
            myHand = listOf(fourthNine),
            myTableMelds = listOf(trinca),
            turnPhase = TurnPhase.ACTION
        )

        viewModel.toggleCardSelection(fourthNine)
        viewModel.meldSelectedCards(chosenTargetIndex = 0)
        advanceUntilIdle()

        assertEquals(listOf(fourthNine), viewModel.gameState.value.myHand)
        assertEquals(listOf(trinca), viewModel.gameState.value.myTableMelds)
        assertTrue(viewModel.gameState.value.lastMeldResult.contains("trinca fica fechada"))
        assertFalse(repo.broadcastMessages.any { it.type == "MELD" })
    }

    @Test
    fun `selected cards can be appended to existing table meld`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "client-1",
            isHost = false,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 2)
        )
        val three = Card(suit = Suit.HEARTS, rank = Rank.THREE)
        val four = Card(suit = Suit.HEARTS, rank = Rank.FOUR)
        val five = Card(suit = Suit.HEARTS, rank = Rank.FIVE)
        val six = Card(suit = Suit.HEARTS, rank = Rank.SIX)

        repo.emitIncoming(NetworkMessage("host", "GAME_START", gameStartPayload(
            seat = 1,
            hand = listOf(six.id),
            activeSeat = 1,
            maxPlayers = 2
        )))
        advanceUntilIdle()
        viewModel.mutableGameState().value = viewModel.gameState.value.copy(
            turnPhase = TurnPhase.ACTION,
            myTableMelds = listOf(listOf(three, four, five))
        )

        viewModel.toggleCardSelection(six)
        viewModel.meldSelectedCards()
        advanceUntilIdle()

        assertEquals(listOf(three, four, five, six), viewModel.gameState.value.myTableMelds.single())
        val payload = JSONObject(repo.broadcastMessages.last { it.type == "MELD" }.payload)
        assertEquals(0, payload.getInt("replaceIndex"))
        assertEquals(4, payload.getJSONArray("cards").length())
    }

    @Test
    fun `shared team table is scored only once when partners report same meld`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 4)
        )
        viewModel.startGame()
        advanceUntilIdle()

        viewModel.mutableGameState().value = viewModel.gameState.value.copy(
            opponentTableMelds = listOf(cleanCanastraCards())
        )
        viewModel.invokeBeginCountOnlyRound()
        (1..3).forEach { seat ->
            repo.emitIncoming(
                NetworkMessage(
                    senderId = "player-$seat",
                    type = "REPLY_COUNT_ROUND",
                    payload = roundReportPayload("player-$seat", seat),
                    senderSeat = seat
                )
            )
        }
        advanceUntilIdle()

        val summary = JSONObject(repo.broadcastMessages.last { it.type == "ROUND_SUMMARY" }.payload)
        val breakdown = summary.getString("breakdown")

        assertTrue(breakdown.contains("Equipe [TEAM_1]: mesa 7 carta(s) = 65 pts"))
        assertTrue(breakdown.contains("Equipe [TEAM_1]: canastras limpas 1, sujas 0 = +200 pts"))
        assertFalse(breakdown.contains("Equipe [TEAM_1]: mesa 14 carta(s) = 130 pts"))
        assertFalse(breakdown.contains("Equipe [TEAM_1]: canastras limpas 2, sujas 0 = +400 pts"))
    }

    @Test
    fun `cacheta ten card win removes two lives from opponent`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.CACHETA, maxPlayers = 2, pointLimit = 7)
        )
        viewModel.startGame()
        advanceUntilIdle()

        val tenCardWin = listOf(
            listOf(
                Card(suit = Suit.HEARTS, rank = Rank.ACE),
                Card(suit = Suit.CLUBS, rank = Rank.ACE),
                Card(suit = Suit.SPADES, rank = Rank.ACE)
            ),
            listOf(
                Card(suit = Suit.DIAMONDS, rank = Rank.FOUR),
                Card(suit = Suit.DIAMONDS, rank = Rank.FIVE),
                Card(suit = Suit.DIAMONDS, rank = Rank.SIX)
            ),
            listOf(
                Card(suit = Suit.CLUBS, rank = Rank.SEVEN),
                Card(suit = Suit.CLUBS, rank = Rank.EIGHT),
                Card(suit = Suit.CLUBS, rank = Rank.NINE),
                Card(suit = Suit.CLUBS, rank = Rank.TEN)
            )
        )

        viewModel.mutableGameState().value = viewModel.gameState.value.copy(
            myTableMelds = tenCardWin
        )
        viewModel.remoteHandsForTest()[1] = listOf(Card(suit = Suit.SPADES, rank = Rank.KING))
        viewModel.invokeTriggerWinFlow(emptyList())
        repo.emitIncoming(
            NetworkMessage(
                senderId = "client-1",
                type = "REPLY_WIN_ROUND",
                payload = roundReportPayload(playerId = "client-1", seat = 1),
                senderSeat = 1
            )
        )
        advanceUntilIdle()

        val summary = JSONObject(repo.broadcastMessages.last { it.type == "ROUND_SUMMARY" }.payload)
        assertEquals(-2, summary.getJSONArray("teamRoundScores").getInt(1))
        assertEquals(5, summary.getJSONArray("teamScores").getInt(1))
        assertTrue(summary.getString("breakdown").contains("perdeu 2 vida(s)"))
    }

    @Test
    fun `cacheta team win in four player mode counts shared table once instead of doubling partner report`() = runTest {
        // Em modo de dupla a mesa da equipe fica compartilhada: remoteTableForSeat devolve o
        // mesmo myTableMelds tanto para o vencedor quanto para o parceiro dele. Sem deduplicar,
        // a soma dos dois relatorios contaria as mesmas 5 cartas baixadas como 10 e penalizaria
        // o adversario com 2 vidas em vez de 1.
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.CACHETA, maxPlayers = 4, pointLimit = 7)
        )
        viewModel.startGame()
        advanceUntilIdle()

        val sharedTeamMeld = listOf(
            Card(suit = Suit.DIAMONDS, rank = Rank.FOUR),
            Card(suit = Suit.DIAMONDS, rank = Rank.FIVE),
            Card(suit = Suit.DIAMONDS, rank = Rank.SIX),
            Card(suit = Suit.DIAMONDS, rank = Rank.SEVEN),
            Card(suit = Suit.DIAMONDS, rank = Rank.EIGHT)
        )
        viewModel.mutableGameState().value = viewModel.gameState.value.copy(
            myTableMelds = listOf(sharedTeamMeld)
        )
        viewModel.invokeTriggerWinFlow(emptyList())
        listOf(1, 2, 3).forEach { seat ->
            repo.emitIncoming(
                NetworkMessage(
                    senderId = "player-$seat",
                    type = "REPLY_WIN_ROUND",
                    payload = roundReportPayload(playerId = "player-$seat", seat = seat),
                    senderSeat = seat
                )
            )
        }
        advanceUntilIdle()

        val summary = JSONObject(repo.broadcastMessages.last { it.type == "ROUND_SUMMARY" }.payload)
        assertEquals(-1, summary.getJSONArray("teamRoundScores").getInt(1))
        assertTrue(summary.getString("breakdown").contains("perdeu 1 vida(s)"))
    }

    @Test
    fun `tranca round summary applies the same asymmetric score on host and client`() = runTest {
        val hostRepo = FakeLocalNetworkRepository()
        val hostViewModel = MatchViewModel(
            networkRepository = hostRepo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.TRANCA, maxPlayers = 2, uniformCardPoints = false)
        )
        hostViewModel.startGame()
        advanceUntilIdle()

        val winnerMeld = cleanCanastraCards()
        val loserHand = listOf(
            Card(suit = Suit.CLUBS, rank = Rank.ACE),
            Card(suit = Suit.DIAMONDS, rank = Rank.FOUR)
        )

        hostViewModel.teamsThatPickedMortoForTest().add(0)
        hostViewModel.mutableGameState().value = hostViewModel.gameState.value.copy(
            myTableMelds = listOf(winnerMeld),
            opponentTableMelds = emptyList()
        )
        hostViewModel.remoteHandsForTest()[1] = loserHand
        hostViewModel.invokeTriggerWinFlow(emptyList())
        hostRepo.emitIncoming(
            NetworkMessage(
                senderId = "client-1",
                type = "REPLY_WIN_ROUND",
                payload = roundReportPayload(playerId = "client-1", seat = 1),
                senderSeat = 1
            )
        )
        advanceUntilIdle()

        val summaryMessage = hostRepo.broadcastMessages.last { it.type == "ROUND_SUMMARY" }
        val summary = JSONObject(summaryMessage.payload)
        assertEquals(365, summary.getJSONArray("teamRoundScores").getInt(0))
        assertEquals(-120, summary.getJSONArray("teamRoundScores").getInt(1))
        assertEquals(365, hostViewModel.gameState.value.roundEndDetails!!.myRoundScore)
        assertEquals(-120, hostViewModel.gameState.value.roundEndDetails!!.opponentRoundScore)
        assertTrue(summary.getString("breakdown").contains("mesa 7 carta(s) = 65 pts"))
        assertTrue(summary.getString("breakdown").contains("canastras limpas 1, sujas 0 = +200 pts"))
        assertTrue(summary.getString("breakdown").contains("Jogador [PLAYER_1]: mão 2 carta(s) = -20 pts"))
        assertTrue(summary.getString("breakdown").contains("bonus de bate +100"))

        val clientRepo = FakeLocalNetworkRepository()
        val clientViewModel = MatchViewModel(
            networkRepository = clientRepo,
            playerId = "client-1",
            isHost = false,
            config = MatchConfig(gameType = GameType.TRANCA, maxPlayers = 2, uniformCardPoints = false)
        )
        clientRepo.emitIncoming(NetworkMessage("host", "GAME_START", gameStartPayload(
            gameType = GameType.TRANCA,
            seat = 1,
            hand = loserHand.map { it.id },
            activeSeat = 1,
            maxPlayers = 2
        )))
        advanceUntilIdle()
        clientRepo.emitIncoming(summaryMessage)
        advanceUntilIdle()

        assertEquals(-120, clientViewModel.gameState.value.roundEndDetails!!.myRoundScore)
        assertEquals(365, clientViewModel.gameState.value.roundEndDetails!!.opponentRoundScore)
        assertEquals(-120, clientViewModel.gameState.value.myScore)
        assertEquals(365, clientViewModel.gameState.value.opponentScore)
    }

    @Test
    fun `buraco batida declares host win with clean canastra bonus in round summary`() = runTest {
        // Cacheta e Tranca ja tinham um teste de batida local pelo host; faltava
        // o equivalente no Buraco (item "batida" da matriz do roadmap por modo).
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 2, uniformCardPoints = false)
        )
        viewModel.startGame()
        advanceUntilIdle()

        // Ambos os times ja pegaram o morto, para o teste medir so a pontuacao
        // de canastra/bate, sem a penalidade separada de "morto nao pego".
        viewModel.teamsThatPickedMortoForTest().add(0)
        viewModel.teamsThatPickedMortoForTest().add(1)
        viewModel.mutableGameState().value = viewModel.gameState.value.copy(
            myTableMelds = listOf(cleanCanastraCards()),
            opponentTableMelds = emptyList()
        )
        val loserHand = listOf(
            Card(suit = Suit.CLUBS, rank = Rank.KING),
            Card(suit = Suit.DIAMONDS, rank = Rank.FOUR)
        )
        viewModel.remoteHandsForTest()[1] = loserHand
        viewModel.invokeTriggerWinFlow(emptyList())
        repo.emitIncoming(
            NetworkMessage(
                senderId = "client-1",
                type = "REPLY_WIN_ROUND",
                payload = roundReportPayload(playerId = "client-1", seat = 1),
                senderSeat = 1
            )
        )
        advanceUntilIdle()

        val summary = JSONObject(repo.broadcastMessages.last { it.type == "ROUND_SUMMARY" }.payload)
        assertEquals("host", summary.getString("winnerId"))
        assertEquals(0, summary.getInt("winnerTeam"))
        // mesa 7 carta(s) = 65 pts + canastra limpa +200 + bonus de bate +100
        assertEquals(365, summary.getJSONArray("teamRoundScores").getInt(0))
        // mao do perdedor: rei (10) + quatro (5) = -15
        assertEquals(-15, summary.getJSONArray("teamRoundScores").getInt(1))
        assertTrue(summary.getString("breakdown").contains("canastras limpas 1, sujas 0 = +200 pts"))
        assertTrue(summary.getString("breakdown").contains("bonus de bate +100"))
        assertFalse(viewModel.gameState.value.roundEndDetails!!.isMatchOver)
    }

    @Test
    fun `online client recognizes authenticated transport id as round winner`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "auth-client",
            isHost = false,
            config = MatchConfig(gameType = GameType.TRANCA, maxPlayers = 2)
        )
        repo.emitIncoming(
            NetworkMessage(
                senderId = "auth-host",
                type = "GAME_START",
                payload = gameStartPayload(
                    gameType = GameType.TRANCA,
                    seat = 1,
                    maxPlayers = 2
                ),
                senderSeat = 0
            )
        )
        advanceUntilIdle()

        val summary = JSONObject()
            .put("v", 1)
            .put("winnerId", "auth-client")
            .put("winnerRoundScore", 120)
            .put("loserRoundScore", -20)
            .put("winnerTotal", 120)
            .put("loserTotal", -20)
            .put("isMatchOver", false)
            .put("breakdown", "")
            .put("teamScores", org.json.JSONArray().put(-20).put(120))
            .put("winnerTeam", 1)
            .put("noWinner", false)
            .toString()
        repo.emitIncoming(
            NetworkMessage(
                senderId = "auth-host",
                type = "ROUND_SUMMARY",
                payload = summary,
                senderSeat = 0
            )
        )
        advanceUntilIdle()

        val details = viewModel.gameState.value.roundEndDetails!!
        assertEquals("Você", details.winnerName)
        assertEquals(120, details.myRoundScore)
        assertEquals(-20, details.opponentRoundScore)
        assertEquals(120, details.myNewTotal)
    }

    @Test
    fun `client ignores legacy round summary without team identity`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "auth-client",
            isHost = false,
            config = MatchConfig(gameType = GameType.TRANCA, maxPlayers = 2)
        )
        repo.emitIncoming(
            NetworkMessage(
                senderId = "auth-host",
                type = "GAME_START",
                payload = gameStartPayload(
                    gameType = GameType.TRANCA,
                    seat = 1,
                    maxPlayers = 2
                ),
                senderSeat = 0
            )
        )
        advanceUntilIdle()

        repo.emitIncoming(
            NetworkMessage(
                senderId = "auth-host",
                type = "ROUND_SUMMARY",
                payload = "auth-client|120|-20|120|-20|false|legado",
                senderSeat = 0
            )
        )
        advanceUntilIdle()

        assertFalse(viewModel.gameState.value.showRoundEndDialog)
        assertNull(viewModel.gameState.value.roundEndDetails)
    }

    @Test
    fun `solo bot replies when host wins round`() = runTest {
        val repo = SoloBotNetworkRepository()
        val replies = mutableListOf<NetworkMessage>()
        val collectJob = launch {
            repo.incomingMessages.collect { replies += it }
        }
        runCurrent()

        repo.startHosting("host", 0, MatchConfig(gameType = GameType.CACHETA, maxPlayers = 2))
        repo.sendMessageToClient(
            0,
            NetworkMessage(
                "host",
                "GAME_START",
                gameStartPayload(gameType = GameType.CACHETA, seat = 1, maxPlayers = 2)
            )
        )
        repo.sendMessage(NetworkMessage("host", "WIN_ROUND", roundReportPayload("host", 0, winnerId = "host")))
        advanceUntilIdle()

        val reply = replies.lastOrNull { it.type == "REPLY_WIN_ROUND" }
        assertNotNull(reply)
        val payload = JSONObject(reply!!.payload)
        assertEquals("machine-1", payload.getString("playerId"))
        assertEquals(1, payload.getInt("seat"))

        collectJob.cancel()
    }

    @Test
    fun `host versus solo bot finishes cacheta when host melds last cards`() = runTest {
        val repo = SoloBotNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.CACHETA, maxPlayers = 2)
        )
        viewModel.startGame()
        advanceUntilIdle()

        val winningHand = listOf(
            Card(Suit.HEARTS, Rank.SEVEN),
            Card(Suit.HEARTS, Rank.EIGHT),
            Card(Suit.HEARTS, Rank.NINE)
        )
        viewModel.mutableGameState().value = viewModel.gameState.value.copy(
            myHand = winningHand,
            selectedCards = emptySet(),
            myTableMelds = emptyList(),
            turnPhase = TurnPhase.ACTION,
            activeSeat = 0,
            showRoundEndDialog = false,
            roundEndDetails = null
        )

        winningHand.forEach { viewModel.toggleCardSelection(it) }
        viewModel.meldSelectedCards()
        advanceUntilIdle()

        val state = viewModel.gameState.value
        assertTrue(state.showRoundEndDialog)
        assertNotNull(state.roundEndDetails)
        assertEquals("Você", state.roundEndDetails!!.winnerName)
    }

    @Test
    fun `host versus solo bot finishes cacheta when host discards last card`() = runTest {
        val repo = SoloBotNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.CACHETA, maxPlayers = 2)
        )
        viewModel.startGame()
        advanceUntilIdle()

        val finalDiscard = Card(Suit.SPADES, Rank.KING)
        viewModel.mutableGameState().value = viewModel.gameState.value.copy(
            myHand = listOf(finalDiscard),
            selectedCards = emptySet(),
            myTableMelds = listOf(
                listOf(
                    Card(Suit.HEARTS, Rank.SEVEN),
                    Card(Suit.DIAMONDS, Rank.SEVEN),
                    Card(Suit.CLUBS, Rank.SEVEN)
                )
            ),
            discardPile = emptyList(),
            turnPhase = TurnPhase.ACTION,
            activeSeat = 0,
            showRoundEndDialog = false,
            roundEndDetails = null
        )

        viewModel.discardCard(finalDiscard)
        advanceUntilIdle()

        val state = viewModel.gameState.value
        assertTrue(state.myHand.isEmpty())
        assertEquals(finalDiscard, state.discardPile.last())
        assertTrue(state.showRoundEndDialog)
        assertNotNull(state.roundEndDetails)
        assertEquals("Você", state.roundEndDetails!!.winnerName)
    }

    @Test
    fun `host locally melds last cards to win buraco directly with clean canastra already on table`() = runTest {
        // Toda a cobertura de vitoria direta no Buraco/Tranca usava invokeTriggerWinFlow (reflection)
        // ou o processamento de jogada REMOTA (handleRemoteMeld/handleRemoteDiscard). Faltava exercitar
        // o caminho local de verdade (meldSelectedCards chamando GameRulesEngine.canDeclareWin) quando
        // o proprio host baixa a ultima carta tendo uma canastra limpa e o morto ja pego.
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 2)
        )
        viewModel.startGame()
        advanceUntilIdle()

        viewModel.teamsThatPickedMortoForTest().add(0)
        val winningHand = listOf(
            Card(suit = Suit.DIAMONDS, rank = Rank.FOUR),
            Card(suit = Suit.DIAMONDS, rank = Rank.FIVE),
            Card(suit = Suit.DIAMONDS, rank = Rank.SIX)
        )
        viewModel.mutableGameState().value = viewModel.gameState.value.copy(
            myHand = winningHand,
            selectedCards = emptySet(),
            myTableMelds = listOf(cleanCanastraCards()),
            turnPhase = TurnPhase.ACTION,
            activeSeat = 0,
            showRoundEndDialog = false,
            roundEndDetails = null
        )

        winningHand.forEach { viewModel.toggleCardSelection(it) }
        viewModel.meldSelectedCards()
        advanceUntilIdle()

        assertTrue(viewModel.gameState.value.myHand.isEmpty())
        assertTrue(repo.broadcastMessages.any { it.type == "WIN_ROUND" })

        repo.emitIncoming(
            NetworkMessage(
                senderId = "client-1",
                type = "REPLY_WIN_ROUND",
                payload = roundReportPayload(playerId = "client-1", seat = 1),
                senderSeat = 1
            )
        )
        advanceUntilIdle()

        val state = viewModel.gameState.value
        assertTrue(state.showRoundEndDialog)
        assertNotNull(state.roundEndDetails)
        assertEquals("Você", state.roundEndDetails!!.winnerName)
        assertTrue(
            repo.broadcastMessages.last { it.type == "ROUND_SUMMARY" }.payload.contains("bonus de bate")
        )
    }

    @Test
    fun `disabling clean canastra requirement lets host win locally with a dirty canastra on the table`() = runTest {
        // GameRulesEngineTest ja cobre canDeclareWin puro com requireCleanCanastraToWin=false, mas
        // ninguem exercitava essa flag pelo caminho real da ViewModel (meldSelectedCards). Isso
        // confirma que currentConfig.requireCleanCanastraToWin realmente chega ate a decisao de
        // bater, e nao um valor padrao hardcoded no meio do fluxo.
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 2, requireCleanCanastraToWin = false)
        )
        viewModel.startGame()
        advanceUntilIdle()

        viewModel.teamsThatPickedMortoForTest().add(0)
        val dirtyCanastra = listOf(
            Card(suit = Suit.HEARTS, rank = Rank.SEVEN),
            Card(suit = Suit.HEARTS, rank = Rank.EIGHT),
            Card(suit = Suit.HEARTS, rank = Rank.NINE),
            Card(suit = Suit.HEARTS, rank = Rank.TEN),
            Card(suit = Suit.HEARTS, rank = Rank.JACK),
            Card(suit = Suit.HEARTS, rank = Rank.QUEEN),
            Card(suit = Suit.SPADES, rank = Rank.ACE, isJoker = true)
        )
        val winningHand = listOf(
            Card(suit = Suit.CLUBS, rank = Rank.ACE),
            Card(suit = Suit.DIAMONDS, rank = Rank.ACE),
            Card(suit = Suit.SPADES, rank = Rank.ACE)
        )
        viewModel.mutableGameState().value = viewModel.gameState.value.copy(
            myHand = winningHand,
            selectedCards = emptySet(),
            myTableMelds = listOf(dirtyCanastra),
            turnPhase = TurnPhase.ACTION,
            activeSeat = 0,
            showRoundEndDialog = false,
            roundEndDetails = null
        )

        winningHand.forEach { viewModel.toggleCardSelection(it) }
        viewModel.meldSelectedCards()
        advanceUntilIdle()

        assertTrue(viewModel.gameState.value.myHand.isEmpty())
        assertTrue(repo.broadcastMessages.any { it.type == "WIN_ROUND" })
        assertFalse(repo.broadcastMessages.any { it.type == "COUNT_ROUND" })
    }

    @Test
    fun `host does not deal a new round when next round confirmation fails`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 2)
        )
        viewModel.startGame()
        advanceUntilIdle()
        val initialStarts = repo.privateClientMessages.count { it.message.type == "GAME_START" }
        repo.confirmedDeliveryResult = false

        viewModel.nextRound()
        advanceUntilIdle()

        assertEquals(initialStarts, repo.privateClientMessages.count { it.message.type == "GAME_START" })
        assertTrue(viewModel.gameState.value.feedbackMessage.contains("Não foi possível"))
    }

    @Test
    fun `next round against solo bot does not leave previous win active`() = runTest {
        val repo = SoloBotNetworkRepository()
        val botMessages = mutableListOf<NetworkMessage>()
        val collectJob = launch {
            repo.incomingMessages.collect { botMessages += it }
        }
        runCurrent()

        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.CACHETA, maxPlayers = 2)
        )
        viewModel.startGame()
        advanceUntilIdle()

        val winningHand = listOf(
            Card(Suit.CLUBS, Rank.FOUR),
            Card(Suit.CLUBS, Rank.FIVE),
            Card(Suit.CLUBS, Rank.SIX)
        )
        viewModel.mutableGameState().value = viewModel.gameState.value.copy(
            myHand = winningHand,
            selectedCards = emptySet(),
            myTableMelds = emptyList(),
            turnPhase = TurnPhase.ACTION,
            activeSeat = 0,
            showRoundEndDialog = false,
            roundEndDetails = null
        )

        winningHand.forEach { viewModel.toggleCardSelection(it) }
        viewModel.meldSelectedCards()
        advanceUntilIdle()
        assertTrue(viewModel.gameState.value.showRoundEndDialog)

        botMessages.clear()
        viewModel.nextRound()
        advanceTimeBy(700)
        runCurrent()

        val state = viewModel.gameState.value
        assertFalse(state.showRoundEndDialog)
        assertNull(state.roundEndDetails)
        assertTrue(state.myHand.isNotEmpty())
        assertTrue(botMessages.any { it.type == "REQ_DRAW_DECK" || it.type == "DRAW_DISCARD" })

        collectJob.cancel()
    }

    private fun roundReportPayload(
        playerId: String,
        seat: Int,
        winnerId: String? = null,
        hand: List<String> = emptyList(),
        tableMelds: List<org.json.JSONArray> = emptyList()
    ): String {
        return JSONObject()
            .put("v", 1)
            .put("playerId", playerId)
            .put("seat", seat)
            .put("hand", org.json.JSONArray().apply { hand.forEach { put(it) } })
            .put("tableMelds", org.json.JSONArray().apply { tableMelds.forEach { put(it) } })
            .apply { if (winnerId != null) put("winnerId", winnerId) }
            .toString()
    }

    private fun gameStartPayload(
        gameType: GameType = GameType.BURACO,
        seat: Int,
        hand: List<String> = emptyList(),
        autoSortHand: Boolean = true,
        activeSeat: Int = 1,
        maxPlayers: Int = 4,
        discardId: String = "",
        botDifficulty: BotDifficulty = BotDifficulty.NORMAL
    ): String {
        return JSONObject()
            .put("v", 1)
            .put("config", MatchConfig(
                gameType = gameType,
                maxPlayers = maxPlayers,
                autoSortHand = autoSortHand,
                botDifficulty = botDifficulty
            ).serialize())
            .put("hand", org.json.JSONArray().apply { hand.forEach { put(it) } })
            .put("seat", seat)
            .put("activeSeat", activeSeat)
            .put("discard", discardId)
            .put("turnCard", "")
            .put("deckSize", 59)
            .put("mortosLeft", 2)
            .toString()
    }

    private fun meldPayload(seat: Int): String {
        return JSONObject()
            .put("v", 1)
            .put(
                "cards",
                org.json.JSONArray()
                    .put(cardId(Rank.SEVEN, Suit.HEARTS))
                    .put(cardId(Rank.EIGHT, Suit.HEARTS))
                    .put(cardId(Rank.NINE, Suit.HEARTS))
            )
            .put("seat", seat)
            .put("team", seat.floorMod(2))
            .toString()
    }

    private fun cleanCanastraCards(): List<Card> = listOf(
        Card(suit = Suit.HEARTS, rank = Rank.SEVEN),
        Card(suit = Suit.HEARTS, rank = Rank.EIGHT),
        Card(suit = Suit.HEARTS, rank = Rank.NINE),
        Card(suit = Suit.HEARTS, rank = Rank.TEN),
        Card(suit = Suit.HEARTS, rank = Rank.JACK),
        Card(suit = Suit.HEARTS, rank = Rank.QUEEN),
        Card(suit = Suit.HEARTS, rank = Rank.KING)
    )

    private fun cardId(rank: Rank, suit: Suit): String = "${rank.name}_${suit.name}_BLACK"

    private fun mortoPayload(): String {
        return JSONObject()
            .put("v", 1)
            .put("hand", org.json.JSONArray().apply { mortoCardIds().forEach { put(it) } })
            .put("mortosLeft", 1)
            .toString()
    }

    private fun mortoCardIds(): List<String> {
        return listOf(
            Rank.ACE,
            Rank.TWO,
            Rank.FOUR,
            Rank.FIVE,
            Rank.SIX,
            Rank.SEVEN,
            Rank.EIGHT,
            Rank.NINE,
            Rank.TEN,
            Rank.JACK,
            Rank.QUEEN
        ).map { rank -> cardId(rank, Suit.CLUBS) }
    }

    private fun Int.floorMod(mod: Int): Int = ((this % mod) + mod) % mod
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val dispatcher: TestDispatcher = StandardTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}

private class FakeLocalNetworkRepository(
    override val requiresClientReadyHandshake: Boolean = false,
    override val isBotRepository: Boolean = false,
    override val isOnlineTransport: Boolean = false
) : LocalNetworkRepository {
    var serverDealResult: String? = null
    var serverDealCalls: Int = 0

    override fun requestServerDeal(onResult: (String?) -> Unit) {
        serverDealCalls++
        onResult(serverDealResult)
    }

    var serverDrawResult: String? = null
    val serverDrawCalls = mutableListOf<Int>()

    override fun requestServerDraw(seat: Int, onResult: (String?) -> Unit) {
        serverDrawCalls += seat
        onResult(serverDrawResult)
    }

    var serverMortoResult: String? = null
    val serverMortoCalls = mutableListOf<Int>()

    override fun requestServerMorto(seat: Int, indirect: Boolean, onResult: (String?) -> Unit) {
        serverMortoCalls += seat
        onResult(serverMortoResult)
    }
    override val discoveredRooms: StateFlow<List<DiscoveredRoom>> = MutableStateFlow(emptyList())
    override val connectedClientsCount: StateFlow<Int> = MutableStateFlow(0)
    override val incomingMessages: MutableSharedFlow<NetworkMessage> = MutableSharedFlow(replay = 64)
    override val connectionStatus: StateFlow<ConnectionStatus> = MutableStateFlow(ConnectionStatus.IDLE)

    val broadcastMessages = mutableListOf<NetworkMessage>()
    val privateClientMessages = mutableListOf<PrivateClientMessage>()
    val privatePlayerMessages = mutableListOf<PrivatePlayerMessage>()
    val confirmedPlayerAttempts = mutableListOf<PrivatePlayerMessage>()
    val eventLog = mutableListOf<String>()
    var confirmedDeliveryResult: Boolean = true

    override fun startHosting(playerName: String, port: Int, config: MatchConfig?) = Unit
    override fun stopHosting() = Unit
    override fun startDiscovery() = Unit
    override fun stopDiscovery() = Unit
    override fun connectToRoom(host: String, port: Int) = Unit
    override fun reconnect(): Boolean = false
    override fun disconnect() = Unit
    override fun resetConnectionStatus() = Unit

    override fun sendMessage(message: NetworkMessage) {
        eventLog += "broadcast:${message.type}"
        broadcastMessages += message
    }

    override fun sendMessageConfirmed(message: NetworkMessage, onResult: (Boolean) -> Unit) {
        if (confirmedDeliveryResult) sendMessage(message)
        onResult(confirmedDeliveryResult)
    }

    override fun sendMessageToClient(clientIndex: Int, message: NetworkMessage): Boolean {
        eventLog += "client:${message.type}"
        privateClientMessages += PrivateClientMessage(clientIndex, message)
        return true
    }

    override fun sendMessageToPlayer(playerId: String, message: NetworkMessage): Boolean {
        eventLog += "player:${message.type}"
        privatePlayerMessages += PrivatePlayerMessage(playerId, message)
        return true
    }

    override fun sendMessageToSeatConfirmed(
        seat: Int,
        message: NetworkMessage,
        onResult: (Boolean) -> Unit
    ) {
        if (confirmedDeliveryResult) sendMessageToClient(seat - 1, message)
        onResult(confirmedDeliveryResult)
    }

    override fun sendMessageToPlayerConfirmed(
        playerId: String,
        message: NetworkMessage,
        onResult: (Boolean) -> Unit
    ) {
        confirmedPlayerAttempts += PrivatePlayerMessage(playerId, message)
        if (confirmedDeliveryResult) sendMessageToPlayer(playerId, message)
        onResult(confirmedDeliveryResult)
    }

    fun emitIncoming(message: NetworkMessage) {
        incomingMessages.tryEmit(message)
    }
}

private data class PrivateClientMessage(val clientIndex: Int, val message: NetworkMessage)
private data class PrivatePlayerMessage(val playerId: String, val message: NetworkMessage)

private fun Any.setPrivateField(name: String, value: Any) {
    val field = this::class.java.getDeclaredField(name)
    field.isAccessible = true
    field.set(this, value)
}

@Suppress("UNCHECKED_CAST")
private fun MatchViewModel.mutableGameState(): MutableStateFlow<GameState> {
    val field = this::class.java.getDeclaredField("_gameState")
    field.isAccessible = true
    return field.get(this) as MutableStateFlow<GameState>
}

@Suppress("UNCHECKED_CAST")
private fun MatchViewModel.remoteHandsForTest(): MutableMap<Int, List<Card>> {
    val field = this::class.java.getDeclaredField("remoteHandsBySeat")
    field.isAccessible = true
    return field.get(this) as MutableMap<Int, List<Card>>
}

@Suppress("UNCHECKED_CAST")
private fun MatchViewModel.masterDeckForTest(): MutableList<Card> {
    val field = this::class.java.getDeclaredField("masterDeck")
    field.isAccessible = true
    return field.get(this) as MutableList<Card>
}

@Suppress("UNCHECKED_CAST")
private fun MatchViewModel.mortosForTest(): MutableList<List<Card>> {
    val field = this::class.java.getDeclaredField("mortos")
    field.isAccessible = true
    return field.get(this) as MutableList<List<Card>>
}

@Suppress("UNCHECKED_CAST")
private fun MatchViewModel.deckServedSeatsForTest(): MutableSet<Int> {
    val field = this::class.java.getDeclaredField("deckServedSeatsThisTurn")
    field.isAccessible = true
    return field.get(this) as MutableSet<Int>
}

@Suppress("UNCHECKED_CAST")
private fun MatchViewModel.teamsThatPickedMortoForTest(): MutableSet<Int> {
    val field = this::class.java.getDeclaredField("teamsThatPickedMorto")
    field.isAccessible = true
    return field.get(this) as MutableSet<Int>
}

@Suppress("UNCHECKED_CAST")
private fun MatchViewModel.remotePlayerSeatsForTest(): MutableMap<String, Int> {
    val field = this::class.java.getDeclaredField("remotePlayerSeats")
    field.isAccessible = true
    return field.get(this) as MutableMap<String, Int>
}

private fun MatchViewModel.currentRoundIdForTest(): String? {
    val field = this::class.java.getDeclaredField("currentRoundId")
    field.isAccessible = true
    return field.get(this) as String?
}

private fun MatchViewModel.invokeBuildSnapshotJson(state: GameState): String {
    val method = this::class.java.getDeclaredMethod("buildSnapshotJson", GameState::class.java)
    method.isAccessible = true
    return method.invoke(this, state) as String
}

private fun MatchViewModel.invokeApplySnapshotJson(json: String): Boolean {
    val method = this::class.java.getDeclaredMethod("applySnapshotJson", String::class.java)
    method.isAccessible = true
    return method.invoke(this, json) as Boolean
}

private fun MatchViewModel.pendingCountOnlyRoundForTest(): Boolean {
    val field = this::class.java.getDeclaredField("pendingCountOnlyRound")
    field.isAccessible = true
    return field.get(this) as Boolean
}

private fun MatchViewModel.invokeBeginCountOnlyRound() {
    val method = this::class.java.getDeclaredMethod("beginCountOnlyRound")
    method.isAccessible = true
    method.invoke(this)
}

private fun MatchViewModel.invokeTriggerWinFlow(hand: List<Card>) {
    val method = this::class.java.getDeclaredMethod("triggerWinFlow", List::class.java)
    method.isAccessible = true
    method.invoke(this, hand)
}

private fun MatchViewModel.invokeApplyServerMortoResultForOwnSeat(rawJson: String?, meldLabel: String) {
    val method = this::class.java.getDeclaredMethod(
        "applyServerMortoResultForOwnSeat", String::class.java, String::class.java
    )
    method.isAccessible = true
    method.invoke(this, rawJson, meldLabel)
}

private fun MatchViewModel.invokeApplyServerMortoResultForRemoteSeat(requestingSeat: Int, team: Int, rawJson: String?) {
    val method = this::class.java.getDeclaredMethod(
        "applyServerMortoResultForRemoteSeat", Int::class.java, Int::class.java, String::class.java
    )
    method.isAccessible = true
    method.invoke(this, requestingSeat, team, rawJson)
}

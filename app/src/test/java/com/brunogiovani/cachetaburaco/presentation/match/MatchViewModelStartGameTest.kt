package com.brunogiovani.cachetaburaco.presentation.match

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
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
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
    fun `cacheta respects room cards per player setting`() = runTest {
        val repo = FakeLocalNetworkRepository()
        val viewModel = MatchViewModel(
            networkRepository = repo,
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.CACHETA, maxPlayers = 2, cachetaCardsPerPlayer = 7)
        )

        viewModel.startGame()
        advanceUntilIdle()

        val state = viewModel.gameState.value
        assertEquals(7, state.myHand.size)
        assertEquals(0, state.mortosLeft)

        val clientStart = JSONObject(repo.privateClientMessages.single().message.payload)
        assertEquals(7, clientStart.getJSONArray("hand").length())
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

        val clientStart = JSONObject(repo.privateClientMessages.single().message.payload)
        val clientHand = clientStart.getJSONArray("hand")
        
        val meldPayload = JSONObject()
            .put("v", 1)
            .put("cards", clientHand)
            .put("seat", 1)
            .put("team", 1)
            .toString()

        repo.emitIncoming(NetworkMessage("client-1", "REQ_PICK_MORTO", """{"v":1,"seat":1}""", messageId = "early-morto-request"))
        advanceUntilIdle()
        assertEquals(0, repo.privatePlayerMessages.count { it.playerId == "client-1" && it.message.type == "SERVE_MORTO" })

        // Esvazia a mão do cliente para o host aceitar o pedido de morto
        repo.emitIncoming(NetworkMessage("client-1", "MELD", meldPayload))
        advanceUntilIdle()

        repo.emitIncoming(NetworkMessage("client-1", "REQ_PICK_MORTO", """{"v":1,"seat":1}"""))
        advanceUntilIdle()
        repo.emitIncoming(NetworkMessage("client-1", "REQ_PICK_MORTO", """{"v":1,"seat":1}""", messageId = "second-morto-request"))
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

        repo.emitIncoming(NetworkMessage("partner", "PICK_MORTO", """{"v":1,"mortosLeft":1,"seat":2,"team":0}"""))
        advanceUntilIdle()

        repo.emitIncoming(NetworkMessage("player-1", "WIN_ROUND", roundReportPayload("player-1", 1, winnerId = "player-1")))
        advanceUntilIdle()
        repo.emitIncoming(NetworkMessage("player-2", "REPLY_WIN_ROUND", roundReportPayload("player-2", 2)))
        advanceUntilIdle()
        repo.emitIncoming(NetworkMessage("player-3", "REPLY_WIN_ROUND", roundReportPayload("player-3", 3)))
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

        repo.emitIncoming(NetworkMessage("player-1", "WIN_ROUND", roundReportPayload("player-1", 1, winnerId = "player-1")))
        advanceUntilIdle()
        repo.emitIncoming(NetworkMessage("player-2", "REPLY_WIN_ROUND", roundReportPayload("player-2", 2)))
        advanceUntilIdle()
        repo.emitIncoming(NetworkMessage("player-3", "REPLY_WIN_ROUND", roundReportPayload("player-3", 3)))
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
            playerId = "host",
            isHost = true,
            config = MatchConfig(gameType = GameType.TRANCA, maxPlayers = 2)
        )
        viewModel.startGame()
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
            .put("seat", 1)
            .put("team", 1)
            .put("replaceIndex", -1)
            .toString()
        val duplicated = NetworkMessage("client-1", "MELD", payload, messageId = "same-message-id")

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
        viewModel.setPrivateField("masterDeck", mutableListOf(nextCard, redThree))

        repo.emitIncoming(NetworkMessage("client-1", "REQ_DRAW_DECK", """{"v":1,"seat":1}"""))
        advanceUntilIdle()
        repo.emitIncoming(NetworkMessage("client-1", "REQ_DRAW_DECK", """{"v":1,"seat":1}"""))
        advanceUntilIdle()

        val servedCards = repo.privatePlayerMessages
            .filter { it.message.type == "SERVE_CARD" }
            .map { it.message.payload }
        assertEquals(listOf(redThree.id, nextCard.id), servedCards)
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
        assertTrue(viewModel.gameState.value.feedbackMessage.contains("nao pode comprar do lixo"))
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

        val cleanCanastra = cleanCanastraJson()
        repo.emitIncoming(NetworkMessage("player-1", "WIN_ROUND", roundReportPayload("player-1", 1, winnerId = "player-1", tableMelds = listOf(cleanCanastra))))
        advanceUntilIdle()
        repo.emitIncoming(NetworkMessage("player-2", "REPLY_WIN_ROUND", roundReportPayload("player-2", 2)))
        advanceUntilIdle()
        repo.emitIncoming(NetworkMessage("player-3", "REPLY_WIN_ROUND", roundReportPayload("player-3", 3, tableMelds = listOf(cleanCanastra))))
        advanceUntilIdle()

        val summary = JSONObject(repo.broadcastMessages.last { it.type == "ROUND_SUMMARY" }.payload)
        val breakdown = summary.getString("breakdown")

        assertTrue(breakdown.contains("Equipe [TEAM_1]: Mesa +65 | Canastras +200 = 265 pts"))
        assertFalse(breakdown.contains("Equipe [TEAM_1]: Mesa +130 | Canastras +400 = 530 pts"))
    }

    private fun roundReportPayload(
        playerId: String,
        seat: Int,
        winnerId: String? = null,
        tableMelds: List<org.json.JSONArray> = emptyList()
    ): String {
        return JSONObject()
            .put("v", 1)
            .put("playerId", playerId)
            .put("seat", seat)
            .put("hand", org.json.JSONArray())
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
        discardId: String = ""
    ): String {
        return JSONObject()
            .put("v", 1)
            .put("config", MatchConfig(gameType = gameType, maxPlayers = maxPlayers, autoSortHand = autoSortHand).serialize())
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

    private fun cleanCanastraJson(): org.json.JSONArray {
        return org.json.JSONArray()
            .put(cardId(Rank.SEVEN, Suit.HEARTS))
            .put(cardId(Rank.EIGHT, Suit.HEARTS))
            .put(cardId(Rank.NINE, Suit.HEARTS))
            .put(cardId(Rank.TEN, Suit.HEARTS))
            .put(cardId(Rank.JACK, Suit.HEARTS))
            .put(cardId(Rank.QUEEN, Suit.HEARTS))
            .put(cardId(Rank.KING, Suit.HEARTS))
    }

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

private class FakeLocalNetworkRepository : LocalNetworkRepository {
    override val discoveredRooms: StateFlow<List<DiscoveredRoom>> = MutableStateFlow(emptyList())
    override val connectedClientsCount: StateFlow<Int> = MutableStateFlow(0)
    override val incomingMessages: MutableSharedFlow<NetworkMessage> = MutableSharedFlow(replay = 64)
    override val connectionStatus: StateFlow<ConnectionStatus> = MutableStateFlow(ConnectionStatus.IDLE)

    val broadcastMessages = mutableListOf<NetworkMessage>()
    val privateClientMessages = mutableListOf<PrivateClientMessage>()
    val privatePlayerMessages = mutableListOf<PrivatePlayerMessage>()

    override fun startHosting(playerName: String, port: Int, config: MatchConfig?) = Unit
    override fun stopHosting() = Unit
    override fun startDiscovery() = Unit
    override fun stopDiscovery() = Unit
    override fun connectToRoom(host: String, port: Int) = Unit
    override fun reconnect(): Boolean = false
    override fun disconnect() = Unit
    override fun resetConnectionStatus() = Unit

    override fun sendMessage(message: NetworkMessage) {
        broadcastMessages += message
    }

    override fun sendMessageToClient(clientIndex: Int, message: NetworkMessage): Boolean {
        privateClientMessages += PrivateClientMessage(clientIndex, message)
        return true
    }

    override fun sendMessageToPlayer(playerId: String, message: NetworkMessage): Boolean {
        privatePlayerMessages += PrivatePlayerMessage(playerId, message)
        return true
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

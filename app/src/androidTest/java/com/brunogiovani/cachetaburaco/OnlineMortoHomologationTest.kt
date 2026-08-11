package com.brunogiovani.cachetaburaco

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.brunogiovani.cachetaburaco.data.online.OnlineEventCodec
import com.brunogiovani.cachetaburaco.data.online.OnlineRoomSession
import com.brunogiovani.cachetaburaco.data.online.OnlineRoomStatus
import com.brunogiovani.cachetaburaco.data.online.OnlineRoomSummary
import com.brunogiovani.cachetaburaco.data.online.SupabaseClientProvider
import com.brunogiovani.cachetaburaco.data.online.SupabaseOnlineRoomDataSource
import com.brunogiovani.cachetaburaco.data.online.SupabaseProjectConfig
import com.brunogiovani.cachetaburaco.domain.models.GameType
import com.brunogiovani.cachetaburaco.domain.models.MatchConfig
import com.brunogiovani.cachetaburaco.domain.repositories.NetworkMessage
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.minimalConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.query.Order
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Prepara uma sala descartavel para homologar a entrega online do morto.
 *
 * Este teste nao roda na suite comum: ele so atua quando `roomCode` e enviado
 * ao runner. Uso a sessao real do host e o RECONNECT_STATE de producao para
 * deixar o convidado sem cartas antes da fase de compra. Depois disso, a unica
 * carta vem do monte real e selecao, descarte e pedido do morto continuam
 * sendo feitos pela UI e pelas RPCs normais do jogo.
 */
@RunWith(AndroidJUnit4::class)
class OnlineMortoHomologationTest {

    @Test
    fun convidadoPegaMortoDiretoNoServidorPublicado() = runBlocking {
        val enabled = InstrumentationRegistry.getArguments()
            .getString("automaticServerMorto")
            .orEmpty()
            .toBooleanStrictOrNull() == true
        assumeTrue(
            "Informe -e automaticServerMorto true para executar a homologacao remota descartavel.",
            enabled
        )

        val hostClient = isolatedClient()
        val guestClient = isolatedClient()
        val hostDataSource = SupabaseOnlineRoomDataSource(hostClient)
        val guestDataSource = SupabaseOnlineRoomDataSource(guestClient)
        val roomCode = "MRT" + UUID.randomUUID().toString()
            .replace("-", "")
            .take(5)
            .uppercase()
        val config = MatchConfig(gameType = GameType.BURACO, maxPlayers = 2)
        var hostSession: OnlineRoomSession? = null
        var guestSession: OnlineRoomSession? = null

        try {
            hostSession = hostDataSource.createRoom("Teste Host", roomCode, config)
            guestSession = guestDataSource.joinRoom("Teste Convidado", roomCode)
            val deal = JSONObject(
                hostDataSource.startRound(hostSession, UUID.randomUUID().toString())
            )
            val roundId = deal.getString("roundId")
            val initialEvents = loadEvents(hostClient, hostSession.room.roomId)
            val publicState = initialEvents.asReversed().firstNotNullOfOrNull { row ->
                row.takeIf { it.eventType == "PUBLIC_STATE" }
                    ?.payload
                    ?.toString()
                    ?.let(OnlineEventCodec::decode)
                    ?.takeIf { it.roundId == roundId }
            }?.payload?.let(::JSONObject) ?: error("PUBLIC_STATE inicial nao encontrado.")

            val reconnectPayload = JSONObject()
                .put("v", 2)
                .put("roundId", roundId)
                .put("config", config.serialize())
                .put("seat", GUEST_SEAT)
                .put("activeSeat", GUEST_SEAT)
                .put("hand", JSONArray())
                .put("myTableMelds", publicState.optJSONArray("team1Melds") ?: JSONArray())
                .put("hostTableMelds", publicState.optJSONArray("team0Melds") ?: JSONArray())
                .put("discard", publicState.optJSONArray("discardPile")?.optString(0).orEmpty())
                .put("discardPile", publicState.optJSONArray("discardPile") ?: JSONArray())
                .put("turnCard", publicState.optString("turnCard"))
                .put("deckSize", publicState.optInt("deckSize"))
                .put("mortosLeft", publicState.optInt("mortosLeft"))
                .put("teamScores", JSONArray().put(0).put(0))
                .put("isReconnect", true)
                .toString()
            assertTrue(
                hostDataSource.publishEvent(
                    session = hostSession,
                    message = NetworkMessage(
                        senderId = hostSession.playerId,
                        type = "RECONNECT_STATE",
                        payload = reconnectPayload,
                        messageId = UUID.randomUUID().toString(),
                        roundId = roundId
                    ),
                    recipientSeat = GUEST_SEAT
                )
            )

            val synchronizedState = JSONObject(publicState.toString())
                .put("activeSeat", GUEST_SEAT)
                .put("handCounts", JSONArray().put(config.cardsPerPlayer).put(0))
            assertTrue(
                hostDataSource.publishEvent(
                    session = hostSession,
                    message = NetworkMessage(
                        senderId = hostSession.playerId,
                        type = "PUBLIC_STATE",
                        payload = synchronizedState.toString(),
                        messageId = UUID.randomUUID().toString(),
                        roundId = roundId
                    )
                )
            )

            val result = JSONObject(
                guestDataSource.takeMorto(
                    session = guestSession,
                    seat = GUEST_SEAT,
                    indirect = false,
                    requestId = UUID.randomUUID().toString()
                )
            )
            val hand = result.optJSONArray("hand") ?: JSONArray()
            assertTrue("O servidor recusou o morto direto do convidado: $result", result.optString("status") == "OK")
            assertTrue("O morto precisa entregar exatamente 11 cartas: $result", hand.length() == config.cardsPerPlayer)
            assertTrue("O servidor deveria manter um morto disponivel: $result", result.optInt("mortosLeft") == 1)

            val canonicalHand = JSONObject(
                hostDataSource.loadRemoteHand(hostSession, GUEST_SEAT)
            )
            assertTrue(
                "O ledger do convidado nao recebeu as 11 cartas.",
                canonicalHand.optJSONArray("hand")?.length() == config.cardsPerPlayer
            )
            assertTrue(
                "O aviso MORTO_TAKEN nao chegou ao historico da sala.",
                loadEvents(hostClient, hostSession.room.roomId).any {
                    it.eventType == "MORTO_TAKEN" && it.recipientSeat == 0
                }
            )
        } finally {
            guestSession?.let { runCatching { guestDataSource.leaveRoom(it) } }
            hostSession?.let { runCatching { hostDataSource.closeRoom(it) } }
            runCatching { guestDataSource.signOut() }
            runCatching { hostDataSource.signOut() }
        }
    }

    @Test
    fun prepararConvidadoParaUltimoDescarte() = runBlocking {
        val roomCode = InstrumentationRegistry.getArguments()
            .getString("roomCode")
            .orEmpty()
            .trim()
            .uppercase()
        assumeTrue("Informe -e roomCode CODIGO para executar a homologacao online.", roomCode.isNotEmpty())

        val client = SupabaseClientProvider.client
        client.auth.awaitInitialization()
        val hostId = requireNotNull(client.auth.currentUserOrNull()?.id) {
            "O emulador do host nao possui uma sessao Supabase autenticada."
        }

        val room = client.from("match_rooms").select {
            filter {
                filter("room_code", FilterOperator.EQ, roomCode)
                filter("host_id", FilterOperator.EQ, hostId)
            }
        }.decodeList<HomologationRoomRow>().singleOrNull()
            ?: error("A sala $roomCode nao pertence a sessao autenticada deste aparelho.")

        val events = client.from("match_events").select {
            filter { filter("room_id", FilterOperator.EQ, room.id) }
            order(column = "id", order = Order.ASCENDING)
        }.decodeList<HomologationEventRow>()

        val gameStart = events.asReversed().firstNotNullOfOrNull { row ->
            row.takeIf { it.eventType == "GAME_START" && it.recipientSeat == GUEST_SEAT }
                ?.payload
                ?.toString()
                ?.let(OnlineEventCodec::decode)
        } ?: error("A sala ainda nao possui GAME_START privado para o convidado.")
        val startState = JSONObject(gameStart.payload)
        val originalHand = startState.optJSONArray("hand") ?: JSONArray()
        require(originalHand.length() > 0) { "A rodada ainda nao distribuiu a mao do convidado." }

        val publicState = events.asReversed().firstNotNullOfOrNull { row ->
            row.takeIf { it.eventType == "PUBLIC_STATE" }
                ?.payload
                ?.toString()
                ?.let(OnlineEventCodec::decode)
                ?.takeIf { it.roundId == gameStart.roundId }
        }?.payload?.let(::JSONObject) ?: JSONObject()

        val roundId = gameStart.roundId
            ?: startState.optString("roundId").takeIf(String::isNotBlank)
            ?: error("GAME_START sem roundId.")
        val config = MatchConfig.deserialize(startState.getString("config"))
        val reconnectPayload = JSONObject()
            .put("v", 2)
            .put("roundId", roundId)
            .put("config", config.serialize())
            .put("seat", GUEST_SEAT)
            .put("activeSeat", GUEST_SEAT)
            .put("hand", JSONArray())
            .put("myTableMelds", publicState.optJSONArray("team1Melds") ?: JSONArray())
            .put("hostTableMelds", publicState.optJSONArray("team0Melds") ?: JSONArray())
            .put("discard", publicState.optJSONArray("discardPile")?.optString(0).orEmpty())
            .put("discardPile", publicState.optJSONArray("discardPile") ?: JSONArray())
            .put("turnCard", publicState.optString("turnCard"))
            .put("deckSize", publicState.optInt("deckSize"))
            .put("mortosLeft", publicState.optInt("mortosLeft"))
            .put("teamScores", JSONArray().put(0).put(0))
            .put("isReconnect", true)
            .toString()

        val session = OnlineRoomSession(
            room = OnlineRoomSummary(
                roomId = room.id,
                roomCode = room.roomCode,
                hostPlayerId = hostId,
                config = config,
                status = OnlineRoomStatus.PLAYING,
                connectedPlayers = 2
            ),
            playerId = hostId,
            seat = 0,
            isHost = true
        )
        val published = SupabaseOnlineRoomDataSource(client).publishEvent(
            session = session,
            message = NetworkMessage(
                senderId = hostId,
                type = "RECONNECT_STATE",
                payload = reconnectPayload,
                messageId = UUID.randomUUID().toString(),
                roundId = roundId
            ),
            recipientSeat = GUEST_SEAT
        )

        assertTrue("O servidor recusou o RECONNECT_STATE de homologacao.", published)
        println("MORTO_HOMOLOGATION_READY=$roomCode")
    }

    @Test
    fun servirCompraDoMonteAoConvidado() = runBlocking {
        val context = loadHostContext()
        val result = JSONObject(
            context.dataSource.drawDeckCard(
                session = context.session,
                seat = GUEST_SEAT,
                requestId = UUID.randomUUID().toString()
            )
        )

        assertTrue("A compra preparada para o convidado nao foi aceita.", result.optString("status") == "OK")
        assertTrue("O servidor nao devolveu a carta comprada.", result.optString("card").isNotBlank())
        println("MORTO_HOMOLOGATION_DRAW=${result.optString("card")}")
    }

    @Test
    fun reativarPresencaDoHost() = runBlocking {
        val context = loadHostContext()
        assertTrue(
            "A presenca do host nao pode ser renovada nesta sala.",
            context.dataSource.touchPresence(context.session)
        )
    }

    @Test
    fun verificarMortoEntregueAoConvidado() = runBlocking {
        val context = loadHostContext()
        val remoteHand = JSONObject(context.dataSource.loadRemoteHand(context.session, GUEST_SEAT))
        val hand = remoteHand.optJSONArray("hand") ?: JSONArray()

        assertTrue("A mao canonica do convidado nao foi encontrada.", remoteHand.optString("status") == "OK")
        assertTrue(
            "O morto precisa entregar exatamente ${context.session.room.config.cardsPerPlayer} cartas.",
            hand.length() == context.session.room.config.cardsPerPlayer
        )
        assertTrue("O servidor deveria manter apenas um morto disponivel.", remoteHand.optInt("mortosLeft") == 1)

        val events = loadEvents(context.client, context.session.room.roomId)
        assertTrue(
            "O servidor nao publicou MORTO_TAKEN para o host.",
            events.any { it.eventType == "MORTO_TAKEN" }
        )
        val publicState = events.asReversed().firstNotNullOfOrNull { row ->
            row.takeIf { it.eventType == "PUBLIC_STATE" }
                ?.payload
                ?.toString()
                ?.let(OnlineEventCodec::decode)
                ?.takeIf { it.roundId == context.roundId }
        }?.payload?.let(::JSONObject) ?: error("PUBLIC_STATE final nao encontrado.")
        val lastDiscard = events.asReversed().firstNotNullOfOrNull { row ->
            row.takeIf { it.eventType == "DISCARD" }
                ?.payload
                ?.toString()
                ?.let(OnlineEventCodec::decode)
                ?.takeIf { it.roundId == context.roundId }
        }?.payload?.let(::JSONObject)?.optString("card").orEmpty()
        val discardPile = publicState.optJSONArray("discardPile") ?: JSONArray()
        assertTrue("O evento de descarte final nao foi encontrado.", lastDiscard.isNotBlank())
        assertTrue(
            "A fotografia publica perdeu a carta descartada antes do morto.",
            discardPile.optString(discardPile.length() - 1) == lastDiscard
        )
        val handCounts = publicState.optJSONArray("handCounts") ?: JSONArray()
        assertTrue(
            "A fotografia publica nao mostra as 11 cartas do convidado.",
            handCounts.optInt(GUEST_SEAT) == context.session.room.config.cardsPerPlayer
        )
        assertTrue("A fotografia publica nao consumiu o morto.", publicState.optInt("mortosLeft") == 1)
        assertTrue("O turno deveria passar ao host depois do morto indireto.", publicState.optInt("activeSeat") == 0)
    }

    private suspend fun loadHostContext(): HomologationHostContext {
        val roomCode = InstrumentationRegistry.getArguments()
            .getString("roomCode")
            .orEmpty()
            .trim()
            .uppercase()
        assumeTrue("Informe -e roomCode CODIGO para executar a homologacao online.", roomCode.isNotEmpty())

        val client = SupabaseClientProvider.client
        client.auth.awaitInitialization()
        val hostId = requireNotNull(client.auth.currentUserOrNull()?.id) {
            "O emulador do host nao possui uma sessao Supabase autenticada."
        }
        val room = client.from("match_rooms").select {
            filter {
                filter("room_code", FilterOperator.EQ, roomCode)
                filter("host_id", FilterOperator.EQ, hostId)
            }
        }.decodeList<HomologationRoomRow>().singleOrNull()
            ?: error("A sala $roomCode nao pertence a sessao autenticada deste aparelho.")
        val gameStart = loadEvents(client, room.id).asReversed().firstNotNullOfOrNull { row ->
            row.takeIf { it.eventType == "GAME_START" && it.recipientSeat == GUEST_SEAT }
                ?.payload
                ?.toString()
                ?.let(OnlineEventCodec::decode)
        } ?: error("A sala ainda nao possui GAME_START privado para o convidado.")
        val startState = JSONObject(gameStart.payload)
        val config = MatchConfig.deserialize(startState.getString("config"))
        val roundId = gameStart.roundId
            ?: startState.optString("roundId").takeIf(String::isNotBlank)
            ?: error("GAME_START sem roundId.")
        val session = OnlineRoomSession(
            room = OnlineRoomSummary(
                roomId = room.id,
                roomCode = room.roomCode,
                hostPlayerId = hostId,
                config = config,
                status = OnlineRoomStatus.PLAYING,
                connectedPlayers = 2
            ),
            playerId = hostId,
            seat = 0,
            isHost = true
        )
        return HomologationHostContext(
            client = client,
            dataSource = SupabaseOnlineRoomDataSource(client),
            session = session,
            roundId = roundId
        )
    }

    private suspend fun loadEvents(
        client: io.github.jan.supabase.SupabaseClient,
        roomId: String
    ): List<HomologationEventRow> {
        return client.from("match_events").select {
            filter { filter("room_id", FilterOperator.EQ, roomId) }
            order(column = "id", order = Order.ASCENDING)
        }.decodeList()
    }

    private companion object {
        const val GUEST_SEAT = 1
    }
}

private fun isolatedClient(): io.github.jan.supabase.SupabaseClient {
    return createSupabaseClient(
        supabaseUrl = SupabaseProjectConfig.URL,
        supabaseKey = SupabaseProjectConfig.PUBLISHABLE_KEY
    ) {
        install(Auth) { minimalConfig() }
        install(Postgrest)
    }
}

private data class HomologationHostContext(
    val client: io.github.jan.supabase.SupabaseClient,
    val dataSource: SupabaseOnlineRoomDataSource,
    val session: OnlineRoomSession,
    val roundId: String
)

@Serializable
private data class HomologationRoomRow(
    val id: String,
    @SerialName("room_code") val roomCode: String
)

@Serializable
private data class HomologationEventRow(
    val id: Long,
    @SerialName("event_type") val eventType: String,
    @SerialName("recipient_seat") val recipientSeat: Int? = null,
    val payload: JsonObject
)

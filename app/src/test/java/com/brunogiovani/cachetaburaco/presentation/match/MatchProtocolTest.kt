package com.brunogiovani.cachetaburaco.presentation.match

import com.brunogiovani.cachetaburaco.domain.models.Card
import com.brunogiovani.cachetaburaco.domain.models.DeckColor
import com.brunogiovani.cachetaburaco.domain.models.GameType
import com.brunogiovani.cachetaburaco.domain.models.MatchConfig
import com.brunogiovani.cachetaburaco.domain.models.Rank
import com.brunogiovani.cachetaburaco.domain.models.Suit
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Estas contas viviam dentro do MatchViewModel e so davam pra exercitar
 * montando uma partida inteira. Separadas em MatchProtocol.kt, da pra testar
 * a regra direto -- que e o ponto, ja que sao a mesma conta que a migration
 * 0048 replica no servidor.
 */
class MatchProtocolTest {

    private val cacheta = MatchConfig(gameType = GameType.CACHETA, pointLimit = 5)
    private val buraco = MatchConfig(gameType = GameType.BURACO, pointLimit = 1500)
    private val tranca = MatchConfig(gameType = GameType.TRANCA, autoMeldTrancaRedThrees = true)

    private fun card(rank: Rank, suit: Suit) =
        Card(rank = rank, suit = suit, deckColor = DeckColor.RED)

    // ─── Pontuacao: Cacheta decresce, Buraco/Tranca cresce ────────────────

    @Test
    fun `cacheta comeca com o limite de vidas nos dois times`() {
        assertEquals(listOf(5, 5), initialTeamScores(cacheta))
    }

    @Test
    fun `buraco comeca zerado nos dois times`() {
        assertEquals(listOf(0, 0), initialTeamScores(buraco))
    }

    @Test
    fun `cacheta acaba quando um time zera as vidas`() {
        assertTrue(isMatchOver(listOf(3, 0), cacheta))
        assertTrue(isMatchOver(listOf(-1, 4), cacheta))
        assertFalse(isMatchOver(listOf(1, 2), cacheta))
    }

    @Test
    fun `buraco acaba quando um time atinge o limite de pontos`() {
        assertTrue(isMatchOver(listOf(1500, 300), buraco))
        assertTrue(isMatchOver(listOf(200, 1600), buraco))
        assertFalse(isMatchOver(listOf(1499, 1400), buraco))
    }

    @Test
    fun `aplicar rodada soma no vencedor e no perdedor`() {
        val updated = applyRoundToTeamScores(
            currentScores = listOf(100, 200),
            winnerTeam = 1,
            winnerRoundScore = 260,
            loserTeam = 0,
            loserRoundScore = -100,
            config = buraco
        )
        assertEquals(listOf(0, 460), updated)
    }

    @Test
    fun `aplicar rodada em placar vazio parte do inicial do modo`() {
        // Cacheta parte de pointLimit e perde vida: 5 - 1 = 4.
        val updated = applyRoundToTeamScores(
            currentScores = emptyList(),
            winnerTeam = 0,
            winnerRoundScore = 0,
            loserTeam = 1,
            loserRoundScore = -1,
            config = cacheta
        )
        assertEquals(listOf(5, 4), updated)
    }

    @Test
    fun `contagem de rodada soma cada time na propria posicao`() {
        val updated = applyCountRoundToTeamScores(
            currentScores = listOf(500, 700),
            roundScores = listOf(-30, 120),
            config = buraco
        )
        assertEquals(listOf(470, 820), updated)
    }

    // ─── Assentos e times ─────────────────────────────────────────────────

    @Test
    fun `time do assento alterna par e impar`() {
        assertEquals(0, teamForSeat(0))
        assertEquals(1, teamForSeat(1))
        assertEquals(0, teamForSeat(2))
        assertEquals(1, teamForSeat(3))
    }

    @Test
    fun `time adversario e sempre o outro`() {
        assertEquals(1, opposingTeam(0))
        assertEquals(0, opposingTeam(1))
    }

    @Test
    fun `proximo assento da a volta na mesa`() {
        val quatro = MatchConfig(maxPlayers = 4)
        assertEquals(1, nextSeatAfter(0, quatro))
        assertEquals(3, nextSeatAfter(2, quatro))
        assertEquals(0, nextSeatAfter(3, quatro))

        val dois = MatchConfig(maxPlayers = 2)
        assertEquals(1, nextSeatAfter(0, dois))
        assertEquals(0, nextSeatAfter(1, dois))
    }

    // ─── 3 vermelho da Tranca ─────────────────────────────────────────────

    @Test
    fun `tres vermelho so conta na tranca com a regra ligada`() {
        val treCopas = card(Rank.THREE, Suit.HEARTS)
        val treOuros = card(Rank.THREE, Suit.DIAMONDS)
        val trePaus = card(Rank.THREE, Suit.CLUBS)

        assertTrue(isTrancaRedThree(treCopas, tranca))
        assertTrue(isTrancaRedThree(treOuros, tranca))
        // Preto nunca conta.
        assertFalse(isTrancaRedThree(trePaus, tranca))
        // Fora da Tranca nao vale.
        assertFalse(isTrancaRedThree(treCopas, buraco))
        // Com a regra desligada nao vale.
        assertFalse(isTrancaRedThree(treCopas, tranca.copy(autoMeldTrancaRedThrees = false)))
    }

    // ─── Comparacao de cartas respeitando duplicatas ──────────────────────

    @Test
    fun `contem cartas respeita duplicata do mesmo id`() {
        val a = card(Rank.FIVE, Suit.HEARTS)
        val b = card(Rank.FIVE, Suit.HEARTS)
        // Duas copias pedidas, so uma disponivel -> nao contem.
        assertFalse(containsCards(listOf(a), listOf(a, b)))
        // Duas disponiveis -> contem.
        assertTrue(containsCards(listOf(a, b), listOf(a, b)))
    }

    @Test
    fun `subtrair cartas devolve o resto ou null quando falta`() {
        val quatro = card(Rank.FOUR, Suit.CLUBS)
        val cinco = card(Rank.FIVE, Suit.CLUBS)
        val seis = card(Rank.SIX, Suit.CLUBS)

        val resto = subtractCards(listOf(quatro, cinco, seis), listOf(quatro, cinco))
        assertEquals(listOf(seis.id), resto?.map { it.id })

        // Pedir carta que nao esta na origem devolve null.
        assertNull(subtractCards(listOf(quatro), listOf(cinco)))
    }

    // ─── Protocolo de rede ────────────────────────────────────────────────

    @Test
    fun `payload de descarte leva carta e assento`() {
        val payload = buildDiscardPayload(card(Rank.KING, Suit.SPADES), seat = 2)
        val (cardId, seat) = parseDiscardPayload(payload)

        assertEquals(card(Rank.KING, Suit.SPADES).id, cardId)
        assertEquals(2, seat)
    }

    @Test
    fun `payload de descarte antigo sem json continua sendo lido`() {
        // Compatibilidade: versao antiga mandava so o id cru, sem envelope.
        val (cardId, seat) = parseDiscardPayload("KING_SPADES_BLACK")
        assertEquals("KING_SPADES_BLACK", cardId)
        assertEquals(0, seat)
    }

    @Test
    fun `payload de baixada leva time derivado do assento`() {
        val payload = buildMeldPayload(
            cards = listOf(card(Rank.FOUR, Suit.CLUBS)),
            seat = 3,
            replaceIndex = 2
        )
        val json = JSONObject(payload)

        assertEquals(3, json.getInt("seat"))
        assertEquals(1, json.getInt("team")) // assento 3 -> time 1
        assertEquals(2, parseMeldReplaceIndex(payload))
    }

    @Test
    fun `payload de assento vai e volta`() {
        assertEquals(2, parseSeatPayload(buildSeatPayload(2)))
    }

    @Test
    fun `payload invalido devolve sentinela em vez de estourar`() {
        assertEquals(-1, parseSeatPayload("nao é json"))
        assertEquals(-1, parseMeldReplaceIndex("{quebrado"))
        assertEquals("", parseCardId("{quebrado"))
    }

    @Test
    fun `mortos restantes so leva assento quando alguem pegou`() {
        val comDono = JSONObject(buildMortosLeftPayload(mortosLeft = 1, pickedSeat = 2))
        assertEquals(1, comDono.getInt("mortosLeft"))
        assertEquals(2, comDono.getInt("seat"))
        assertEquals(0, comDono.getInt("team")) // assento 2 -> time 0

        val semDono = JSONObject(buildMortosLeftPayload(mortosLeft = 2, pickedSeat = -1))
        assertEquals(2, semDono.getInt("mortosLeft"))
        assertFalse(semDono.has("seat"))
    }

    @Test
    fun `placar de time so e aceito com os dois times`() {
        assertNull(parseTeamScores(null))
        assertNull(parseTeamScores(org.json.JSONArray().put(10)))
        assertEquals(listOf(10, 20), parseTeamScores(org.json.JSONArray().put(10).put(20)))
    }
}

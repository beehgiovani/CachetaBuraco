package com.brunogiovani.cachetaburaco.presentation.match

import com.brunogiovani.cachetaburaco.domain.models.Card
import com.brunogiovani.cachetaburaco.domain.models.GameType
import com.brunogiovani.cachetaburaco.domain.models.MatchConfig
import com.brunogiovani.cachetaburaco.domain.models.Rank
import com.brunogiovani.cachetaburaco.domain.models.Suit
import org.json.JSONArray
import org.json.JSONObject

/**
 * Serializacao do protocolo de rede e as contas de pontuacao da partida.
 *
 * Tudo aqui e funcao pura: entra parametro, sai resultado, sem tocar em
 * nenhum estado do MatchViewModel. Ficavam misturadas no meio da maquina de
 * estado (3.845 linhas) sem precisar -- separadas, dao pra testar sem montar
 * um ViewModel inteiro, que e exatamente o que a regra de pontuacao merece
 * (e o mesmo calculo que a migration 0048 replica no servidor).
 *
 * O que NAO veio junto, de proposito: os parsers que dependem do
 * `allCardsMap` (tabela de cartas da partida) e do `playerId` do
 * MatchViewModel. Trazer esses exigiria passar mais estado por parametro sem
 * ganho real de clareza.
 */

// ─── Protocolo: montagem de payload ───────────────────────────────────────

internal fun buildDiscardPayload(card: Card, seat: Int): String {
    return JSONObject()
        .put("v", 1)
        .put("card", card.id)
        .put("seat", seat)
        .toString()
}

internal fun buildMeldPayload(cards: List<Card>, seat: Int, replaceIndex: Int = -1): String {
    return JSONObject()
        .put("v", 1)
        .put("cards", cardsToJson(cards))
        .put("seat", seat)
        .put("team", teamForSeat(seat))
        .put("replaceIndex", replaceIndex)
        .toString()
}

internal fun buildSeatPayload(seat: Int): String {
    return JSONObject()
        .put("v", 1)
        .put("seat", seat)
        .toString()
}

internal fun buildMortosLeftPayload(mortosLeft: Int, pickedSeat: Int): String {
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

// ─── Protocolo: leitura de payload ────────────────────────────────────────

internal fun parseDiscardPayload(payload: String): Pair<String, Int> {
    val trimmed = payload.trim()
    if (!trimmed.startsWith("{")) return payload to 0

    val json = runCatching { JSONObject(trimmed) }.getOrNull()
        ?: return payload to 0
    return json.optString("card", "") to json.optInt("seat", 0)
}

internal fun parseMeldReplaceIndex(payload: String): Int {
    val trimmed = payload.trim()
    if (!trimmed.startsWith("{")) return -1
    return runCatching { JSONObject(trimmed).optInt("replaceIndex", -1) }.getOrDefault(-1)
}

internal fun parseSeatPayload(payload: String): Int {
    val trimmed = payload.trim()
    if (!trimmed.startsWith("{")) return -1
    return runCatching { JSONObject(trimmed).optInt("seat", -1) }.getOrDefault(-1)
}

internal fun parseCardId(payload: String): String {
    val trimmed = payload.trim()
    if (!trimmed.startsWith("{")) return trimmed
    return runCatching { JSONObject(trimmed).optString("card", "") }.getOrDefault("")
}

internal fun parseTeamScores(jsonArray: JSONArray?): List<Int>? {
    jsonArray ?: return null
    if (jsonArray.length() < 2) return null
    return List(jsonArray.length()) { index -> jsonArray.optInt(index) }
}

// ─── Cartas <-> JSON ──────────────────────────────────────────────────────

internal fun cardsToJson(cards: List<Card>): JSONArray {
    return JSONArray().apply {
        cards.forEach { card -> put(card.id) }
    }
}

internal fun handsToJson(hands: List<List<Card>>): JSONArray {
    return JSONArray().apply {
        hands.forEach { hand -> put(cardsToJson(hand)) }
    }
}

// ─── Comparacao de conjuntos de carta (por id, respeitando duplicatas) ────

internal fun containsCards(source: List<Card>, requested: List<Card>): Boolean {
    val remainingIds = source.map { it.id }.toMutableList()
    return requested.all { card ->
        val index = remainingIds.indexOf(card.id)
        if (index < 0) false else {
            remainingIds.removeAt(index)
            true
        }
    }
}

internal fun subtractCards(fullMeld: List<Card>, existingMeld: List<Card>): List<Card>? {
    val remaining = fullMeld.toMutableList()
    existingMeld.forEach { existing ->
        val index = remaining.indexOfFirst { it.id == existing.id }
        if (index < 0) return null
        remaining.removeAt(index)
    }
    return remaining
}

// ─── Assentos e times ─────────────────────────────────────────────────────

internal fun Int.floorMod(mod: Int): Int = ((this % mod) + mod) % mod

internal fun teamForSeat(seat: Int): Int = seat.floorMod(2)

internal fun opposingTeam(team: Int): Int = if (team == 0) 1 else 0

internal fun nextSeatAfter(seat: Int, config: MatchConfig): Int {
    val maxSeats = config.maxPlayers.coerceAtLeast(2)
    return (seat + 1).floorMod(maxSeats)
}

// ─── Regras de carta que dependem so da config ────────────────────────────

internal fun isTrancaRedThree(card: Card, config: MatchConfig): Boolean {
    return config.gameType == GameType.TRANCA &&
        config.autoMeldTrancaRedThrees &&
        card.rank == Rank.THREE &&
        (card.suit == Suit.HEARTS || card.suit == Suit.DIAMONDS)
}

// ─── Pontuacao ────────────────────────────────────────────────────────────
// Mesma conta que private.cbr_compute_round_summary faz no servidor
// (migration 0048). Se mudar aqui, tem que mudar la tambem.

internal fun initialTeamScores(config: MatchConfig): List<Int> {
    val initial = if (config.gameType == GameType.CACHETA) config.pointLimit else 0
    return listOf(initial, initial)
}

/**
 * Verifica se a partida terminou.
 * - Cacheta: uma equipe ficou com 0 ou menos vidas.
 * - Buraco/Tranca: uma equipe atingiu ou superou o limite de pontos configurado.
 */
internal fun isMatchOver(teamScores: List<Int>, config: MatchConfig): Boolean {
    return if (config.gameType == GameType.CACHETA) {
        // Na Cacheta os pontos começam em pointLimit e decrescem; acaba quando alguém chega em 0
        teamScores.any { it <= 0 }
    } else {
        // No Buraco/Tranca os pontos começam em 0 e crescem; acaba quando alguém atinge o limite
        teamScores.any { it >= config.pointLimit }
    }
}

internal fun applyRoundToTeamScores(
    currentScores: List<Int>,
    winnerTeam: Int,
    winnerRoundScore: Int,
    loserTeam: Int,
    loserRoundScore: Int,
    config: MatchConfig
): List<Int> {
    val scores = currentScores.ifEmpty { initialTeamScores(config) }.toMutableList()
    while (scores.size < 2) scores.add(if (config.gameType == GameType.CACHETA) config.pointLimit else 0)
    scores[winnerTeam] = scores[winnerTeam] + winnerRoundScore
    scores[loserTeam] = scores[loserTeam] + loserRoundScore
    return scores
}

internal fun applyCountRoundToTeamScores(
    currentScores: List<Int>,
    roundScores: List<Int>,
    config: MatchConfig
): List<Int> {
    val scores = currentScores.ifEmpty { initialTeamScores(config) }.toMutableList()
    while (scores.size < 2) scores.add(if (config.gameType == GameType.CACHETA) config.pointLimit else 0)
    roundScores.forEachIndexed { team, roundScore ->
        if (team < scores.size) scores[team] += roundScore
    }
    return scores
}

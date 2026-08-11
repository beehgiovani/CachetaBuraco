package com.brunogiovani.cachetaburaco.domain.repositories

import com.brunogiovani.cachetaburaco.domain.models.Championship
import com.brunogiovani.cachetaburaco.domain.models.ChampionshipCadence
import com.brunogiovani.cachetaburaco.domain.models.ChampionshipMatchSummary
import com.brunogiovani.cachetaburaco.domain.models.ChampionshipStandingEntry
import com.brunogiovani.cachetaburaco.domain.models.GameType
import com.brunogiovani.cachetaburaco.domain.models.PlayerLevel

interface ChampionshipRepository {
    /** So o admin do jogo consegue criar (checado no servidor). */
    suspend fun createChampionship(
        playerName: String,
        name: String,
        gameType: GameType,
        cadence: ChampionshipCadence,
        level: PlayerLevel?
    ): Championship

    /** Rejeitado no servidor se o campeonato exigir um nivel diferente do calculado pro jogador. */
    suspend fun joinChampionship(playerName: String, code: String): Championship

    suspend fun listMyChampionships(playerName: String): List<Championship>

    suspend fun listStandings(playerName: String, championshipId: String, limit: Int = 50): List<ChampionshipStandingEntry>

    suspend fun listMatches(playerName: String, championshipId: String, limit: Int = 50): List<ChampionshipMatchSummary>

    /** So o admin (host do campeonato) pode encerrar. */
    suspend fun finishChampionship(playerName: String, championshipId: String)

    /** Nivel calculado no servidor (private.cbr_player_level) pro jogador atual. */
    suspend fun getMyLevel(playerName: String): PlayerLevel
}

package com.brunogiovani.cachetaburaco.domain.repositories

import com.brunogiovani.cachetaburaco.domain.models.Championship
import com.brunogiovani.cachetaburaco.domain.models.ChampionshipMatchSummary
import com.brunogiovani.cachetaburaco.domain.models.ChampionshipStandingEntry
import com.brunogiovani.cachetaburaco.domain.models.GameType

interface ChampionshipRepository {
    suspend fun createChampionship(playerName: String, name: String, gameType: GameType): Championship

    suspend fun joinChampionship(playerName: String, code: String): Championship

    /** So o host da sala pode vincular, e so antes da partida comecar. */
    suspend fun linkRoomToChampionship(playerName: String, roomCode: String, championshipCode: String)

    suspend fun listMyChampionships(playerName: String): List<Championship>

    suspend fun listStandings(playerName: String, championshipId: String, limit: Int = 50): List<ChampionshipStandingEntry>

    suspend fun listMatches(playerName: String, championshipId: String, limit: Int = 50): List<ChampionshipMatchSummary>

    /** So o host do campeonato pode encerrar. */
    suspend fun finishChampionship(playerName: String, championshipId: String)
}

package com.brunogiovani.cachetaburaco.domain.models

enum class ChampionshipStatus { ACTIVE, FINISHED }

// Campeonato por pontos (Fase 6): inscricao por codigo, igual sala privada --
// nao existe lista publica de campeonatos pra descobrir, so quem recebe o
// codigo consegue entrar.
data class Championship(
    val id: String,
    val code: String,
    val name: String,
    val gameType: GameType,
    val status: ChampionshipStatus,
    val isHost: Boolean = false,
    val participantCount: Int = 0
)

data class ChampionshipStandingEntry(
    val position: Int,
    val playerId: String,
    val playerName: String,
    val avatarUrl: String?,
    val avatarPhotoUrl: String?,
    val totalWins: Int,
    val totalMatches: Int
)

data class ChampionshipMatchSummary(
    val matchResultId: String,
    val winnerTeam: Int,
    val winnerNickname: String?,
    val finishedAt: String?
)

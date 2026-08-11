package com.brunogiovani.cachetaburaco.domain.models

enum class ChampionshipStatus { ACTIVE, FINISHED }

enum class ChampionshipCadence { WEEKLY, MONTHLY, MANUAL }

// Calculado no servidor a partir de player_stats (private.cbr_player_level) --
// nunca autodeclarado, pra nivel de sala/campeonato significar alguma coisa.
enum class PlayerLevel { NOOB, MID, HARD, EXPERT }

// Campeonato por pontos (Fase 6, redesenhado): so o admin do jogo cria,
// inscricao por codigo (igual sala privada -- sem lista publica), e toda
// partida do inscrito conta automaticamente enquanto o campeonato esta
// dentro da janela de datas (cadence define o fim; MANUAL fica em aberto ate
// o admin encerrar).
data class Championship(
    val id: String,
    val code: String,
    val name: String,
    val gameType: GameType,
    val status: ChampionshipStatus,
    val cadence: ChampionshipCadence = ChampionshipCadence.MANUAL,
    val level: PlayerLevel? = null,
    val startsAt: String? = null,
    val endsAt: String? = null,
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

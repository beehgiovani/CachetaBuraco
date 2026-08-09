package com.brunogiovani.cachetaburaco.domain.models

enum class OnlineRankingPeriod {
    OVERALL,
    WEEKLY,
    MONTHLY
}

data class OnlineRankingEntry(
    val position: Int,
    val playerId: String,
    val playerName: String,
    val avatarUrl: String?,
    val totalWins: Int,
    val totalMatches: Int,
    val cachetaWins: Int,
    val buracoWins: Int,
    val trancaWins: Int,
    val bestStreak: Int,
    val currentStreak: Int,
    val xp: Int,
    val lastMatchAt: String?,
    val avatarPhotoUrl: String? = null
) {
    val winRatePercent: Int
        get() = if (totalMatches <= 0) 0 else ((totalWins * 100.0) / totalMatches).toInt()
}

data class OnlineRankingSnapshot(
    val localPlayerId: String,
    val entries: List<OnlineRankingEntry>,
    val period: OnlineRankingPeriod = OnlineRankingPeriod.OVERALL,
    // So preenchido pra WEEKLY/MONTHLY -- offset 0 e o periodo atual (em
    // andamento), negativo e temporada passada (fechada). periodStart/End
    // vem sempre do servidor (migration 0033), mesmo quando entries fica
    // vazio, pra UI conseguir mostrar o intervalo de datas de um periodo sem
    // nenhuma partida.
    val periodOffset: Int = 0,
    val periodStart: String? = null,
    val periodEnd: String? = null
) {
    val localPlayer: OnlineRankingEntry?
        get() = entries.firstOrNull { it.playerId == localPlayerId }

    val isClosedPeriod: Boolean
        get() = periodOffset < 0
}

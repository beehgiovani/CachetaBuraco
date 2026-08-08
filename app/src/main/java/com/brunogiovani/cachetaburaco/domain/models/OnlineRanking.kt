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
    val period: OnlineRankingPeriod = OnlineRankingPeriod.OVERALL
) {
    val localPlayer: OnlineRankingEntry?
        get() = entries.firstOrNull { it.playerId == localPlayerId }
}

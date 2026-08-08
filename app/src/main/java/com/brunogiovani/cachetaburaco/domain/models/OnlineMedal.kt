package com.brunogiovani.cachetaburaco.domain.models

enum class MedalTier { BRONZE, SILVER, GOLD }

data class MedalDefinition(
    val code: String,
    val tier: MedalTier,
    val title: String,
    val description: String
)

/** Espelha os codigos concedidos pelo trigger `player_stats_grant_medals` (migration 0027). */
object MedalCatalog {
    val all: List<MedalDefinition> = listOf(
        MedalDefinition("WINS_10", MedalTier.BRONZE, "Iniciante", "10 vitórias no total"),
        MedalDefinition("WINS_50", MedalTier.SILVER, "Veterano", "50 vitórias no total"),
        MedalDefinition("WINS_150", MedalTier.GOLD, "Lenda da mesa", "150 vitórias no total"),

        MedalDefinition("STREAK_3", MedalTier.BRONZE, "Em alta", "3 vitórias seguidas"),
        MedalDefinition("STREAK_5", MedalTier.SILVER, "Imparável", "5 vitórias seguidas"),
        MedalDefinition("STREAK_10", MedalTier.GOLD, "Invencível", "10 vitórias seguidas"),

        MedalDefinition("CACHETA_WINS_10", MedalTier.BRONZE, "Cachetaço", "10 vitórias na Cacheta"),
        MedalDefinition("CACHETA_WINS_25", MedalTier.SILVER, "Mestre da Cacheta", "25 vitórias na Cacheta"),
        MedalDefinition("CACHETA_WINS_50", MedalTier.GOLD, "Rei da Cacheta", "50 vitórias na Cacheta"),

        MedalDefinition("BURACO_WINS_10", MedalTier.BRONZE, "Furador", "10 vitórias no Buraco"),
        MedalDefinition("BURACO_WINS_25", MedalTier.SILVER, "Mestre do Buraco", "25 vitórias no Buraco"),
        MedalDefinition("BURACO_WINS_50", MedalTier.GOLD, "Rei do Buraco", "50 vitórias no Buraco"),

        MedalDefinition("TRANCA_WINS_10", MedalTier.BRONZE, "Trancafiado", "10 vitórias na Tranca"),
        MedalDefinition("TRANCA_WINS_25", MedalTier.SILVER, "Mestre da Tranca", "25 vitórias na Tranca"),
        MedalDefinition("TRANCA_WINS_50", MedalTier.GOLD, "Rei da Tranca", "50 vitórias na Tranca"),

        MedalDefinition("MATCHES_25", MedalTier.BRONZE, "Presença garantida", "25 partidas jogadas"),
        MedalDefinition("MATCHES_100", MedalTier.SILVER, "Frequentador assíduo", "100 partidas jogadas"),
        MedalDefinition("MATCHES_300", MedalTier.GOLD, "Peça da mesa", "300 partidas jogadas")
    )
}

data class EarnedMedal(val code: String, val earnedAt: String?)

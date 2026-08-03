package com.brunogiovani.cachetaburaco.domain.models

data class Player(
    val id: String,
    val name: String,
    val points: Int = 0,
    val isReady: Boolean = false
)

enum class GameType { CACHETA, BURACO, TRANCA }

enum class MatchMode { ONLINE, LOCAL_NETWORK }

enum class PointsMode { FREE, CHIPS }

enum class BotDifficulty { EASY, NORMAL, HARD }

/**
 * Configuração fechada da partida.
 *
 * O lobby monta este pacote e a mesa recebe tudo pronto, seja no local, contra
 * a máquina ou futuramente online.
 */
data class MatchConfig(
    val gameType: GameType = GameType.CACHETA,
    val maxPlayers: Int = 2,
    val allowWildcards: Boolean = true,         // Curingas habilitados
    val allowDrawFromDiscard: Boolean = true,    // Permitir compra do lixo
    val allowCharutos: Boolean = true,           // Permitir trincas/charutos quando a regra da sala habilitar
    val cachetaCardsPerPlayer: Int = 9,          // Cacheta costuma usar 9 cartas, mas algumas mesas usam variações
    val cachetaStartsWithDiscard: Boolean = false, // Vira fica separado; lixo começa zerado por padrão
    val requireCleanCanastraToWin: Boolean = true, // Buraco exige canastra limpa para bater
    val autoMeldTrancaRedThrees: Boolean = true, // Tranca baixa 3 vermelho automaticamente
    val penalizeBlackThreesInHand: Boolean = true, // Tranca: 3 preto preso na mão pesa 100 pontos
    val autoSortHand: Boolean = true,           // Ordenar mão automaticamente
    val uniformCardPoints: Boolean = false,       // Tranca/Buraco: todas as cartas valem 10pts
    val botDifficulty: BotDifficulty = BotDifficulty.NORMAL,
    val pointsMode: PointsMode = PointsMode.FREE,
    val pointLimit: Int = 1500                   // Limite de pontos para a partida acabar
) {
    val isTeamMode: Boolean get() = maxPlayers == 4
    val cardsPerPlayer: Int get() = if (gameType == GameType.CACHETA) cachetaCardsPerPlayer else 11

    fun serialize(): String {
        return listOf(
            gameType,
            maxPlayers,
            allowWildcards,
            allowDrawFromDiscard,
            allowCharutos,
            cachetaCardsPerPlayer,
            cachetaStartsWithDiscard,
            requireCleanCanastraToWin,
            autoMeldTrancaRedThrees,
            penalizeBlackThreesInHand,
            autoSortHand,
            uniformCardPoints,
            botDifficulty,
            pointsMode,
            pointLimit
        ).joinToString(",")
    }

    companion object {
        fun deserialize(serialized: String): MatchConfig {
            val parts = serialized.split(",")
            val defaults = MatchConfig()
            val hasBlackThreePenalty = parts.size >= 15
            val hasBotDifficulty = parts.size >= 14
            val hasExpandedRules = parts.size >= 13
            val hasCharutosField = parts.size >= 8 && !hasExpandedRules

            return MatchConfig(
                gameType = parts.getOrNull(0)
                    ?.let { runCatching { GameType.valueOf(it) }.getOrNull() }
                    ?: defaults.gameType,
                maxPlayers = parts.getOrNull(1)
                    ?.toIntOrNull()
                    ?.coerceIn(2, 4)
                    ?: defaults.maxPlayers,
                allowWildcards = parts.getOrNull(2)?.toBooleanStrictOrNull()
                    ?: defaults.allowWildcards,
                allowDrawFromDiscard = parts.getOrNull(3)?.toBooleanStrictOrNull()
                    ?: defaults.allowDrawFromDiscard,
                allowCharutos = if (hasCharutosField) {
                    parts.getOrNull(4)?.toBooleanStrictOrNull() ?: defaults.allowCharutos
                } else if (hasExpandedRules) {
                    parts.getOrNull(4)?.toBooleanStrictOrNull() ?: defaults.allowCharutos
                } else {
                    defaults.allowCharutos
                },
                cachetaCardsPerPlayer = if (hasExpandedRules) {
                    parts.getOrNull(5)?.toIntOrNull()?.coerceIn(7, 10) ?: defaults.cachetaCardsPerPlayer
                } else {
                    defaults.cachetaCardsPerPlayer
                },
                cachetaStartsWithDiscard = if (hasExpandedRules) {
                    parts.getOrNull(6)?.toBooleanStrictOrNull() ?: defaults.cachetaStartsWithDiscard
                } else {
                    defaults.cachetaStartsWithDiscard
                },
                requireCleanCanastraToWin = if (hasExpandedRules) {
                    parts.getOrNull(7)?.toBooleanStrictOrNull() ?: defaults.requireCleanCanastraToWin
                } else {
                    defaults.requireCleanCanastraToWin
                },
                autoMeldTrancaRedThrees = if (hasExpandedRules) {
                    parts.getOrNull(8)?.toBooleanStrictOrNull() ?: defaults.autoMeldTrancaRedThrees
                } else {
                    defaults.autoMeldTrancaRedThrees
                },
                penalizeBlackThreesInHand = if (hasBlackThreePenalty) {
                    parts.getOrNull(9)?.toBooleanStrictOrNull() ?: defaults.penalizeBlackThreesInHand
                } else {
                    defaults.penalizeBlackThreesInHand
                },
                autoSortHand = parts.getOrNull(
                    when {
                        hasBlackThreePenalty -> 10
                        hasExpandedRules -> 9
                        hasCharutosField -> 5
                        else -> 4
                    }
                )?.toBooleanStrictOrNull()
                    ?: defaults.autoSortHand,
                uniformCardPoints = if (hasExpandedRules) {
                    parts.getOrNull(if (hasBlackThreePenalty) 11 else 10)?.toBooleanStrictOrNull() ?: defaults.uniformCardPoints
                } else {
                    defaults.uniformCardPoints
                },
                botDifficulty = if (hasBotDifficulty) {
                    parts.getOrNull(if (hasBlackThreePenalty) 12 else 11)
                        ?.let { runCatching { BotDifficulty.valueOf(it) }.getOrNull() }
                        ?: defaults.botDifficulty
                } else {
                    defaults.botDifficulty
                },
                pointsMode = parts.getOrNull(
                    when {
                        hasBlackThreePenalty -> 13
                        hasBotDifficulty -> 12
                        hasExpandedRules -> 11
                        hasCharutosField -> 6
                        else -> 5
                    }
                )
                    ?.let { runCatching { PointsMode.valueOf(it) }.getOrNull() }
                    ?: defaults.pointsMode,
                pointLimit = parts.getOrNull(
                    when {
                        hasBlackThreePenalty -> 14
                        hasBotDifficulty -> 13
                        hasExpandedRules -> 12
                        hasCharutosField -> 7
                        else -> 6
                    }
                )
                    ?.toIntOrNull()
                    ?.coerceIn(1, 100_000)
                    ?: defaults.pointLimit
            )
        }
    }
}

data class Match(
    val id: String,
    val config: MatchConfig,
    val mode: MatchMode,
    val players: List<Player>
)

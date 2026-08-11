package com.brunogiovani.cachetaburaco.data.online

import com.brunogiovani.cachetaburaco.domain.models.Championship
import com.brunogiovani.cachetaburaco.domain.models.ChampionshipCadence
import com.brunogiovani.cachetaburaco.domain.models.ChampionshipMatchSummary
import com.brunogiovani.cachetaburaco.domain.models.ChampionshipStandingEntry
import com.brunogiovani.cachetaburaco.domain.models.ChampionshipStatus
import com.brunogiovani.cachetaburaco.domain.models.GameType
import com.brunogiovani.cachetaburaco.domain.models.PlayerLevel
import com.brunogiovani.cachetaburaco.domain.repositories.ChampionshipRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.storage.storage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val AVATAR_PHOTO_BUCKET = "avatar-photos"

/** Campeonatos por pontos (Fase 6): inscricao por codigo, mesmo padrao de sala privada. */
class SupabaseChampionshipRepository(
    private val client: SupabaseClient = SupabaseClientProvider.client
) : ChampionshipRepository {
    private val identity = SupabaseIdentity(client)

    override suspend fun createChampionship(
        playerName: String,
        name: String,
        gameType: GameType,
        cadence: ChampionshipCadence,
        level: PlayerLevel?
    ): Championship {
        identity.ensure(playerName)
        return client.postgrest.rpc(
            function = "create_championship",
            parameters = buildJsonObject {
                put("p_name", name)
                put("p_game_type", gameType.name)
                put("p_cadence", cadence.name)
                put("p_level", level?.name)
            }
        ).decodeSingle<ChampionshipRow>().toDomain()
    }

    override suspend fun joinChampionship(playerName: String, code: String): Championship {
        identity.ensure(playerName)
        return client.postgrest.rpc(
            function = "join_championship",
            parameters = buildJsonObject { put("p_code", code) }
        ).decodeSingle<ChampionshipRow>().toDomain()
    }

    override suspend fun getMyLevel(playerName: String): PlayerLevel {
        identity.ensure(playerName)
        val level = client.postgrest.rpc(function = "get_my_level").decodeAs<String>()
        return runCatching { PlayerLevel.valueOf(level) }.getOrDefault(PlayerLevel.NOOB)
    }

    override suspend fun listMyChampionships(playerName: String): List<Championship> {
        identity.ensure(playerName)
        return client.postgrest.rpc(function = "list_my_championships")
            .decodeList<MyChampionshipRow>()
            .map { it.toDomain() }
    }

    override suspend fun listStandings(
        playerName: String,
        championshipId: String,
        limit: Int
    ): List<ChampionshipStandingEntry> {
        identity.ensure(playerName)
        val safeLimit = limit.coerceIn(1, 100)
        return client.postgrest.rpc(
            function = "list_championship_standings",
            parameters = buildJsonObject {
                put("p_championship_id", championshipId)
                put("p_limit", safeLimit)
            }
        ).decodeList<ChampionshipStandingRow>().map { it.toDomain(::avatarPhotoUrl) }
    }

    override suspend fun listMatches(
        playerName: String,
        championshipId: String,
        limit: Int
    ): List<ChampionshipMatchSummary> {
        identity.ensure(playerName)
        val safeLimit = limit.coerceIn(1, 100)
        return client.postgrest.rpc(
            function = "list_championship_matches",
            parameters = buildJsonObject {
                put("p_championship_id", championshipId)
                put("p_limit", safeLimit)
            }
        ).decodeList<ChampionshipMatchRow>().map { it.toDomain() }
    }

    override suspend fun finishChampionship(playerName: String, championshipId: String) {
        identity.ensure(playerName)
        client.postgrest.rpc(
            function = "finish_championship",
            parameters = buildJsonObject { put("p_championship_id", championshipId) }
        )
    }

    private fun avatarPhotoUrl(path: String): String =
        client.storage.from(AVATAR_PHOTO_BUCKET).publicUrl(path)
}

private fun String.toGameType(): GameType =
    runCatching { GameType.valueOf(this) }.getOrDefault(GameType.CACHETA)

private fun String.toChampionshipStatus(): ChampionshipStatus =
    runCatching { ChampionshipStatus.valueOf(this) }.getOrDefault(ChampionshipStatus.ACTIVE)

private fun String.toChampionshipCadence(): ChampionshipCadence =
    runCatching { ChampionshipCadence.valueOf(this) }.getOrDefault(ChampionshipCadence.MANUAL)

private fun String?.toPlayerLevel(): PlayerLevel? =
    this?.let { runCatching { PlayerLevel.valueOf(it) }.getOrNull() }

@Serializable
private data class ChampionshipRow(
    @SerialName("championship_id") val championshipId: String,
    val code: String,
    val name: String,
    @SerialName("game_type") val gameType: String,
    val status: String,
    val cadence: String = "MANUAL",
    val level: String? = null,
    @SerialName("starts_at") val startsAt: String? = null,
    @SerialName("ends_at") val endsAt: String? = null
) {
    fun toDomain() = Championship(
        id = championshipId,
        code = code,
        name = name,
        gameType = gameType.toGameType(),
        status = status.toChampionshipStatus(),
        cadence = cadence.toChampionshipCadence(),
        level = level.toPlayerLevel(),
        startsAt = startsAt,
        endsAt = endsAt
    )
}

@Serializable
private data class MyChampionshipRow(
    @SerialName("championship_id") val championshipId: String,
    val code: String,
    val name: String,
    @SerialName("game_type") val gameType: String,
    val status: String,
    @SerialName("is_host") val isHost: Boolean,
    @SerialName("participant_count") val participantCount: Int,
    val cadence: String = "MANUAL",
    val level: String? = null,
    @SerialName("starts_at") val startsAt: String? = null,
    @SerialName("ends_at") val endsAt: String? = null
) {
    fun toDomain() = Championship(
        id = championshipId,
        code = code,
        name = name,
        gameType = gameType.toGameType(),
        status = status.toChampionshipStatus(),
        cadence = cadence.toChampionshipCadence(),
        level = level.toPlayerLevel(),
        startsAt = startsAt,
        endsAt = endsAt,
        isHost = isHost,
        participantCount = participantCount
    )
}

@Serializable
private data class ChampionshipStandingRow(
    @SerialName("rank_position") val rankPosition: Long,
    @SerialName("profile_id") val profileId: String,
    val nickname: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("avatar_photo_path") val avatarPhotoPath: String? = null,
    @SerialName("total_wins") val totalWins: Int,
    @SerialName("total_matches") val totalMatches: Int
) {
    fun toDomain(buildPhotoUrl: (String) -> String) = ChampionshipStandingEntry(
        position = rankPosition.coerceIn(1, Int.MAX_VALUE.toLong()).toInt(),
        playerId = profileId,
        playerName = nickname,
        avatarUrl = avatarUrl,
        avatarPhotoUrl = avatarPhotoPath?.let(buildPhotoUrl),
        totalWins = totalWins,
        totalMatches = totalMatches
    )
}

@Serializable
private data class ChampionshipMatchRow(
    @SerialName("match_result_id") val matchResultId: String,
    @SerialName("winner_team") val winnerTeam: Int,
    @SerialName("winner_nickname") val winnerNickname: String? = null,
    @SerialName("finished_at") val finishedAt: String? = null
) {
    fun toDomain() = ChampionshipMatchSummary(
        matchResultId = matchResultId,
        winnerTeam = winnerTeam,
        winnerNickname = winnerNickname,
        finishedAt = finishedAt
    )
}

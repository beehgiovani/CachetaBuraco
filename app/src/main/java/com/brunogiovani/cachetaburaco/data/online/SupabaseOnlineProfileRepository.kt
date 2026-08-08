package com.brunogiovani.cachetaburaco.data.online

import com.brunogiovani.cachetaburaco.domain.models.EarnedMedal
import com.brunogiovani.cachetaburaco.domain.models.OnlineAvatar
import com.brunogiovani.cachetaburaco.domain.models.OnlineProfile
import com.brunogiovani.cachetaburaco.domain.repositories.OnlineProfileRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import io.github.jan.supabase.storage.storage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private const val AVATAR_PHOTO_BUCKET = "avatar-photos"

class SupabaseOnlineProfileRepository(
    private val client: SupabaseClient = SupabaseClientProvider.client
) : OnlineProfileRepository {
    private val identity = SupabaseIdentity(client)

    override suspend fun loadProfile(playerName: String): OnlineProfile {
        val playerId = identity.ensure(playerName)
        return fetchProfile(playerId)
    }

    override suspend fun updateAvatar(playerName: String, avatar: OnlineAvatar): OnlineProfile {
        val playerId = identity.ensure(playerName)
        client.postgrest.rpc(
            function = "set_profile_avatar",
            parameters = buildJsonObject { put("p_avatar_id", avatar.storageId) }
        )
        return fetchProfile(playerId)
    }

    override suspend fun loadMedals(playerName: String): List<EarnedMedal> {
        val playerId = identity.ensure(playerName)
        return client.from("player_medals").select {
            filter { filter("profile_id", FilterOperator.EQ, playerId) }
        }.decodeList<PlayerMedalRow>().map { EarnedMedal(code = it.medalCode, earnedAt = it.earnedAt) }
    }

    override suspend fun uploadAvatarPhoto(playerName: String, jpegBytes: ByteArray): OnlineProfile {
        val playerId = identity.ensure(playerName)
        val path = "$playerId/photo.jpg"
        client.storage.from(AVATAR_PHOTO_BUCKET).upload(path, jpegBytes) { upsert = true }
        client.postgrest.rpc(
            function = "set_profile_avatar_photo",
            parameters = buildJsonObject { put("p_path", path) }
        )
        return fetchProfile(playerId)
    }

    override suspend fun clearAvatarPhoto(playerName: String): OnlineProfile {
        val playerId = identity.ensure(playerName)
        client.postgrest.rpc(function = "clear_profile_avatar_photo")
        return fetchProfile(playerId)
    }

    private suspend fun fetchProfile(playerId: String): OnlineProfile {
        val row = client.from("profiles").select {
            filter { filter("id", FilterOperator.EQ, playerId) }
            limit(1)
        }.decodeSingle<PublicOnlineProfileRow>()
        return row.toEnrichedDomain()
    }

    override suspend fun reportAvatarPhoto(targetProfileId: String) {
        client.postgrest.rpc(
            function = "report_avatar_photo",
            parameters = buildJsonObject { put("p_profile_id", targetProfileId) }
        )
    }

    override suspend fun deleteAccount(playerName: String) {
        identity.ensure(playerName)
        client.postgrest.rpc(function = "delete_own_account")
    }

    private fun OnlineProfile.withAccountInfo(photoPath: String?): OnlineProfile {
        val user = client.auth.currentUserOrNull()
        return copy(
            isAnonymous = user?.isAnonymous ?: true,
            email = user?.email,
            accountCreatedAt = user?.createdAt?.toString(),
            avatarPhotoUrl = photoPath?.let { client.storage.from(AVATAR_PHOTO_BUCKET).publicUrl(it) }
        )
    }

    private fun PublicOnlineProfileRow.toEnrichedDomain(): OnlineProfile =
        toDomain().withAccountInfo(avatarPhotoPath)
}

@Serializable
internal data class PublicOnlineProfileRow(
    @SerialName("profile_id") val profileId: String? = null,
    val id: String? = null,
    val nickname: String,
    @SerialName("avatar_url") val avatarUrl: String? = null,
    @SerialName("avatar_photo_path") val avatarPhotoPath: String? = null
)

@Serializable
internal data class PlayerMedalRow(
    @SerialName("medal_code") val medalCode: String,
    @SerialName("earned_at") val earnedAt: String? = null
)

internal fun PublicOnlineProfileRow.toDomain(): OnlineProfile {
    return OnlineProfile(
        playerId = requireNotNull(profileId ?: id) { "Perfil online sem identificador." },
        playerName = nickname,
        avatar = OnlineAvatar.fromStorageId(avatarUrl)
    )
}

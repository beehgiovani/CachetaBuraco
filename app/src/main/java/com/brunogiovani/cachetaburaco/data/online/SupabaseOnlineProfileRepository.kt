package com.brunogiovani.cachetaburaco.data.online

import com.brunogiovani.cachetaburaco.domain.models.OnlineAvatar
import com.brunogiovani.cachetaburaco.domain.models.OnlineProfile
import com.brunogiovani.cachetaburaco.domain.repositories.OnlineProfileRepository
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class SupabaseOnlineProfileRepository(
    private val client: SupabaseClient = SupabaseClientProvider.client
) : OnlineProfileRepository {
    private val identity = SupabaseIdentity(client)

    override suspend fun loadProfile(playerName: String): OnlineProfile {
        val playerId = identity.ensure(playerName)
        return client.from("profiles").select {
            filter { filter("id", FilterOperator.EQ, playerId) }
            limit(1)
        }.decodeSingle<PublicOnlineProfileRow>().toDomain()
    }

    override suspend fun updateAvatar(playerName: String, avatar: OnlineAvatar): OnlineProfile {
        identity.ensure(playerName)
        return client.postgrest.rpc(
            function = "set_profile_avatar",
            parameters = buildJsonObject { put("p_avatar_id", avatar.storageId) }
        ).decodeSingle<PublicOnlineProfileRow>().toDomain()
    }
}

@Serializable
internal data class PublicOnlineProfileRow(
    @SerialName("profile_id") val profileId: String? = null,
    val id: String? = null,
    val nickname: String,
    @SerialName("avatar_url") val avatarUrl: String? = null
)

internal fun PublicOnlineProfileRow.toDomain(): OnlineProfile {
    return OnlineProfile(
        playerId = requireNotNull(profileId ?: id) { "Perfil online sem identificador." },
        playerName = nickname,
        avatar = OnlineAvatar.fromStorageId(avatarUrl)
    )
}

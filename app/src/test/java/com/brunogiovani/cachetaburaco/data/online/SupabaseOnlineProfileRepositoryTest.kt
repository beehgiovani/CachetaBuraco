package com.brunogiovani.cachetaburaco.data.online

import com.brunogiovani.cachetaburaco.domain.models.OnlineAvatar
import org.junit.Assert.assertEquals
import org.junit.Test

class SupabaseOnlineProfileRepositoryTest {
    @Test
    fun `direct profile row maps id nickname and avatar`() {
        val profile = PublicOnlineProfileRow(
            id = "player-direct",
            nickname = "Jogador",
            avatarUrl = "builtin:ruby"
        ).toDomain()

        assertEquals("player-direct", profile.playerId)
        assertEquals("Jogador", profile.playerName)
        assertEquals(OnlineAvatar.RUBY, profile.avatar)
    }

    @Test
    fun `rpc profile row maps profile id and safe avatar fallback`() {
        val profile = PublicOnlineProfileRow(
            profileId = "player-rpc",
            nickname = "Mesa",
            avatarUrl = "unknown"
        ).toDomain()

        assertEquals("player-rpc", profile.playerId)
        assertEquals(OnlineAvatar.EMERALD, profile.avatar)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `profile response without id is rejected`() {
        PublicOnlineProfileRow(nickname = "Sem id").toDomain()
    }
}

package com.brunogiovani.cachetaburaco.domain.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineProfileTest {
    @Test
    fun `all stored avatar ids resolve to their enum value`() {
        OnlineAvatar.entries.forEach { avatar ->
            assertEquals(avatar, OnlineAvatar.fromStorageId(avatar.storageId))
        }
    }

    @Test
    fun `missing or legacy avatar falls back to emerald`() {
        assertEquals(OnlineAvatar.EMERALD, OnlineAvatar.fromStorageId(null))
        assertEquals(OnlineAvatar.EMERALD, OnlineAvatar.fromStorageId("https://legacy.invalid/avatar.png"))
    }

    @Test
    fun `avatar storage ids are unique`() {
        val ids = OnlineAvatar.entries.map(OnlineAvatar::storageId)

        assertTrue(ids.size == ids.distinct().size)
    }
}

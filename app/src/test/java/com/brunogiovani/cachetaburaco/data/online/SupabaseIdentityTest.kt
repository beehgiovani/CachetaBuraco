package com.brunogiovani.cachetaburaco.data.online

import org.junit.Assert.assertEquals
import org.junit.Test

class SupabaseIdentityTest {
    @Test
    fun `nickname removes extra spaces and respects database limit`() {
        assertEquals("Bruno Giovani", normalizeOnlineNickname("  Bruno    Giovani  "))
        assertEquals(24, normalizeOnlineNickname("a".repeat(40)).length)
    }

    @Test
    fun `nickname falls back when normalized value is too short`() {
        assertEquals("Jogador", normalizeOnlineNickname(" a "))
        assertEquals("Jogador", normalizeOnlineNickname("   "))
    }
}

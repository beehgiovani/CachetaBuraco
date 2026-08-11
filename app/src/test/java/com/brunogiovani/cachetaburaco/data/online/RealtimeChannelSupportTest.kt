package com.brunogiovani.cachetaburaco.data.online

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RealtimeChannelSupportTest {
    @Test
    fun `successive subscriptions receive different topics`() {
        val first = uniqueRealtimeTopic("waiting-match-rooms")
        val second = uniqueRealtimeTopic("waiting-match-rooms")

        assertTrue(first.startsWith("waiting-match-rooms-"))
        assertTrue(second.startsWith("waiting-match-rooms-"))
        assertNotEquals(first, second)
    }
}

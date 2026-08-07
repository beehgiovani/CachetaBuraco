package com.brunogiovani.cachetaburaco.data.online

import com.brunogiovani.cachetaburaco.domain.repositories.NetworkMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OnlineEventCodecTest {

    @Test
    fun `encode and decode keep the same network message envelope`() {
        val message = NetworkMessage(
            senderId = "host",
            type = "PUBLIC_STATE",
            payload = """{"deckSize":40,"discardSize":3}""",
            messageId = "msg-123",
            roundId = "38d0d17f-b889-4dc6-a7f4-9204592f9a84"
        )

        val decoded = OnlineEventCodec.decode(OnlineEventCodec.encode(message))

        assertEquals(message, decoded)
    }

    @Test
    fun `decode rejects malformed or incomplete events`() {
        assertNull(OnlineEventCodec.decode("{"))
        assertNull(OnlineEventCodec.decode("""{"senderId":"host","payload":"{}","messageId":"1"}"""))
        assertNull(OnlineEventCodec.decode("""{"senderId":"host","type":"PING","payload":"{}"}"""))
    }

    @Test
    fun `decode keeps compatibility with event created before round identity`() {
        val decoded = OnlineEventCodec.decode(
            """{"senderId":"host","type":"PUBLIC_STATE","payload":"{}","messageId":"legacy-1"}"""
        )

        assertEquals(null, decoded?.roundId)
    }
}

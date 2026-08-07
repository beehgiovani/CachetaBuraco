package com.brunogiovani.cachetaburaco.data.online

import com.brunogiovani.cachetaburaco.domain.models.GameType
import com.brunogiovani.cachetaburaco.domain.models.MatchConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class OnlineRoomConfigJsonTest {

    @Test
    fun `online room exposes rule flags without losing serialized config`() {
        val config = MatchConfig(
            gameType = GameType.TRANCA,
            allowWildcards = false,
            allowDrawFromDiscard = false,
            allowCharutos = false,
            requireCleanCanastraToWin = false
        )

        val json = config.toOnlineRoomConfigJson()

        assertEquals(config.serialize(), json["serialized"]?.toString()?.trim('"'))
        assertEquals("false", json["allowWildcards"].toString())
        assertEquals("false", json["allowDrawFromDiscard"].toString())
        assertEquals("false", json["allowCharutos"].toString())
        assertEquals("false", json["requireCleanCanastraToWin"].toString())
        assertEquals("11", json["cardsPerPlayer"].toString())
    }
}

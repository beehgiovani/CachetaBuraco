package com.brunogiovani.cachetaburaco.data.online

import com.brunogiovani.cachetaburaco.domain.models.OnlineRankingPeriod
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SupabaseOnlineRankingRepositoryTest {
    @Test
    fun `overall ranking uses global rpc and caps limit`() {
        val request = buildRankingRpcRequest(OnlineRankingPeriod.OVERALL, 500)

        assertEquals("list_global_ranking", request.function)
        assertEquals(100, request.parameters.getValue("p_limit").jsonPrimitive.int)
        assertFalse(request.parameters.containsKey("p_period"))
    }

    @Test
    fun `weekly ranking uses period rpc and minimum limit`() {
        val request = buildRankingRpcRequest(OnlineRankingPeriod.WEEKLY, 0)

        assertEquals("list_period_ranking", request.function)
        assertEquals("WEEKLY", request.parameters.getValue("p_period").jsonPrimitive.content)
        assertEquals(1, request.parameters.getValue("p_limit").jsonPrimitive.int)
    }

    @Test
    fun `monthly ranking sends monthly period`() {
        val request = buildRankingRpcRequest(OnlineRankingPeriod.MONTHLY, 25)

        assertEquals("list_period_ranking", request.function)
        assertEquals("MONTHLY", request.parameters.getValue("p_period").jsonPrimitive.content)
        assertEquals(25, request.parameters.getValue("p_limit").jsonPrimitive.int)
    }

    @Test
    fun `weekly ranking defaults offset to zero`() {
        val request = buildRankingRpcRequest(OnlineRankingPeriod.WEEKLY, 50)

        assertEquals(0, request.parameters.getValue("p_offset").jsonPrimitive.int)
    }

    @Test
    fun `weekly ranking sends negative offset for past seasons`() {
        val request = buildRankingRpcRequest(OnlineRankingPeriod.WEEKLY, 50, offset = -2)

        assertEquals(-2, request.parameters.getValue("p_offset").jsonPrimitive.int)
    }

    @Test
    fun `positive offset is clamped to zero`() {
        val request = buildRankingRpcRequest(OnlineRankingPeriod.MONTHLY, 50, offset = 3)

        assertEquals(0, request.parameters.getValue("p_offset").jsonPrimitive.int)
    }

    @Test
    fun `overall ranking never sends offset`() {
        val request = buildRankingRpcRequest(OnlineRankingPeriod.OVERALL, 50, offset = -1)

        assertFalse(request.parameters.containsKey("p_offset"))
    }
}

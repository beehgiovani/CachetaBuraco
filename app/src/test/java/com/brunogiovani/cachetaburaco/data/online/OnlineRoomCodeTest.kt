package com.brunogiovani.cachetaburaco.data.online

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OnlineRoomCodeTest {

    @Test
    fun `room code uses normalized player prefix and five random characters`() {
        val indexes = ArrayDeque(listOf(0, 1, 2, 3, 4))

        assertEquals("BRUABCDE", OnlineRoomCode.create("Bruno") { indexes.removeFirst() })
    }

    @Test
    fun `room code falls back when name has no usable characters`() {
        assertEquals("CBRAAAAA", OnlineRoomCode.create("!!!") { 0 })
    }

    @Test
    fun `room code removes accents and keeps database compatible length`() {
        val code = OnlineRoomCode.create("Jo\u00e3o") { 31 }

        assertTrue(code.startsWith("JOA"))
        assertEquals(8, code.length)
        assertTrue(code.all { it in 'A'..'Z' || it in '0'..'9' })
    }
}

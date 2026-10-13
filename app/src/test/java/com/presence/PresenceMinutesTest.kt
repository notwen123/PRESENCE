package com.presence

import org.junit.Assert.assertEquals
import org.junit.Test

class PresenceMinutesTest {
    @Test
    fun onlyTheGapBetweenTwoFaceDownSamplesCounts() {
        val min = 60_000L
        assertEquals(5 * min, PresenceMinutes.credit(0, lastDown = true, now = 5 * min, down = true))
        assertEquals(0, PresenceMinutes.credit(0, lastDown = false, now = 5 * min, down = true))  // just put down
        assertEquals(0, PresenceMinutes.credit(0, lastDown = true, now = 5 * min, down = false))  // picked up since
        assertEquals(0, PresenceMinutes.credit(0, lastDown = true, now = 60 * min, down = true))  // gap too long to trust
    }
}

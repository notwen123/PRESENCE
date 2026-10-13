package com.presence

import com.presence.evidence.MotionProbe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Synthetic 100 Hz accelerometer traces: face-down on a table, knocks, injected taps, a phone in hand. */
class MotionProbeTest {
    private val rnd = Random(7)
    private val start = 1_000_000_000L

    /** [up] = +1 screen up, -1 face-down. A jolt of [jolt] m/s^2 lasts 3 samples at [joltAt] seconds. */
    private fun MotionProbe.run(seconds: Int, joltAt: Double?, jolt: Float, up: Int = -1, handNoise: Float = 0f,
                                tapAtJolt: Boolean = false) {
        for (k in 0 until seconds * 100) {
            val ts = start + k * 10_000_000L
            val jolting = joltAt != null && k in (joltAt * 100).toInt() until (joltAt * 100).toInt() + 3
            val noise = (rnd.nextFloat() - 0.5f) * 0.01f + (rnd.nextFloat() - 0.5f) * handNoise
            feed(ts, 0f, 0f, up * (9.81f + noise + if (jolting) jolt else 0f))
            if (tapAtJolt && joltAt != null && k == (joltAt * 100).toInt()) onTap(ts)
        }
    }

    private fun probe() = MotionProbe(null, null, measuring = true)

    @Test
    fun knockOnTheBackIsDetectedAndTheFaceDownPhoneCountsAsStill() {
        var knocks = 0
        val p = probe().apply { onKnock = { knocks++ } }
        p.run(30, joltAt = 12.0, jolt = 1.2f)
        val (still, touch) = p.takeWindow()
        assertEquals(1, knocks)
        assertTrue(p.faceDown)
        assertTrue("still=$still", still >= 80)
        assertTrue("touch=$touch", touch >= 1000)
    }

    @Test
    fun ourOwnVibrationIsNotAKnock() {
        var knocks = 0
        val p = probe().apply { onKnock = { knocks++ }; quiet(start + 11_900_000_000L, 1000) }
        p.run(30, joltAt = 12.0, jolt = 1.2f)
        assertEquals(0, knocks)
    }

    @Test
    fun injectedTapMovesNothing() {
        var knocks = 0
        val p = probe().apply { onKnock = { knocks++ } }
        p.run(30, joltAt = 12.0, jolt = 0f, tapAtJolt = true)
        val (_, touch) = p.takeWindow()
        assertEquals(0, knocks)
        assertTrue("touch=$touch", touch < 40)
    }

    @Test
    fun faceUpOrInHandIsNotFaceDownAndStill() {
        val up = probe().apply { run(30, joltAt = null, jolt = 0f, up = 1) }
        assertFalse(up.faceDown)
        assertTrue(up.takeWindow().first < 10)
        val hand = probe().apply { run(30, joltAt = null, jolt = 0f, handNoise = 0.6f) }
        assertTrue(hand.takeWindow().first < 60)
    }

    @Test
    fun calibrationUsesHalfTheMedianKnockButNeverGetsCloseToNoise() {
        assertEquals(0.9f, MotionProbe.personalKnock(listOf(1600, 1800, 2400)), 0.001f)
        assertEquals(MotionProbe.MIN_KNOCK, MotionProbe.personalKnock(listOf(150, 200, 250)), 0.001f)
        assertEquals(MotionProbe.KNOCK, MotionProbe.personalKnock(emptyList()), 0.001f)
    }

    @Test
    fun aSoftCalibratedKnockIsDetected() {
        var knocks = 0
        val p = MotionProbe(null, null, measuring = true, knock = 0.25f).apply { onKnock = { knocks++ } }
        p.run(30, joltAt = 12.0, jolt = 0.3f)
        assertEquals(1, knocks)
    }

    @Test
    fun noSensorMeansNotMeasured() {
        assertEquals(-1 to -1, MotionProbe(null).takeWindow())
    }
}

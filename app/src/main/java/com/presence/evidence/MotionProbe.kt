package com.presence.evidence

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Physical evidence for one checkpoint window, from the accelerometer. Only two numbers leave the
 * phone per window; raw samples are never stored or sent.
 *
 * - stillPct: share of 1-second buckets in which the phone lay still AND face-down (gravity on -z),
 *   with the seconds around a knock or tap excluded.
 * - touchMotion: the largest jolt (mm/s^2) of a check-in. A knock on the back of the phone, or a finger
 *   on the glass, moves it; input injected by a script or an AI agent moves nothing.
 *
 * It also detects knocks itself ([onKnock]), so a face-down phone is answered without being picked up,
 * and reports whether the phone is face-down right now ([faceDown]).
 *
 * ponytail: fixed thresholds; calibrate KNOCK and STILL_SD on real devices (Seeker, mid-range Android).
 */
class MotionProbe(
    private val sensors: SensorManager?,
    private val accel: Sensor? = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
    private val measuring: Boolean = accel != null,
    /** m/s^2 jolt that counts as a knock: the user's calibrated value, or the default. */
    private val knock: Float = KNOCK,
) : SensorEventListener {
    constructor(context: Context, knock: Float = KNOCK) :
        this(context.getSystemService(SensorManager::class.java), knock = knock)

    /** Called on the sensor thread (main looper) with the knock's jolt in mm/s^2. */
    var onKnock: ((Int) -> Unit)? = null

    @Volatile var faceDown = false
        private set

    // Ring buffer of the last ~4 s of |a| (m/s^2) with timestamps (ns, elapsedRealtimeNanos base).
    private val t = LongArray(BUF)
    private val m = FloatArray(BUF)
    private var n = 0L

    private var bucketStart = 0L
    private var bucketN = 0
    private var bucketSum = 0.0
    private var bucketSq = 0.0
    private var bucketZ = 0.0
    private var stillBuckets = 0
    private var countedBuckets = 0
    private var lastTapNs = Long.MIN_VALUE / 2
    private val pendingTaps = ArrayDeque<Long>()
    private var touchMax = -1

    private var baseline = GRAVITY
    private var zAvg = 0f
    private var quietUntil = 0L
    private var lastKnock = Long.MIN_VALUE / 2

    fun start() {
        sensors?.registerListener(this, accel ?: return, SAMPLE_US)
    }

    fun stop() = sensors?.unregisterListener(this)

    /** The phone's own buzz shakes the accelerometer; don't take it for a knock. */
    fun quiet(ms: Long) = quiet(SystemClock.elapsedRealtimeNanos(), ms)

    @Synchronized
    internal fun quiet(now: Long, ms: Long) {
        quietUntil = now + ms * 1_000_000
    }

    fun onTap() = onTap(SystemClock.elapsedRealtimeNanos())

    @Synchronized
    internal fun onTap(now: Long) {
        lastTapNs = now
        pendingTaps.addLast(now)
    }

    /** Close the window: returns (stillPct, touchMotion) and starts a fresh one. -1 = not measured. */
    @Synchronized
    fun takeWindow(): Pair<Int, Int> {
        settleTaps(force = true)
        val still = if (!measuring || countedBuckets == 0) -1 else stillBuckets * 100 / countedBuckets
        val touch = if (!measuring) -1 else touchMax
        stillBuckets = 0; countedBuckets = 0; touchMax = -1
        return still to touch
    }

    override fun onSensorChanged(e: SensorEvent) = feed(e.timestamp, e.values[0], e.values[1], e.values[2])

    @Synchronized
    internal fun feed(ts: Long, x: Float, y: Float, z: Float) {
        val mag = sqrt(x * x + y * y + z * z)
        val i = (n % BUF).toInt()
        t[i] = ts; m[i] = mag; n++
        zAvg += (z - zAvg) * 0.05f
        faceDown = zAvg < FACE_DOWN_Z

        // Knock: a sharp jolt away from the slow baseline, outside our own vibration, at most every 350 ms.
        val dev = abs(mag - baseline)
        baseline += (mag - baseline) * 0.02f
        if (measuring && dev > knock && ts > quietUntil && ts - lastKnock > KNOCK_GAP_NS) {
            lastKnock = ts
            lastTapNs = ts
            touchMax = maxOf(touchMax, (dev * 1000).toInt())
            onKnock?.invoke((dev * 1000).toInt())
        }

        if (bucketStart == 0L) bucketStart = ts
        if (ts - bucketStart >= BUCKET_NS) {
            if (bucketN >= 5 && lastTapNs < bucketStart - TAP_EXCLUDE_NS) {
                val mean = bucketSum / bucketN
                val sd = sqrt((bucketSq / bucketN - mean * mean).coerceAtLeast(0.0))
                countedBuckets++
                if (sd < STILL_SD && bucketZ / bucketN < FACE_DOWN_Z) stillBuckets++
            }
            bucketStart = ts; bucketN = 0; bucketSum = 0.0; bucketSq = 0.0; bucketZ = 0.0
        }
        bucketN++; bucketSum += mag; bucketSq += mag.toDouble() * mag; bucketZ += z
        settleTaps(force = false)
    }

    /** Score each screen tap once the buffer holds TAP_AFTER_NS of samples past it. */
    private fun settleTaps(force: Boolean) {
        val latest = if (n == 0L) return else t[((n - 1) % BUF).toInt()]
        while (pendingTaps.isNotEmpty() && (force || latest - pendingTaps.first() >= TAP_AFTER_NS)) {
            val tap = pendingTaps.removeFirst()
            val before = mutableListOf<Float>()
            var peak = 0f
            for (k in 0 until minOf(n, BUF.toLong()).toInt()) {
                val dt = t[k] - tap
                if (dt in -BASELINE_NS until -TAP_BEFORE_NS) before += m[k]
            }
            val base = before.sorted().let { if (it.isEmpty()) return@let null else it[it.size / 2] } ?: continue
            for (k in 0 until minOf(n, BUF.toLong()).toInt()) {
                val dt = t[k] - tap
                if (dt in -TAP_BEFORE_NS..TAP_AFTER_NS) peak = maxOf(peak, abs(m[k] - base))
            }
            touchMax = maxOf(touchMax, (peak * 1000).toInt())
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    companion object {
        const val SAMPLE_US = 10_000              // ~100 Hz, below the 200 Hz cap that needs a permission
        const val BUF = 400                       // ~4 s at 100 Hz
        const val GRAVITY = 9.81f
        const val BUCKET_NS = 1_000_000_000L
        const val TAP_EXCLUDE_NS = 2_000_000_000L // a check-in and the 2 s around it don't count against stillness
        const val TAP_BEFORE_NS = 150_000_000L
        const val TAP_AFTER_NS = 300_000_000L
        const val BASELINE_NS = 1_500_000_000L
        const val STILL_SD = 0.08                 // m/s^2; a phone lying on a table is well under this
        const val FACE_DOWN_Z = -7f               // m/s^2 on the z axis: screen facing the table
        const val KNOCK = 0.35f                   // m/s^2 jolt; real-phone noise measured at <= 0.09
        const val CALIBRATION_KNOCK = 0.15f       // listen low while learning the user's knock
        const val MIN_KNOCK = 0.2f                // never trust a threshold this close to sensor noise
        const val KNOCK_GAP_NS = 350_000_000L

        /** A user's knock threshold from calibration knocks (mm/s^2): half their median knock, never near noise. */
        fun personalKnock(jolts: List<Int>): Float {
            if (jolts.isEmpty()) return KNOCK
            val median = jolts.sorted()[jolts.size / 2] / 1000f
            return maxOf(MIN_KNOCK, median * 0.5f)
        }
    }
}

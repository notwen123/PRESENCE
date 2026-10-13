package com.presence

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.util.Calendar
import kotlin.math.sqrt

/**
 * Presence Minutes: face-down time through the day, without opening the app. Every few minutes the
 * phone wakes, listens to the accelerometer for ~1.5 s, and if the phone lies face-down and still, the
 * time since the previous such sample counts. Low assurance (no knocks), so it
 * fills the daily ring only: no money, nothing on-chain. Opt-in.
 *
 * ponytail: inexact alarms (Doze stretches them), so long still stretches are undercounted, never
 * overcounted; a gap over MAX_GAP_MS counts nothing.
 */
object PresenceMinutes {
    private const val PREFS = "presence"
    private const val ON = "minutes_on"
    private const val DAY = "minutes_day"
    private const val TOTAL = "minutes_total_ms"
    private const val LAST_AT = "minutes_last_at"
    private const val LAST_DOWN = "minutes_last_down"
    const val GOAL_MINUTES = 180
    private val INTERVAL_MS = if (BuildConfig.DEBUG) 60_000L else 5 * 60_000L
    private const val MAX_GAP_MS = 20 * 60_000L
    private const val MIN_READINGS = 5

    fun enabled(ctx: Context) = prefs(ctx).getBoolean(ON, false)

    fun setEnabled(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putBoolean(ON, on).remove(LAST_AT).apply()
        schedule(ctx)
    }

    /** Minutes counted today (local time). */
    fun today(ctx: Context): Int {
        val p = prefs(ctx)
        return if (p.getInt(DAY, -1) == dayOfYear()) (p.getLong(TOTAL, 0) / 60_000).toInt() else 0
    }

    /** Credit a face-down stretch measured elsewhere (e.g. the Urge Catcher's 60-second breath). */
    fun add(ctx: Context, ms: Long) {
        val p = prefs(ctx)
        val day = dayOfYear()
        val base = if (p.getInt(DAY, -1) == day) p.getLong(TOTAL, 0) else 0
        p.edit().putInt(DAY, day).putLong(TOTAL, base + ms).apply()
    }

    internal fun schedule(ctx: Context) {
        val alarms = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(ctx, 1, Intent(ctx, Sampler::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        alarms.cancel(pi)
        if (!enabled(ctx)) return
        alarms.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + INTERVAL_MS, pi)
    }

    /** Credit the gap between two consecutive face-down samples. Pure so it can be tested. */
    internal fun credit(lastAt: Long, lastDown: Boolean, now: Long, down: Boolean): Long =
        if (lastDown && down && now - lastAt in 1..MAX_GAP_MS) now - lastAt else 0

    private fun record(ctx: Context, down: Boolean) {
        val p = prefs(ctx)
        val now = System.currentTimeMillis()
        val day = dayOfYear()
        val base = if (p.getInt(DAY, -1) == day) p.getLong(TOTAL, 0) else 0
        val add = credit(p.getLong(LAST_AT, 0), p.getBoolean(LAST_DOWN, false), now, down)
        p.edit().putInt(DAY, day).putLong(TOTAL, base + add).putLong(LAST_AT, now).putBoolean(LAST_DOWN, down).apply()
    }

    private fun dayOfYear() = Calendar.getInstance().let { it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR) }
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    class Sampler : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val app = ctx.applicationContext
            val pending = goAsync()
            val sensors = app.getSystemService(SensorManager::class.java)
            // Screen off: ordinary sensors only report while the CPU is held awake. Prefer a wake-up sensor.
            val accel = sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER, true)
                ?: sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
            val power = app.getSystemService(PowerManager::class.java)
            val screenOff = power?.isInteractive == false // logged only
            val wake = power?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "facedown:minutes")?.apply { acquire(5_000) }
            if (accel == null) { schedule(app); wake?.release(); pending.finish(); return }
            val z = mutableListOf<Float>(); val mag = mutableListOf<Float>()
            val listener = object : SensorEventListener {
                override fun onSensorChanged(e: SensorEvent) {
                    z += e.values[2]; mag += sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                }
                override fun onAccuracyChanged(s: Sensor?, a: Int) = Unit
            }
            sensors.registerListener(listener, accel, SensorManager.SENSOR_DELAY_GAME)
            val main = Handler(Looper.getMainLooper())
            main.postDelayed({ sensors.flush(listener) }, 1000) // batched readings: ask for them now
            main.postDelayed({
                sensors.unregisterListener(listener)
                val mean = mag.average()
                val sd = sqrt(mag.sumOf { (it - mean) * (it - mean) } / mag.size.coerceAtLeast(1))
                val down = z.size >= MIN_READINGS && z.average() < -7 && sd < 0.08 // face-down: nobody's looking at it
                Log.i("PRESENCE", "minutes sample screenOff=$screenOff n=${z.size} z=${z.average()} sd=$sd down=$down")
                if (z.size >= MIN_READINGS) record(app, down) // no readings = unknown: keep the previous sample
                schedule(app)
                if (wake?.isHeld == true) wake.release()
                pending.finish()
            }, 1500)
        }
    }

    /** Alarms don't survive a reboot; re-arm them. */
    class Boot : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED) { schedule(ctx); FocusAppointment.schedule(ctx) }
        }
    }
}

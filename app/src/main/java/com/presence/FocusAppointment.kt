package com.presence

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import java.util.Calendar

/**
 * A daily focus appointment: a reminder at a time the user picked. Appointments more than doubled
 * follow-through compared with financial commitment devices (Derksen et al., arXiv 2110.06876).
 * It's only a reminder; the server neither knows nor enforces it.
 *
 * ponytail: inexact alarm (no exact-alarm permission); re-armed after a reboot by PresenceMinutes.Boot.
 */
object FocusAppointment {
    private const val PREFS = "presence"
    private const val KEY = "appointment_minutes" // minutes after midnight, local time
    private const val CHANNEL = "appointments"

    fun get(ctx: Context): Int? = prefs(ctx).getInt(KEY, -1).takeIf { it >= 0 }

    fun set(ctx: Context, minutes: Int?) {
        prefs(ctx).edit().apply { if (minutes == null) remove(KEY) else putInt(KEY, minutes) }.apply()
        schedule(ctx)
    }

    fun label(minutes: Int): String {
        val c = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, minutes / 60); set(Calendar.MINUTE, minutes % 60) }
        return java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(c.time)
    }

    internal fun schedule(ctx: Context) {
        val alarms = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ctx, Reminder::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        alarms.cancel(pi)
        val minutes = get(ctx) ?: return
        val next = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, minutes / 60); set(Calendar.MINUTE, minutes % 60)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        alarms.setWindow(AlarmManager.RTC_WAKEUP, next.timeInMillis, 60_000, pi)
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    class Reminder : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Focus appointments", NotificationManager.IMPORTANCE_DEFAULT))
            }
            val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
            nm.notify(1, NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("Time to go facedown")
                .setContentText("Your focus appointment is now. Phone down, Pip's waiting.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
            schedule(ctx) // tomorrow, same time
        }
    }
}

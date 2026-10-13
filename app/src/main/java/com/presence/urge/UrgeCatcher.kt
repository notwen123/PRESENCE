package com.presence.urge

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent

/**
 * Urge Catcher: when an app the user picked comes to the front, show a pause first (one sec style,
 * see InterceptActivity). Reads only the package name of the window that opened; never its content.
 * Enabled by the user in Android's Accessibility settings, and off by default.
 */
class UrgeCatcherService : AccessibilityService() {
    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = e.packageName?.toString() ?: return
        if (pkg !in Urges.watched(this) || Urges.allowed(pkg)) return
        // The app is still launching and would cover the pause: send it home, then show the pause.
        performGlobalAction(GLOBAL_ACTION_HOME)
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, InterceptActivity::class.java)
                .putExtra(InterceptActivity.EXTRA_PACKAGE, pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }, 300)
    }

    override fun onInterrupt() = Unit
}

/** The user's watched apps and short "open anyway" passes. */
object Urges {
    /** Apps people most often name when they lose time; only the installed ones are offered. */
    val CANDIDATES = linkedMapOf(
        "com.instagram.android" to "Instagram",
        "com.google.android.youtube" to "YouTube",
        "com.twitter.android" to "X",
        "com.zhiliaoapp.musically" to "TikTok",
        "com.snapchat.android" to "Snapchat",
        "com.facebook.katana" to "Facebook",
        "com.reddit.frontpage" to "Reddit",
        "in.mohalla.sharechat" to "ShareChat",
        "com.android.chrome" to "Chrome",
    )
    private const val PREFS = "presence"
    private const val KEY = "urge_watched"
    private const val PASS_MS = 5 * 60_000L
    private val passes = mutableMapOf<String, Long>()

    fun installed(ctx: Context) = CANDIDATES.filterKeys { ctx.packageManager.getLaunchIntentForPackage(it) != null }

    fun watched(ctx: Context): Set<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet()) ?: emptySet()

    fun setWatched(ctx: Context, pkg: String, on: Boolean) {
        val now = watched(ctx).toMutableSet().apply { if (on) add(pkg) else remove(pkg) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY, now).apply()
    }

    /** "Open anyway" lets the app through for five minutes, so the pause doesn't loop. */
    fun allow(pkg: String, ms: Long = PASS_MS) { passes[pkg] = SystemClock.elapsedRealtime() + ms }
    fun allowed(pkg: String) = (passes[pkg] ?: 0) > SystemClock.elapsedRealtime()

    fun enabled(ctx: Context): Boolean {
        val me = ComponentName(ctx, UrgeCatcherService::class.java)
        return Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.split(':')?.any { ComponentName.unflattenFromString(it) == me } == true
    }
}

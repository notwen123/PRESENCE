package com.presence.urge

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.presence.MainActivity
import com.presence.PresenceMinutes
import com.presence.evidence.MotionProbe
import com.presence.ui.components.Gap
import com.presence.ui.components.Page
import com.presence.ui.components.Pip
import com.presence.ui.components.PipMood
import com.presence.ui.components.PrimaryButton
import com.presence.ui.components.SecondaryButton
import com.presence.ui.components.TopBar
import com.presence.ui.theme.ClockInTheme
import com.presence.ui.theme.SemanticColors
import kotlinx.coroutines.delay

/**
 * The pause before a watched app: turn the urge into a short facedown session, or open the app after
 * a 10-second wait. A short pause before opening an app reduces how often people open it
 * (Grüning et al., PNAS 2023, the "one sec" study).
 */
class InterceptActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra(EXTRA_PACKAGE) ?: return finish()
        val name = Urges.CANDIDATES[pkg] ?: "this app"
        setContent {
            ClockInTheme {
                var wait by remember { mutableIntStateOf(10) }
                LaunchedEffect(Unit) { while (wait > 0) { delay(1000); wait-- } }
                // "Earn your scroll": 60 s face-down (counted by the same detector as sessions) unlocks the app for 10 min.
                var earning by remember { mutableStateOf(false) }
                var earned by remember { mutableIntStateOf(0) }
                if (earning) LaunchedEffect(Unit) {
                    val probe = MotionProbe(this@InterceptActivity).also { it.start() }
                    try {
                        while (earned < EARN_SECONDS) { delay(1000); if (probe.faceDown) earned++ }
                    } finally { probe.stop() }
                    PresenceMinutes.add(this@InterceptActivity, EARN_SECONDS * 1000L)
                    Urges.allow(pkg, EARN_PASS_MS)
                    packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
                    finish()
                }
                Page {
                    TopBar()
                    Spacer(Modifier.weight(1f))
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Pip(PipMood.Thinking, 160.dp, entrance = true, live = true)
                        Gap(24)
                        Text("About to open $name?", style = MaterialTheme.typography.headlineLarge,
                            color = SemanticColors.PrimaryText, textAlign = TextAlign.Center)
                        Gap(8)
                        Text("Put it down for 10 minutes instead. Finish and it counts, on Monad.",
                            style = MaterialTheme.typography.bodyLarge, color = SemanticColors.SecondaryText,
                            textAlign = TextAlign.Center)
                    }
                    Spacer(Modifier.weight(1f))
                    PrimaryButton("Go facedown 10 min") {
                        startActivity(Intent(this@InterceptActivity, MainActivity::class.java)
                            .putExtra(MainActivity.EXTRA_MISSION, URGE_MISSION)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
                        finish()
                    }
                    Gap(8)
                    SecondaryButton(if (earning) "Face-down: $earned / $EARN_SECONDS s (turn the phone over)"
                                    else "Earn your scroll: 60 s face-down, then 10 min of $name") { earning = true }
                    Gap(8)
                    SecondaryButton(if (wait > 0) "Open $name in $wait s" else "Open $name anyway") {
                        if (wait > 0) return@SecondaryButton
                        Urges.allow(pkg)
                        packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
                        finish()
                    }
                    Gap(16)
                }
            }
        }
    }

    companion object {
        const val EXTRA_PACKAGE = "package"
        const val URGE_MISSION = "urge-break"
        const val EARN_SECONDS = 60
        const val EARN_PASS_MS = 10 * 60_000L
    }
}

package com.presence.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.presence.session.Screen
import com.presence.ui.components.Gap
import com.presence.ui.components.Page
import com.presence.ui.components.Pip
import com.presence.ui.components.PipMood
import com.presence.ui.components.PrimaryButton
import com.presence.ui.components.ProgressRing
import com.presence.ui.components.StatusLabel
import com.presence.ui.components.TopBar
import com.presence.ui.components.minutesLabel
import com.presence.ui.theme.BrandColors
import com.presence.ui.theme.LocalReducedMotion
import com.presence.ui.theme.Motion
import com.presence.ui.theme.SemanticColors

/**
 * Face-down: the screen goes black at minimum brightness, so the phone is dark on the table while the
 * session (and the foreground evidence) keeps running. A buzz asks for a check-in; a knock on the back
 * answers it. Picked up, the phone shows the timer and an "I'm here" button instead.
 */
@Composable
fun ActiveScreen(a: Screen.Active, facedownNow: Int, onPulse: () -> Unit, onBuzz: () -> Unit) {
    val view = LocalView.current
    val context = LocalContext.current
    DisposableEffect(Unit) {
        view.keepScreenOn = true // the app must stay in front; face-down it is black and dimmed
        onDispose { view.keepScreenOn = false }
    }
    val dark = a.faceDown && !a.leftForeground
    DisposableEffect(dark) {
        val window = context.findActivity()?.window
        window?.attributes = window?.attributes?.apply {
            screenBrightness = if (dark) 0.01f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        onDispose {
            window?.attributes = window?.attributes?.apply {
                screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            }
        }
    }
    // One buzz per check-in: that's how a face-down user knows to knock. No other haptics.
    LaunchedEffect(a.promptVisible) {
        if (a.promptVisible) {
            onBuzz()
            context.getSystemService(Vibrator::class.java)?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 180, 120, 180), -1))
        }
    }
    if (dark) Box(Modifier.fillMaxSize().background(Color.Black).semantics {
        contentDescription = "Facedown. The session is running."
    }) else Visible(a, facedownNow, onPulse)
}

@Composable
private fun Visible(a: Screen.Active, facedownNow: Int, onPulse: () -> Unit) = Page {
    TopBar {
        if (a.leftForeground) StatusLabel("Session interrupted") else StatusLabel("Session active", accent = true)
    }
    Spacer(Modifier.weight(1f))
    Timer(a)
    Gap(24)
    Text("Check-in ${(a.checkpointsDone + 1).coerceAtMost(a.checkpointsTotal)} of ${a.checkpointsTotal}",
        style = MaterialTheme.typography.labelMedium, color = SemanticColors.TertiaryText,
        modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    Gap(8)
    Text(
        when {
            a.leftForeground -> "You left the app, so this session can't be verified."
            a.confirmedThisWindow -> "Checked in. Turn the phone face-down again."
            else -> "Turn the phone face-down. The screen goes dark; knock on the back when it buzzes."
        },
        style = MaterialTheme.typography.bodyLarge,
        color = if (a.leftForeground) SemanticColors.PrimaryText else SemanticColors.SecondaryText,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
    )
    if (facedownNow > 1) {
        Gap(12)
        Text("You and ${facedownNow - 1} ${if (facedownNow == 2) "other" else "others"} are facedown right now",
            style = MaterialTheme.typography.labelMedium, color = BrandColors.Green,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
    }
    Spacer(Modifier.weight(1f))
    CheckIn(a.promptVisible, onPulse)
    Gap(24)
}

@Composable
private fun Timer(a: Screen.Active) {
    val remaining = ((a.durationMs - a.elapsedMs).coerceAtLeast(0) + 999) / 1000
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        ProgressRing((a.elapsedMs.toFloat() / a.durationMs).coerceIn(0f, 1f), Modifier.size(264.dp),
            color = if (a.leftForeground) SemanticColors.Disabled else BrandColors.Green)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (remaining >= 3600) "%d:%02d:%02d".format(remaining / 3600, remaining / 60 % 60, remaining % 60)
                else "%02d:%02d".format(remaining / 60, remaining % 60),
                style = MaterialTheme.typography.displayLarge, color = SemanticColors.PrimaryText)
            Text("${minutesLabel((a.durationMs / 1000).toInt())} goal",
                style = MaterialTheme.typography.bodyMedium, color = SemanticColors.SecondaryText)
        }
    }
}

/** Fixed-height slot so the layout never jumps when a check-in appears. Pip is alive but slow and quiet. */
@Composable
private fun CheckIn(visible: Boolean, onConfirm: () -> Unit) {
    val reduced = LocalReducedMotion.current
    Box(Modifier.fillMaxWidth().height(144.dp), contentAlignment = Alignment.Center) {
        AnimatedContent(visible, label = "checkin", transitionSpec = {
            val d = if (reduced) 0 else Motion.control
            fadeIn(tween(d)) togetherWith fadeOut(tween(d))
        }) { show ->
            if (show) Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Still here? Knock on the back, or tap", style = MaterialTheme.typography.titleMedium,
                    color = SemanticColors.PrimaryText)
                Gap(12)
                PrimaryButton("I'm here", onClick = onConfirm)
            } else Pip(PipMood.Still, 136.dp, entrance = true, live = true) // hops back in after each check-in
        }
    }
}

@Composable
fun CountdownScreen(seconds: Int) = Page {
    TopBar()
    Spacer(Modifier.weight(1f))
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Pip(PipMood.Still, 176.dp, entrance = true, live = true)
        Gap(24)
        Text("Turn your phone face-down.", style = MaterialTheme.typography.headlineLarge,
            color = SemanticColors.PrimaryText, textAlign = TextAlign.Center)
        Gap(8)
        Text("Lay it on a table. The screen goes dark. When it buzzes, knock on the back.",
            style = MaterialTheme.typography.bodyLarge, color = SemanticColors.SecondaryText, textAlign = TextAlign.Center)
        Gap(32)
        Text("Starting in $seconds", style = MaterialTheme.typography.labelMedium, color = SemanticColors.TertiaryText,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    Spacer(Modifier.weight(1f))
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Learn the user's knock on their own phone and table: three knocks, face-down. */
@Composable
fun CalibrateScreen(c: Screen.Calibrate, onDone: () -> Unit) = Page {
    TopBar(onBack = onDone)
    Spacer(Modifier.weight(1f))
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Pip(if (c.threshold != null) PipMood.Proud else PipMood.Still, 160.dp, live = true)
        Gap(24)
        Text(if (c.threshold != null) "Got your knock." else "Teach FACEDOWN your knock",
            style = MaterialTheme.typography.headlineLarge, color = SemanticColors.PrimaryText, textAlign = TextAlign.Center)
        Gap(8)
        Text(
            when {
                c.threshold != null -> "Your knocks average about %.1f m/s². Check-ins now count a knock above %.2f m/s² on this phone."
                    .format(c.jolts.sorted()[c.jolts.size / 2] / 1000f, c.threshold)
                !c.faceDown -> "Lay the phone face-down where you usually work, then knock on the back 3 times. It buzzes for each knock it feels."
                else -> "Knock on the back. ${3 - c.jolts.size} to go."
            },
            style = MaterialTheme.typography.bodyLarge, color = SemanticColors.SecondaryText, textAlign = TextAlign.Center,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
        Gap(24)
        Text("${c.jolts.size} of 3", style = MaterialTheme.typography.labelMedium, color = SemanticColors.TertiaryText)
    }
    Spacer(Modifier.weight(1f))
    if (c.threshold != null) PrimaryButton("Done", onClick = onDone)
    Gap(16)
}

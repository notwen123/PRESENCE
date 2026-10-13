package com.presence.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.presence.R
import com.presence.ui.theme.BrandColors
import com.presence.ui.theme.LocalReducedMotion
import kotlin.math.PI
import kotlin.math.sin

/** Pip, the FACEDOWN bird. One pose per moment; art lives in res/drawable-nodpi (cut from public/macouts.png). */
enum class PipMood(@DrawableRes val art: Int, val description: String) {
    Ready(R.drawable.mascot_ready, "Pip waving hello"),
    Still(R.drawable.mascot_still, "Pip focusing with a notepad"),
    Thinking(R.drawable.mascot_thinking, "Pip looking up, checking"),
    Proud(R.drawable.mascot_proud, "Pip celebrating"),
    Retry(R.drawable.mascot_encourage, "Pip encouraging you to try again"),
    Resting(R.drawable.mascot_resting, "Pip resting"),
}

/**
 * [entrance] plays one soft hop-in when the pose appears. [live] keeps Pip quietly alive: slow breathing,
 * a gentle sway, a writing nod in the focus pose, a floor shadow and a few drifting leaves. Every motion
 * is slow and low-contrast so it never pulls attention, and all of it is off under reduced motion.
 * Animated values are read only in the draw phase, so a long session doesn't recompose every frame.
 */
@Composable
fun Pip(mood: PipMood, size: Dp = 72.dp, modifier: Modifier = Modifier, entrance: Boolean = false, live: Boolean = false) {
    val reduced = LocalReducedMotion.current
    val hop = remember { Animatable(if (entrance && !reduced) 0.86f else 1f) }
    if (entrance && !reduced) LaunchedEffect(Unit) {
        hop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    }
    val idle = if (live && !reduced) idleMotion(mood) else null

    Box(modifier.size(size)) {
        if (idle != null) Canvas(Modifier.matchParentSize()) { shadow(idle.breathe.value) }
        Image(
            painterResource(mood.art), mood.description,
            Modifier.matchParentSize().graphicsLayer {
                val b = idle?.breathe?.value ?: 0f
                transformOrigin = TransformOrigin(0.5f, 0.92f) // pivot at the feet
                scaleX = hop.value * (1f - 0.012f * b)
                scaleY = hop.value * (1f + 0.028f * b)
                rotationZ = (idle?.sway?.value ?: 0f) * 2.2f + (idle?.nod?.value ?: 0f)
                translationY = -(1 - hop.value) * this.size.height * 0.6f
            },
        )
        if (idle != null) Canvas(Modifier.matchParentSize()) { leaves(idle.drift.value) }
    }
}

private class Idle(val breathe: State<Float>, val sway: State<Float>, val nod: State<Float>, val drift: State<Float>)

private val zero = mutableFloatStateOf(0f)

@Composable
private fun idleMotion(mood: PipMood): Idle {
    val t = rememberInfiniteTransition(label = "pip")
    val breathe = t.animateFloat(0f, 1f, infiniteRepeatable(tween(3200, easing = FastOutSlowInEasing), RepeatMode.Reverse), "breathe")
    val sway = t.animateFloat(-1f, 1f, infiniteRepeatable(tween(4800, easing = FastOutSlowInEasing), RepeatMode.Reverse), "sway")
    // The focus pose holds a notepad: a small nod every few seconds reads as writing.
    val nod = if (mood == PipMood.Still) t.animateFloat(0f, 0f, infiniteRepeatable(keyframes {
        durationMillis = 2600
        0f at 0; 0f at 1700; -3.5f at 1850; 0.8f at 2000; -2.5f at 2150; 0f at 2350
    }), "nod") else zero
    val drift = t.animateFloat(0f, 1f, infiniteRepeatable(tween(9000, easing = LinearEasing)), "drift")
    return Idle(breathe, sway, nod, drift)
}

/** A soft contact shadow that tightens as Pip breathes in. */
private fun DrawScope.shadow(b: Float) {
    val w = size.width * (0.5f - 0.03f * b)
    drawOval(BrandColors.Ink.copy(alpha = 0.06f), Offset((size.width - w) / 2, size.height * 0.9f), Size(w, size.height * 0.07f))
}

/** Three leaves rising and fading around Pip, each on its own phase of one 9 s loop. */
private fun DrawScope.leaves(drift: Float) {
    val starts = listOf(Triple(0.12f, 0.00f, 1f), Triple(0.86f, 0.37f, -1f), Triple(0.24f, 0.71f, -1f))
    for ((x0, phase, dir) in starts) {
        val p = (drift + phase) % 1f
        val alpha = sin(PI * p).toFloat() * 0.55f
        val x = size.width * (x0 + 0.06f * sin(2 * PI * p).toFloat() * dir)
        val y = size.height * (0.72f - 0.55f * p)
        val leaf = size.minDimension * 0.07f
        translate(x, y) {
            rotate(dir * (25f + 140f * p), Offset.Zero) {
                val path = Path().apply {
                    moveTo(0f, -leaf)
                    quadraticBezierTo(leaf * 0.75f, 0f, 0f, leaf)
                    quadraticBezierTo(-leaf * 0.75f, 0f, 0f, -leaf)
                }
                drawPath(path, BrandColors.Green.copy(alpha = alpha))
            }
        }
    }
}

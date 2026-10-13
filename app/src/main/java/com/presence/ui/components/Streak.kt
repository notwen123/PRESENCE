package com.presence.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.presence.ui.theme.BrandColors
import com.presence.ui.theme.Haptics
import com.presence.ui.theme.LocalReducedMotion
import com.presence.ui.theme.Motion
import com.presence.ui.theme.SemanticColors
import com.presence.ui.theme.Shapes
import com.presence.ui.theme.Spacing

/**
 * The streak flame, in the brand's greens. Lit (today is proved): it flickers, read only in the draw phase.
 * Not lit: a gray flame, the streak waiting for today. [ignite] pops it in once. Reduced motion: still.
 */
@Composable
fun StreakFlame(size: Dp, lit: Boolean, modifier: Modifier = Modifier, ignite: Boolean = false) {
    val reduced = LocalReducedMotion.current
    val pop = remember { Animatable(if (ignite && !reduced) 0f else 1f) }
    if (ignite && !reduced) LaunchedEffect(Unit) {
        pop.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = Spring.StiffnessLow))
    }
    val animate = lit && !reduced
    val t = rememberInfiniteTransition(label = "flame")
    val flick = if (animate) t.animateFloat(0f, 1f, infiniteRepeatable(tween(520, easing = FastOutSlowInEasing), RepeatMode.Reverse), "flick") else null
    val sway = if (animate) t.animateFloat(-1f, 1f, infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse), "sway") else null
    val core = if (animate) t.animateFloat(0f, 1f, infiniteRepeatable(tween(380, easing = LinearEasing), RepeatMode.Reverse), "core") else null
    val outerFill = if (lit) Brush.verticalGradient(listOf(BrandColors.GreenLight, BrandColors.Green, BrandColors.GreenDeep))
        else SolidColor(BrandColors.Ink.copy(alpha = 0.14f))
    val innerFill = if (lit) Brush.verticalGradient(listOf(BrandColors.GreenMist, BrandColors.White))
        else SolidColor(BrandColors.Ink.copy(alpha = 0.06f))
    Canvas(modifier.size(size).semantics { contentDescription = if (lit) "Streak flame, lit today" else "Streak flame, not lit today" }) {
        val f = flick?.value ?: 0f
        val sw = sway?.value ?: 0f
        val c = core?.value ?: 0f
        val w = this.size.width
        val h = this.size.height
        val base = Offset(w / 2, h * 0.98f)
        scale(pop.value, pop.value, base) {
            rotate(sw * 3f, base) {
                scale(1f - 0.03f * f, 1f + 0.06f * f, base) { drawPath(outerFlame(w, h, sw), outerFill) }
                scale(0.94f + 0.08f * c, 0.9f + 0.12f * c, base) { drawPath(innerFlame(w, h), innerFill) }
            }
        }
    }
}

private fun outerFlame(w: Float, h: Float, sway: Float) = Path().apply {
    val tip = 0.5f + 0.05f * sway
    moveTo(w * tip, h * 0.02f)
    cubicTo(w * 0.66f, h * 0.2f, w * 0.92f, h * 0.36f, w * 0.9f, h * 0.64f)
    cubicTo(w * 0.88f, h * 0.86f, w * 0.72f, h * 0.98f, w * 0.5f, h * 0.98f)
    cubicTo(w * 0.28f, h * 0.98f, w * 0.1f, h * 0.86f, w * 0.1f, h * 0.64f)
    cubicTo(w * 0.1f, h * 0.46f, w * 0.2f, h * 0.36f, w * 0.28f, h * 0.26f) // the side lick
    cubicTo(w * 0.3f, h * 0.36f, w * 0.36f, h * 0.42f, w * 0.42f, h * 0.44f)
    cubicTo(w * 0.4f, h * 0.3f, w * 0.44f, h * 0.14f, w * tip, h * 0.02f)
    close()
}

private fun innerFlame(w: Float, h: Float) = Path().apply {
    moveTo(w * 0.5f, h * 0.44f)
    cubicTo(w * 0.6f, h * 0.56f, w * 0.72f, h * 0.66f, w * 0.7f, h * 0.8f)
    cubicTo(w * 0.68f, h * 0.92f, w * 0.6f, h * 0.96f, w * 0.5f, h * 0.96f)
    cubicTo(w * 0.4f, h * 0.96f, w * 0.3f, h * 0.92f, w * 0.3f, h * 0.8f)
    cubicTo(w * 0.3f, h * 0.66f, w * 0.42f, h * 0.56f, w * 0.5f, h * 0.44f)
    close()
}

/** Flame + count, the compact streak used on Today and in lists. */
@Composable
fun FlameCount(streak: Int, lit: Boolean, modifier: Modifier = Modifier) = Row(modifier, verticalAlignment = Alignment.CenterVertically) {
    StreakFlame(40.dp, lit)
    Spacer(Modifier.width(Spacing.compact))
    Text("$streak", style = MaterialTheme.typography.displayMedium,
        color = if (lit) BrandColors.Green else SemanticColors.PrimaryText)
}

/**
 * The completion ring: today's goal fills the ring, then the flame lights and the count steps up from
 * yesterday's number. Called only after the server verified the session. Reduced motion: drawn complete.
 */
@Composable
fun StreakRing(streak: Int, modifier: Modifier = Modifier, ringSize: Dp = 220.dp) {
    val reduced = LocalReducedMotion.current
    val view = LocalView.current
    val fill = remember { Animatable(if (reduced) 1f else 0f) }
    var lit by remember { mutableStateOf(reduced) }
    var shown by remember { mutableStateOf(if (reduced) streak else (streak - 1).coerceAtLeast(0)) }
    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        kotlinx.coroutines.delay(250)
        fill.animateTo(1f, tween(900, easing = Motion.Emphasized))
        lit = true; Haptics.success(view)
        kotlinx.coroutines.delay(220)
        shown = streak
    }
    Box(modifier.size(ringSize), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 18.dp.toPx()
            val inset = stroke / 2
            val arc = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke)
            drawArc(BrandColors.Ink.copy(alpha = 0.08f), 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
            drawArc(Brush.sweepGradient(listOf(BrandColors.GreenLight, BrandColors.Green, BrandColors.GreenLight)),
                -90f, 360f * fill.value, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (lit) StreakFlame(84.dp, lit = true, ignite = !reduced) else StreakFlame(84.dp, lit = false)
            AnimatedContent(shown, transitionSpec = {
                (slideInVertically(tween(Motion.screen)) { it } + fadeIn(tween(Motion.screen))) togetherWith
                    (slideOutVertically(tween(Motion.screen)) { -it } + fadeOut(tween(Motion.micro)))
            }, label = "count") { n ->
                Text(if (n == 1) "1 day streak" else "$n day streak", style = MaterialTheme.typography.headlineSmall,
                    color = if (lit) BrandColors.Green else SemanticColors.SecondaryText)
            }
        }
    }
}

/** The week as seven circles: a green check for each verified day, today ringed, open days outlined. */
@Composable
fun WeekDots(days: List<WeekDay>, modifier: Modifier = Modifier, popToday: Boolean = false) {
    val reduced = LocalReducedMotion.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        days.forEach { d ->
            val kept = d.state == DayState.Kept || d.state == DayState.TodayKept
            val today = d.state == DayState.TodayKept || d.state == DayState.TodayOpen
            val pop = remember { Animatable(if (popToday && today && !reduced) 0.4f else 1f) }
            if (popToday && today && !reduced) LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(1300)
                pop.animateTo(1f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMediumLow))
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(40.dp).scale(pop.value)
                    .then(if (d.state == DayState.TodayKept) Modifier.border(2.dp, BrandColors.Green, CircleShape).padding(3.dp) else Modifier)
                    .background(if (kept) BrandColors.Green else BrandColors.White, CircleShape)
                    .then(if (!kept) Modifier.border(2.dp, if (today) BrandColors.Green else SemanticColors.Border, CircleShape) else Modifier)
                    .semantics { contentDescription = "${d.label}: " + when (d.state) {
                        DayState.Kept, DayState.TodayKept -> "verified"; DayState.TodayOpen -> "today, not yet"
                        DayState.Interrupted -> "interrupted"; DayState.Upcoming -> "upcoming"; DayState.Empty -> "no session" } },
                    contentAlignment = Alignment.Center) {
                    if (kept) Canvas(Modifier.size(16.dp)) {
                        val p = Path().apply { moveTo(size.width * 0.12f, size.height * 0.52f); lineTo(size.width * 0.4f, size.height * 0.78f); lineTo(size.width * 0.9f, size.height * 0.22f) }
                        drawPath(p, BrandColors.White, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
                    }
                    else if (d.state == DayState.Interrupted) Canvas(Modifier.size(10.dp)) {
                        drawLine(SemanticColors.Disabled, Offset(0f, 0f), Offset(size.width, size.height), 2.dp.toPx(), StrokeCap.Round)
                        drawLine(SemanticColors.Disabled, Offset(size.width, 0f), Offset(0f, size.height), 2.dp.toPx(), StrokeCap.Round)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(if (today) "Today" else d.label, style = MaterialTheme.typography.labelMedium,
                    color = if (today) BrandColors.Green else SemanticColors.TertiaryText)
            }
        }
    }
}

/** Pip says something: the pose and a speech bubble, which pops in just after Pip lands. */
@Composable
fun PipSays(mood: PipMood, text: String, modifier: Modifier = Modifier, pipSize: Dp = 96.dp, live: Boolean = false) {
    val reduced = LocalReducedMotion.current
    val pop = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(text) { if (!reduced) { pop.snapTo(0f); kotlinx.coroutines.delay(260); pop.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)) } }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Pip(mood, pipSize, entrance = true, live = live)
        Spacer(Modifier.width(Spacing.standard))
        Box(Modifier.weight(1f).graphicsLayer {
                scaleX = 0.85f + 0.15f * pop.value; scaleY = scaleX; alpha = pop.value
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f)
            }
            .drawBehind {
                val r = CornerRadius(16.dp.toPx())
                val b = 2.dp.toPx()
                drawRoundRect(BrandColors.White, cornerRadius = r)
                drawRoundRect(SemanticColors.Border, cornerRadius = r, style = Stroke(b))
                val mid = size.height / 2; val half = 8.dp.toPx(); val tail = 10.dp.toPx()
                drawPath(Path().apply { moveTo(b, mid - half); lineTo(-tail, mid); lineTo(b, mid + half); close() }, BrandColors.White)
                drawLine(SemanticColors.Border, Offset(0f, mid - half), Offset(-tail, mid), b, StrokeCap.Round)
                drawLine(SemanticColors.Border, Offset(-tail, mid), Offset(0f, mid + half), b, StrokeCap.Round)
            }
            .padding(horizontal = Spacing.standard, vertical = 14.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium), color = SemanticColors.PrimaryText)
        }
    }
}

/**
 * The post-session moment (shown once per verified session, before the trust receipt): the ring fills, the
 * flame lights, the week gets today's check, Pip speaks. [reward] is the settled amount only, or null.
 */
@Composable
fun SessionComplete(streak: Int, history: List<com.presence.attest.HistoryItem>, reward: String?, onContinue: () -> Unit) {
    // The server just verified this session, so today counts even if the history list hasn't refreshed yet.
    val week = weekOf(history).map { if (it.state == DayState.TodayOpen) it.copy(state = DayState.TodayKept) else it }
    Column(Modifier.fillMaxSize().background(BrandColors.White).statusBarsPadding().navigationBarsPadding()
        .padding(horizontal = Spacing.gutter), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(40.dp))
        Text("Session complete!", style = MaterialTheme.typography.headlineLarge, color = SemanticColors.PrimaryText,
            textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text("You kept your commitment.", style = MaterialTheme.typography.bodyLarge, color = SemanticColors.SecondaryText)
        reward?.let {
            Spacer(Modifier.height(Spacing.related))
            Text(it, style = MaterialTheme.typography.labelLarge, color = BrandColors.Green,
                modifier = Modifier.background(SemanticColors.SuccessTint, Shapes.pill).padding(horizontal = 14.dp, vertical = 6.dp))
        }
        Spacer(Modifier.weight(1f))
        StreakRing(streak)
        Spacer(Modifier.height(28.dp))
        WeekDots(week, popToday = true)
        Spacer(Modifier.weight(1f))
        PipSays(if (isMilestone(streak)) milestoneMood(streak) else PipMood.Champion, when {
            isMilestone(streak) -> "$streak days. That's a milestone!"
            streak == 1 -> "Day one! Come back tomorrow to make it two."
            else -> "$streak days strong. Keep the fire going!"
        }, live = true)
        Spacer(Modifier.height(Spacing.section))
        PrimaryButton("Continue", onClick = onContinue)
        Spacer(Modifier.height(Spacing.standard))
    }
}

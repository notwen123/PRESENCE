package com.presence.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.presence.ui.theme.BrandColors
import com.presence.ui.theme.Haptics
import com.presence.ui.theme.LocalReducedMotion
import com.presence.ui.theme.Motion
import com.presence.ui.theme.SemanticColors
import com.presence.ui.theme.Spacing
import kotlin.math.PI
import kotlin.math.sin

// ---------- Milestones ----------

/** Streak milestones that earn a Milestone Flight. Ordinary days stay compact. */
val MILESTONES = listOf(3, 7, 14, 30, 60, 100, 365)

fun nextMilestone(streak: Int): Int? = MILESTONES.firstOrNull { it > streak }
fun isMilestone(streak: Int) = streak in MILESTONES

private fun milestoneLine(n: Int) = when (n) {
    3 -> "Three days of putting it down."
    7 -> "A whole week, face-down."
    14 -> "Two weeks. It's becoming a habit."
    30 -> "A month of showing up."
    60 -> "Two months. Pip is impressed."
    100 -> "One hundred days."
    else -> "A year of presence."
}

// ---------- Feather ----------

/** One feather: a curved quill with a vane on each side, drawn in a w x h box, tip at the top. */
internal fun DrawScope.feather(color: Color, filled: Boolean, w: Float = size.width, h: Float = size.height) {
    val vane = Path().apply {
        moveTo(w * 0.5f, h * 0.02f)
        cubicTo(w * 1.0f, h * 0.22f, w * 0.95f, h * 0.62f, w * 0.52f, h * 0.86f)
        cubicTo(w * 0.05f, h * 0.62f, w * 0.0f, h * 0.22f, w * 0.5f, h * 0.02f)
        close()
    }
    if (filled) drawPath(vane, color, style = Fill) else drawPath(vane, color, style = Stroke(w * 0.07f))
    val quill = Path().apply {
        moveTo(w * 0.5f, h * 0.1f)
        quadraticBezierTo(w * 0.53f, h * 0.6f, w * 0.48f, h * 0.98f)
    }
    drawPath(quill, if (filled) BrandColors.White.copy(alpha = 0.7f) else color, style = Stroke(w * 0.06f, cap = StrokeCap.Round))
}

/**
 * Feather Trail: the last seven days, oldest first. A day with a verified session is a filled green
 * feather. [arrivingIndex] (a day just earned) flies in on a short arc and settles; everything else is still.
 */
@Composable
fun FeatherTrail(days: List<Boolean>, modifier: Modifier = Modifier, featherSize: Dp = 14.dp, arrivingIndex: Int? = null,
                 onArrived: () -> Unit = {}) {
    val reduced = LocalReducedMotion.current
    val view = LocalView.current
    val flight = remember { Animatable(if (arrivingIndex != null && !reduced) 0f else 1f) }
    val settle = remember { Animatable(if (arrivingIndex != null && !reduced) -38f else 0f) }
    LaunchedEffect(arrivingIndex) {
        if (arrivingIndex == null) return@LaunchedEffect
        if (!reduced) {
            flight.animateTo(1f, tween(Motion.spatial, easing = Motion.Emphasized))
            Haptics.tick(view)
            settle.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium))
        }
        onArrived()
    }
    Row(modifier.semantics { contentDescription = "Feather trail: ${days.count { it }} of the last 7 days kept" },
        horizontalArrangement = Arrangement.spacedBy(featherSize * 0.45f), verticalAlignment = Alignment.CenterVertically) {
        days.forEachIndexed { i, kept ->
            val arriving = i == arrivingIndex
            Canvas(Modifier.size(featherSize, featherSize * 1.6f).graphicsLayer {
                if (arriving) {
                    val t = flight.value
                    translationX = (1 - t) * -size.width * 2.2f
                    translationY = -sin(PI * t).toFloat() * size.height * 0.9f - (1 - t) * size.height * 0.4f
                    rotationZ = settle.value
                    alpha = 0.25f + 0.75f * t
                }
            }) {
                feather(if (kept) BrandColors.Green else SemanticColors.Border, filled = kept)
            }
        }
    }
}


// ---------- Attestation Seal ----------

enum class SealState { Pending, Verified, Unconfirmed }

/**
 * The evidence ring. Pending: a quiet ring while the server and chain answer. Verified (only when the
 * server said so): the ring closes, then a checkmark draws itself, with one success haptic.
 * Unconfirmed: the ring stays open, honestly.
 */
@Composable
fun AttestationSeal(state: SealState, size: Dp = 88.dp) {
    val reduced = LocalReducedMotion.current
    val view = LocalView.current
    val ring = remember { Animatable(if (state == SealState.Verified && reduced) 1f else 0.18f) }
    val check = remember { Animatable(if (state == SealState.Verified && reduced) 1f else 0f) }
    LaunchedEffect(state) {
        when (state) {
            SealState.Verified -> {
                ring.animateTo(1f, tween(if (reduced) 0 else Motion.seal / 2, easing = Motion.Emphasized))
                check.animateTo(1f, tween(if (reduced) 0 else Motion.seal / 2, easing = Motion.Standard))
                Haptics.success(view)
            }
            SealState.Unconfirmed -> ring.animateTo(0.66f, tween(if (reduced) 0 else Motion.screen))
            SealState.Pending -> Unit
        }
    }
    val label = when (state) {
        SealState.Verified -> "Verified"
        SealState.Pending -> "Waiting for verification"
        SealState.Unconfirmed -> "Not confirmed"
    }
    Canvas(Modifier.size(size).semantics { contentDescription = label }) {
        val stroke = this.size.minDimension * 0.07f
        val inset = stroke / 2
        drawArc(SemanticColors.Divider, 0f, 360f, false, Offset(inset, inset),
            androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke), style = Stroke(stroke))
        drawArc(BrandColors.Green, -90f, 360f * ring.value, false, Offset(inset, inset),
            androidx.compose.ui.geometry.Size(this.size.width - stroke, this.size.height - stroke),
            style = Stroke(stroke, cap = StrokeCap.Round))
        if (check.value > 0f) {
            val w = this.size.width
            val path = Path().apply {
                moveTo(w * 0.3f, w * 0.52f); lineTo(w * 0.45f, w * 0.66f); lineTo(w * 0.71f, w * 0.38f)
            }
            val measure = PathMeasure().apply { setPath(path, false) }
            val partial = Path()
            measure.getSegment(0f, measure.length * check.value, partial, true)
            drawPath(partial, BrandColors.Green, style = Stroke(stroke * 1.1f, cap = StrokeCap.Round))
        }
    }
}

// ---------- Milestone Flight ----------

/**
 * Mode C: the rare milestone. Feathers fan out into a crest around the number, Pip celebrates once.
 * Tap anywhere (or "Continue") to skip; it never blocks the next action.
 */
@Composable
fun MilestoneFlight(streak: Int, onDone: () -> Unit, onShare: () -> Unit) {
    val reduced = LocalReducedMotion.current
    val view = LocalView.current
    val fan = remember { Animatable(if (reduced) 1f else 0f) }
    val number = remember { Animatable(if (reduced) 1f else 0.6f) }
    LaunchedEffect(Unit) {
        if (!reduced) {
            fan.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessLow))
            Haptics.success(view)
        }
    }
    LaunchedEffect(Unit) {
        if (!reduced) number.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    }
    val tier = MILESTONES.indexOf(streak).coerceAtLeast(0)
    val feathers = (5 + tier).coerceAtMost(11) // bigger milestones, fuller crest: same language, more of it
    Box(Modifier.fillMaxSize().background(BrandColors.White)
        .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDone),
        contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(Spacing.gutter)) {
            Pip(milestoneMood(streak), 132.dp, entrance = true)
            Spacer(Modifier.size(Spacing.compact))
            Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val c = Offset(size.width / 2, size.height / 2)
                    val f = fan.value
                    val spread = 150f // degrees across the crest
                    for (i in 0 until feathers) {
                        val angle = (-spread / 2 + spread * i / (feathers - 1)) * f
                        rotate(angle, c) {
                            translate(c.x - 14.dp.toPx(), c.y - size.height * 0.48f) {
                                feather(BrandColors.Green.copy(alpha = 0.55f + 0.45f * (1 - kotlin.math.abs(angle) / 90f)),
                                    filled = true, w = 28.dp.toPx(), h = 46.dp.toPx())
                            }
                        }
                    }
                    if (tier >= 3) drawCircle(SemanticColors.SuccessSoft, size.minDimension * 0.27f, c, style = Stroke(4.dp.toPx()))
                }
                Column(Modifier.padding(top = 40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    StreakFlame(64.dp, lit = true, ignite = true)
                    Text("$streak", style = MaterialTheme.typography.displayMedium, color = BrandColors.Green,
                        modifier = Modifier.graphicsLayer { scaleX = number.value; scaleY = number.value })
                }
            }
            Text("$streak-day streak", style = MaterialTheme.typography.headlineLarge, color = SemanticColors.PrimaryText,
                textAlign = TextAlign.Center)
            Spacer(Modifier.size(Spacing.compact))
            Text(milestoneLine(streak), style = MaterialTheme.typography.bodyLarge, color = SemanticColors.SecondaryText,
                textAlign = TextAlign.Center)
            Spacer(Modifier.size(Spacing.section))
            PrimaryButton("Continue", Modifier.width(240.dp), onClick = onDone)
            TextAction("Share") { onShare() }
        }
    }
}

// ---------- Week (Mode 1) ----------

enum class DayState { Kept, TodayKept, TodayOpen, Interrupted, Empty, Upcoming }

data class WeekDay(val label: String, val state: DayState)

/**
 * This calendar week, Monday to Sunday, from the server's own session receipts: a day is Kept only if a session
 * was verified that day; Interrupted if sessions were made but none verified; Empty if nothing happened.
 */
fun weekOf(history: List<com.presence.attest.HistoryItem>, nowMs: Long = System.currentTimeMillis()): List<WeekDay> {
    val cal = java.util.Calendar.getInstance()
    fun key(ms: Long) = cal.apply { timeInMillis = ms }.let { it.get(java.util.Calendar.YEAR) * 1000 + it.get(java.util.Calendar.DAY_OF_YEAR) }
    val verified = history.filter { it.state == "SETTLED" || it.state == "VERIFIED" }
        .mapNotNull { h -> h.startedAt?.let { key((it * 1000).toLong()) } }.toSet()
    val attempted = history.mapNotNull { h -> h.startedAt?.let { key((it * 1000).toLong()) } }.toSet()
    cal.timeInMillis = nowMs
    val dow = (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7 // Monday = 0
    val today = key(nowMs)
    val fmt = java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault())
    return (0 until 7).map { i ->
        val ms = nowMs + (i - dow) * 86_400_000L
        val k = key(ms)
        val state = when {
            i > dow -> DayState.Upcoming
            k == today -> if (k in verified) DayState.TodayKept else DayState.TodayOpen
            k in verified -> DayState.Kept
            k in attempted -> DayState.Interrupted
            else -> DayState.Empty
        }
        WeekDay(fmt.format(java.util.Date(ms)).take(3), state)
    }
}

/**
 * The feather week. Each state looks different on purpose: green gradient feathers for kept days (today ringed),
 * a dashed green outline for today still open, an outlined gray feather for an interrupted day, a small dot for an
 * empty past day, a faint outline for days still ahead. A green trail joins consecutive kept days.
 */
@Composable
fun FeatherWeek(days: List<WeekDay>, modifier: Modifier = Modifier, featherSize: Dp = 18.dp, arrivingToday: Boolean = false,
                onArrived: () -> Unit = {}, onDay: ((Int) -> Unit)? = null) {
    val reduced = LocalReducedMotion.current
    val view = LocalView.current
    val flight = remember { Animatable(if (arrivingToday && !reduced) 0f else 1f) }
    LaunchedEffect(arrivingToday) {
        if (!arrivingToday) return@LaunchedEffect
        if (!reduced) { flight.animateTo(1f, tween(Motion.spatial, easing = Motion.Emphasized)); Haptics.tick(view) }
        onArrived()
    }
    val kept = { d: WeekDay -> d.state == DayState.Kept || d.state == DayState.TodayKept }
    Box(modifier.fillMaxWidth().semantics {
        contentDescription = "This week: ${days.count(kept)} of ${days.count { it.state != DayState.Upcoming }} days kept"
    }) {
        // The trail: a soft green line behind each run of consecutive kept days.
        Canvas(Modifier.matchParentSize()) {
            val col = size.width / 7
            val y = featherSize.toPx() * 0.9f
            for (i in 0 until 6) if (kept(days[i]) && kept(days[i + 1])) {
                drawLine(SemanticColors.SuccessSoft, Offset(col * (i + 0.5f), y), Offset(col * (i + 1.5f), y),
                    strokeWidth = featherSize.toPx() * 0.28f, cap = StrokeCap.Round)
            }
        }
        Row(Modifier.fillMaxWidth()) {
            days.forEachIndexed { i, d ->
                val today = d.state == DayState.TodayKept || d.state == DayState.TodayOpen
                Column(Modifier.weight(1f).then(if (onDay != null) Modifier.clickable { onDay(i) } else Modifier),
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(featherSize * 1.8f).then(
                        if (d.state == DayState.TodayKept) Modifier.background(SemanticColors.SuccessTint, androidx.compose.foundation.shape.CircleShape) else Modifier),
                        contentAlignment = Alignment.Center) {
                        Canvas(Modifier.size(featherSize, featherSize * 1.6f).graphicsLayer {
                            if (d.state == DayState.TodayKept && arrivingToday) {
                                val t = flight.value
                                translationY = -sin(PI * t).toFloat() * size.height * 0.8f - (1 - t) * size.height * 0.6f
                                rotationZ = (1 - t) * -30f
                                alpha = 0.3f + 0.7f * t
                            }
                        }) {
                            when (d.state) {
                                DayState.Kept, DayState.TodayKept -> featherGradient()
                                DayState.TodayOpen -> featherDashed(BrandColors.Green)
                                DayState.Interrupted -> feather(SemanticColors.Disabled, filled = false)
                                DayState.Upcoming -> feather(SemanticColors.Divider, filled = false)
                                DayState.Empty -> drawCircle(SemanticColors.Disabled, size.width * 0.14f, Offset(size.width / 2, size.height / 2))
                            }
                        }
                    }
                    Text(if (today) "Today" else d.label, style = MaterialTheme.typography.labelMedium,
                        color = when {
                            today -> SemanticColors.PrimaryText
                            d.state == DayState.Upcoming -> SemanticColors.TertiaryText
                            else -> SemanticColors.SecondaryText
                        })
                }
            }
        }
    }
}

private fun DrawScope.featherGradient() {
    val w = size.width; val h = size.height
    val vane = Path().apply {
        moveTo(w * 0.5f, h * 0.02f)
        cubicTo(w * 1.0f, h * 0.22f, w * 0.95f, h * 0.62f, w * 0.52f, h * 0.86f)
        cubicTo(w * 0.05f, h * 0.62f, w * 0.0f, h * 0.22f, w * 0.5f, h * 0.02f)
        close()
    }
    drawPath(vane, androidx.compose.ui.graphics.Brush.verticalGradient(
        listOf(BrandColors.Green.copy(alpha = 0.7f), BrandColors.Green), 0f, h))
    val quill = Path().apply { moveTo(w * 0.5f, h * 0.1f); quadraticBezierTo(w * 0.53f, h * 0.6f, w * 0.48f, h * 0.98f) }
    drawPath(quill, BrandColors.White.copy(alpha = 0.75f), style = Stroke(w * 0.06f, cap = StrokeCap.Round))
}

private fun DrawScope.featherDashed(color: Color) {
    val w = size.width; val h = size.height
    val vane = Path().apply {
        moveTo(w * 0.5f, h * 0.02f)
        cubicTo(w * 1.0f, h * 0.22f, w * 0.95f, h * 0.62f, w * 0.52f, h * 0.86f)
        cubicTo(w * 0.05f, h * 0.62f, w * 0.0f, h * 0.22f, w * 0.5f, h * 0.02f)
        close()
    }
    drawPath(vane, color, style = Stroke(w * 0.08f, pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(w * 0.18f, w * 0.12f))))
}

// ---------- Milestone journey ----------

/**
 * Earned milestones are solid medallions (tap to replay their flight); the next one is larger with a ring showing
 * real progress toward it; later ones are quiet but legible.
 */
@Composable
fun MilestoneJourney(current: Int, best: Int, onEarned: (Int) -> Unit, modifier: Modifier = Modifier) {
    val next = nextMilestone(current)
    val shown = MILESTONES.take(5)
    Box(modifier.fillMaxWidth()) {
        Canvas(Modifier.matchParentSize()) {
            val y = 30.dp.toPx()
            drawLine(SemanticColors.Divider, Offset(size.width * 0.1f, y), Offset(size.width * 0.9f, y), 3.dp.toPx(), StrokeCap.Round)
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            shown.forEach { n ->
                val earned = best >= n
                val isNext = n == next
                Column(Modifier.weight(1f).then(if (earned) Modifier.clickable { onEarned(n) } else Modifier)
                    .semantics { contentDescription = when { earned -> "$n days, earned"; isNext -> "$n days, next: $current of $n"; else -> "$n days" } },
                    horizontalAlignment = Alignment.CenterHorizontally) {
                    val d = if (isNext) 60.dp else 48.dp
                    Box(Modifier.padding(top = if (isNext) 0.dp else 6.dp).size(d), contentAlignment = Alignment.Center) {
                        Canvas(Modifier.fillMaxSize()) {
                            val r = size.minDimension / 2
                            when {
                                earned -> drawCircle(androidx.compose.ui.graphics.Brush.linearGradient(
                                    listOf(BrandColors.Green.copy(alpha = 0.8f), BrandColors.Green)), r)
                                isNext -> {
                                    drawCircle(BrandColors.White, r)
                                    drawCircle(SemanticColors.Divider, r - 3.dp.toPx(), style = Stroke(5.dp.toPx()))
                                    drawArc(BrandColors.Green, -90f, 360f * (current.toFloat() / n).coerceIn(0f, 1f), false,
                                        Offset(3.dp.toPx(), 3.dp.toPx()), androidx.compose.ui.geometry.Size(size.width - 6.dp.toPx(), size.height - 6.dp.toPx()),
                                        style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
                                }
                                else -> drawCircle(Color(0xFFF3F4F6), r)
                            }
                        }
                        Text("$n", style = if (isNext) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                            color = when { earned -> BrandColors.White; isNext -> SemanticColors.PrimaryText; else -> SemanticColors.SecondaryText })
                    }
                    Spacer(Modifier.size(6.dp))
                    Text(when { earned -> "Earned"; isNext -> "Next"; else -> "days" }, style = MaterialTheme.typography.labelMedium,
                        color = when { earned -> BrandColors.Green; isNext -> SemanticColors.PrimaryText; else -> SemanticColors.TertiaryText })
                }
            }
        }
    }
}

// ---------- Streak hero ----------

/** The Progress focal point: the real count, a message for the real state, and Pip on the way to the next milestone. */
@Composable
fun StreakHero(streak: Int, provedToday: Boolean, modifier: Modifier = Modifier) {
    val next = nextMilestone(streak)
    val prev = MILESTONES.lastOrNull { it <= streak } ?: 0
    val fraction = if (next == null) 1f else ((streak - prev).toFloat() / (next - prev)).coerceIn(0f, 1f)
    val reduced = LocalReducedMotion.current
    val shown = remember { Animatable(if (reduced) fraction else 0f) }
    LaunchedEffect(fraction) { shown.animateTo(fraction, tween(if (reduced) 0 else Motion.spatial, easing = Motion.Emphasized)) }
    Column(modifier.fillMaxWidth().background(SemanticColors.SuccessTint, com.presence.ui.theme.Shapes.large)
        .padding(horizontal = Spacing.standard, vertical = Spacing.section)) {
        Row(verticalAlignment = Alignment.Bottom) {
            StreakFlame(56.dp, lit = provedToday, modifier = Modifier.padding(bottom = 6.dp))
            Spacer(Modifier.width(Spacing.compact))
            Text("$streak", style = MaterialTheme.typography.displayLarge, color = if (provedToday) BrandColors.Green else SemanticColors.PrimaryText)
            Spacer(Modifier.width(Spacing.compact))
            Text(if (streak == 1) "day streak" else "days streak", style = MaterialTheme.typography.headlineSmall,
                color = SemanticColors.PrimaryText, modifier = Modifier.padding(bottom = 9.dp))
        }
        Text(when {
            streak == 0 -> "Your first verified session starts your streak."
            provedToday && streak == 1 -> "Day one, done. Come back tomorrow to make it two."
            provedToday -> "Today is in. Pip is keeping count."
            else -> "Go facedown today to keep it going."
        }, style = MaterialTheme.typography.bodyLarge, color = SemanticColors.SecondaryText)
        Spacer(Modifier.size(Spacing.section))
        // The track to the next milestone, with Pip standing where you are.
        Box(Modifier.fillMaxWidth().size(height = 84.dp, width = 0.dp)) {
            Canvas(Modifier.fillMaxWidth().size(height = 84.dp, width = 0.dp)) {
                val y = size.height - 6.dp.toPx()
                drawLine(BrandColors.Green.copy(alpha = 0.16f), Offset(0f, y), Offset(size.width, y), 8.dp.toPx(), StrokeCap.Round)
                drawLine(BrandColors.Green, Offset(0f, y), Offset(size.width * shown.value, y), 8.dp.toPx(), StrokeCap.Round)
            }
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
                val pip = 72.dp
                val x = (maxWidth - pip) * shown.value
                Pip(when { streak == 0 -> PipMood.Resting; provedToday -> PipMood.Runner; else -> PipMood.Coach }, pip,
                    Modifier.padding(start = x).align(Alignment.BottomStart).padding(bottom = 6.dp))
            }
        }
        Spacer(Modifier.size(6.dp))
        Row(Modifier.fillMaxWidth()) {
            Text("Day $prev".takeIf { prev > 0 } ?: "Start", style = MaterialTheme.typography.labelMedium, color = SemanticColors.TertiaryText)
            Spacer(Modifier.weight(1f))
            Text(next?.let { "$it-day milestone · ${it - streak} to go" } ?: "Every milestone reached",
                style = MaterialTheme.typography.labelMedium, color = SemanticColors.PrimaryText)
        }
    }
}

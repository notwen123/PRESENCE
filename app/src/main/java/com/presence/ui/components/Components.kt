package com.presence.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.presence.ui.theme.BrandColors
import com.presence.ui.theme.LocalReducedMotion
import com.presence.ui.theme.Motion
import com.presence.ui.theme.SemanticColors
import com.presence.ui.theme.Shapes
import com.presence.ui.theme.Spacing

/** A full-screen page on the white canvas, inside the system bars. */
@Composable
fun Page(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) = Column(
    modifier
        .fillMaxSize()
        .background(SemanticColors.Canvas)
        .statusBarsPadding()
        .navigationBarsPadding()
        .padding(horizontal = Spacing.gutter),
    content = content,
)

@Composable
fun TopBar(title: String = "FACEDOWN", onBack: (() -> Unit)? = null, trailing: @Composable RowScope.() -> Unit = {}) =
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 0.dp)) {
                Text("← Back", style = MaterialTheme.typography.labelLarge, color = SemanticColors.PrimaryText)
            }
        } else {
            // Wordmark: "FACEDOWN." with a green full stop.
            Text(buildAnnotatedString {
                append(title)
                withStyle(SpanStyle(color = BrandColors.Green)) { append(".") }
            }, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Black, letterSpacing = 1.sp),
                color = SemanticColors.PrimaryText, modifier = Modifier.semantics { heading() })
        }
        Spacer(Modifier.weight(1f))
        trailing()
    }

/** Pressed state: a slight settle, skipped when the user asked for reduced motion. */
@Composable
private fun pressScale(source: MutableInteractionSource): Float {
    val pressed by source.collectIsPressedAsState()
    val reduced = LocalReducedMotion.current
    return animateFloatAsState(if (pressed && !reduced) 0.98f else 1f, tween(Motion.micro), label = "press").value
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false,
                  onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        interactionSource = source,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).scale(pressScale(source)),
        shape = Shapes.standard,
        colors = ButtonDefaults.buttonColors(
            containerColor = BrandColors.Green, contentColor = BrandColors.White,
            disabledContainerColor = SemanticColors.Subtle,
            disabledContentColor = if (loading) SemanticColors.PrimaryText else SemanticColors.TertiaryText),
        elevation = null,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(16.dp), color = SemanticColors.PrimaryText, strokeWidth = 2.dp)
            Spacer(Modifier.width(Spacing.related))
        }
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    OutlinedButton(
        onClick = onClick,
        interactionSource = source,
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp).scale(pressScale(source)),
        shape = Shapes.standard,
        border = BorderStroke(1.dp, SemanticColors.Border),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = SemanticColors.PrimaryText) }
}

@Composable
fun TextAction(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) =
    TextButton(onClick = onClick, modifier = modifier.heightIn(min = 48.dp)) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = SemanticColors.PrimaryText)
    }

/** Small capsule label. [accent] = green, for genuinely verified/active states only. */
@Composable
fun StatusLabel(text: String, accent: Boolean = false, modifier: Modifier = Modifier) {
    val color = if (accent) BrandColors.Green else SemanticColors.SecondaryText
    Row(
        modifier
            .background(if (accent) SemanticColors.SuccessTint else SemanticColors.Subtle, Shapes.pill)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(6.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** "Development · devnet", always visible outside mainnet production. */
@Composable
fun EnvironmentLabel(mode: String?, network: String?) {
    if (mode == null && network == null) return
    if (mode == "production" && network == "mainnet") return
    StatusLabel(listOfNotNull(mode?.replaceFirstChar { it.uppercase() }, network).joinToString(" · "))
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) =
    Text(text, style = MaterialTheme.typography.labelMedium, color = SemanticColors.TertiaryText,
        modifier = modifier.semantics { heading() })

@Composable
fun Hairline(modifier: Modifier = Modifier) =
    Box(modifier.fillMaxWidth().height(1.dp).background(SemanticColors.Divider))

@Composable
fun Gap(h: Int) = Spacer(Modifier.height(h.dp))

/** One label/value line, used by confirmation, receipt and profile. */
@Composable
fun KeyValue(key: String, value: String, valueColor: Color = SemanticColors.PrimaryText, mono: Boolean = false,
             @DrawableRes icon: Int? = null) =
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(vertical = Spacing.compact),
        verticalAlignment = Alignment.CenterVertically) {
        icon?.let {
            Image(painterResource(it), null, Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.related))
        }
        Text(key, style = MaterialTheme.typography.bodyMedium, color = SemanticColors.SecondaryText)
        Spacer(Modifier.width(Spacing.standard))
        Spacer(Modifier.weight(1f))
        Text(value, style = if (mono) MaterialTheme.typography.labelSmall else MaterialTheme.typography.titleMedium,
            color = valueColor, textAlign = TextAlign.End)
    }

/** Bordered group of rows. One level only: never nested. */
@Composable
fun Group(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) = Column(
    modifier.fillMaxWidth().border(1.dp, SemanticColors.Border, Shapes.standard)
        .padding(horizontal = Spacing.standard, vertical = Spacing.micro),
    content = content,
)

/** Segmented choice between the real missions (e.g. 2 min / 25 min). */
@Composable
fun <T> DurationSelector(options: List<T>, selected: T?, label: (T) -> String, enabled: (T) -> Boolean = { true },
                         onSelect: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(SemanticColors.Subtle, Shapes.standard).padding(Spacing.micro).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.micro),
    ) {
        options.forEach { o ->
            val on = o == selected
            val bg by animateColorAsState(if (on) BrandColors.White else Color.Transparent, tween(Motion.control), label = "seg")
            Box(
                Modifier.weight(1f).heightIn(min = 48.dp)
                    .then(if (on) Modifier.border(1.dp, SemanticColors.Border, Shapes.small) else Modifier)
                    .background(bg, Shapes.small)
                    .selectable(on, enabled = enabled(o), role = Role.Tab) { onSelect(o) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label(o), style = MaterialTheme.typography.labelLarge,
                    color = when {
                        on -> SemanticColors.PrimaryText
                        enabled(o) -> SemanticColors.SecondaryText
                        else -> SemanticColors.Disabled
                    })
            }
        }
    }
}

/** Centered Pip + message + optional action: empty, error and interruption states. */
@Composable
fun MessageState(mood: PipMood, title: String, body: String, modifier: Modifier = Modifier,
                 action: String? = null, onAction: () -> Unit = {}) =
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Pip(mood, 120.dp)
        Gap(12)
        Text(title, style = MaterialTheme.typography.titleMedium, color = SemanticColors.PrimaryText,
            textAlign = TextAlign.Center)
        Gap(4)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = SemanticColors.SecondaryText,
            textAlign = TextAlign.Center)
        if (action != null) {
            Gap(8)
            TextAction(action, onClick = onAction)
        }
    }

/** Thin progress ring; the timer text sits inside. */
@Composable
fun ProgressRing(progress: Float, modifier: Modifier = Modifier, color: Color = BrandColors.Green) =
    Canvas(modifier) {
        val stroke = Stroke(width = 4.dp.toPx(), cap = StrokeCap.Round)
        drawArc(SemanticColors.Divider, 0f, 360f, false, style = stroke)
        drawArc(color, -90f, 360f * progress, false, style = stroke)
    }

enum class Tab(val label: String) { Today("Today"), Progress("Progress"), Profile("Profile") }

@Composable
fun ClockInBottomNavigation(selected: Tab, onSelect: (Tab) -> Unit) = Column(
    Modifier.fillMaxWidth().background(SemanticColors.Canvas).navigationBarsPadding()
) {
    Hairline()
    Row(Modifier.fillMaxWidth().height(64.dp).selectableGroup()) {
        Tab.entries.forEach { t ->
            val on = t == selected
            val color = if (on) BrandColors.Green else SemanticColors.SecondaryText
            Column(
                Modifier.weight(1f).fillMaxSize().selectable(on, role = Role.Tab) { onSelect(t) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Canvas(Modifier.size(24.dp)) { tabIcon(t, color) }
                Gap(4)
                Text(t.label, style = MaterialTheme.typography.labelMedium, color = color)
            }
        }
    }
}

/** 24dp line icons with one stroke weight, so the three tabs read as a set. */
private fun DrawScope.tabIcon(t: Tab, color: Color) {
    val s = size.minDimension / 24f
    val w = 1.8f * s
    fun p(x: Float, y: Float) = Offset(x * s, y * s)
    when (t) {
        Tab.Today -> { // a clock
            drawCircle(color, 9f * s, p(12f, 12f), style = Stroke(w))
            drawLine(color, p(12f, 12f), p(12f, 7f), w, StrokeCap.Round)
            drawLine(color, p(12f, 12f), p(15.5f, 13.5f), w, StrokeCap.Round)
        }
        Tab.Progress -> { // a timeline of three points
            drawLine(color, p(7f, 5f), p(7f, 19f), w, StrokeCap.Round)
            listOf(6f, 12f, 18f).forEach { y ->
                drawCircle(color, 1.6f * s, p(7f, y))
                drawLine(color, p(11f, y), p(19f, y), w, StrokeCap.Round)
            }
        }
        Tab.Profile -> {
            drawCircle(color, 3.8f * s, p(12f, 8.5f), style = Stroke(w))
            drawArc(color, 200f, 140f, false, p(4.5f, 14f), Size(15f * s, 12f * s),
                style = Stroke(w, cap = StrokeCap.Round))
        }
    }
}

fun shortAddress(a: String) = if (a.length > 10) "${a.take(4)}…${a.takeLast(4)}" else a

fun formatToken(v: Double): String =
    if (v % 1.0 == 0.0) v.toLong().toString() else "%.2f".format(v).trimEnd('0').trimEnd('.')

fun minutesLabel(seconds: Int): String = if (seconds >= 60) "${seconds / 60} min" else "$seconds sec"

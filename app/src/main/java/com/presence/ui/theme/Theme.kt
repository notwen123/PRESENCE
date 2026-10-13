package com.presence.ui.theme

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.presence.R

/** The whole brand: white canvas, near-black ink, one green. Everything else is a tone of these three. */
object BrandColors {
    val White = Color(0xFFFFFFFF)
    val Ink = Color(0xFF111827)
    val Green = Color(0xFF15803D) // 5.0:1 against white: passes AA for white button labels and text
    // Tones of the one green, for depth only: the 3D button lip, the flame's base and highlight.
    val GreenDeep = Color(0xFF0E5A2B)
    val GreenLight = Color(0xFF3FA165)
    val GreenMist = Color(0xFFE8F5EC)
}

object SemanticColors {
    val Canvas = BrandColors.White
    val PrimaryText = BrandColors.Ink
    val SecondaryText = BrandColors.Ink.copy(alpha = 0.64f)
    val TertiaryText = BrandColors.Ink.copy(alpha = 0.48f)
    val Divider = BrandColors.Ink.copy(alpha = 0.08f)
    val Border = BrandColors.Ink.copy(alpha = 0.12f)
    val Subtle = BrandColors.Ink.copy(alpha = 0.04f) // quiet fills: selected rows, code blocks
    val Disabled = BrandColors.Ink.copy(alpha = 0.24f)
    val Success = BrandColors.Green
    val SuccessTint = BrandColors.Green.copy(alpha = 0.08f)
    val SuccessSoft = BrandColors.Green.copy(alpha = 0.18f)
    /** The only gradient outside the mascot art: progress and milestone moments. */
    val ProgressGradient = Brush.linearGradient(listOf(BrandColors.Green.copy(alpha = 0.72f), BrandColors.Green))
}

object Spacing {
    val micro = 4.dp
    val compact = 8.dp
    val related = 12.dp
    val standard = 16.dp
    val section = 24.dp
    val major = 32.dp
    val large = 48.dp
    val gutter = 24.dp
}

object Shapes {
    val small = RoundedCornerShape(10.dp)
    val standard = RoundedCornerShape(16.dp)
    val large = RoundedCornerShape(24.dp)
    val pill = RoundedCornerShape(50)
}

/**
 * Motion is a system with five levels: micro (controls), spatial (one view becoming another), mascot
 * (a pose change), success (verified), milestone (rare). Durations in ms; nothing loops while idle.
 */
object Motion {
    const val micro = 120
    const val control = 180
    const val screen = 220
    const val spatial = 420
    const val completion = 380
    const val seal = 900
    const val milestone = 1400

    /** Standard: quick out, gentle settle. Emphasized: a longer, decisive arrival for spatial moves. */
    val Standard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    val Emphasized: Easing = CubicBezierEasing(0.3f, 0f, 0f, 1f)
    val Exit: Easing = CubicBezierEasing(0.3f, 0f, 1f, 1f)
}

/** Haptics are reserved for confirmation: a selection tick and a real success. Never decorative. */
object Haptics {
    fun tick(view: View) = view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    fun success(view: View) = view.performHapticFeedback(
        if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM else HapticFeedbackConstants.LONG_PRESS)
}

/** The user's motion choice (Profile → Motion). Android's own "remove animations" always wins. */
object MotionSettings {
    private const val KEY = "motion_reduced"
    var reduced by mutableStateOf(false)
        private set

    fun load(ctx: Context) {
        reduced = ctx.getSharedPreferences("presence", Context.MODE_PRIVATE).getBoolean(KEY, false)
    }

    fun set(ctx: Context, value: Boolean) {
        reduced = value
        ctx.getSharedPreferences("presence", Context.MODE_PRIVATE).edit().putBoolean(KEY, value).apply()
    }
}

@OptIn(ExperimentalTextApi::class)
private fun variable(res: Int, weight: Int) =
    Font(res, FontWeight(weight), variationSettings = FontVariation.Settings(FontVariation.weight(weight)))

/** Manrope for display and headlines, Inter for interface and body. Both OFL, bundled in res/font. */
val Manrope = FontFamily(variable(R.font.manrope, 500), variable(R.font.manrope, 600), variable(R.font.manrope, 700),
    variable(R.font.manrope, 800))
val Inter = FontFamily(variable(R.font.inter, 400), variable(R.font.inter, 500), variable(R.font.inter, 600))
private const val TABULAR = "tnum"

val ClockInTypography = Typography(
    displayLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.SemiBold, fontSize = 56.sp, lineHeight = 60.sp,
        letterSpacing = (-1.5).sp, fontFeatureSettings = TABULAR),
    displayMedium = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 44.sp, lineHeight = 48.sp,
        letterSpacing = (-1).sp, fontFeatureSettings = TABULAR),
    headlineLarge = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 30.sp, lineHeight = 36.sp,
        letterSpacing = (-0.6).sp),
    headlineSmall = TextStyle(fontFamily = Manrope, fontWeight = FontWeight.Bold, fontSize = 21.sp, lineHeight = 27.sp,
        letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = Inter, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Inter, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 0.2.sp,
        fontFeatureSettings = TABULAR),
    labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp),
)

/** True when the user wants less motion: Android "remove animations", or Profile → Motion → Reduced. */
val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
fun ClockInTheme(content: @Composable () -> Unit) {
    val resolver = LocalContext.current.contentResolver
    val system = remember { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    CompositionLocalProvider(LocalReducedMotion provides (system || MotionSettings.reduced)) {
        MaterialTheme(
            colorScheme = lightColorScheme(
                primary = BrandColors.Green, onPrimary = BrandColors.White,
                background = BrandColors.White, onBackground = BrandColors.Ink,
                surface = BrandColors.White, onSurface = BrandColors.Ink,
                surfaceVariant = SemanticColors.Subtle, onSurfaceVariant = SemanticColors.SecondaryText,
                outline = SemanticColors.Border, outlineVariant = SemanticColors.Divider,
                inverseSurface = BrandColors.Ink, inverseOnSurface = BrandColors.White,
                error = BrandColors.Ink, onError = BrandColors.White,
                secondaryContainer = SemanticColors.SuccessTint, onSecondaryContainer = BrandColors.Green,
            ),
            typography = ClockInTypography,
            content = content,
        )
    }
}

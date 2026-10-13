package com.presence.ui.theme

import android.provider.Settings
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The whole brand: white canvas, near-black ink, one green. Everything else is ink at an opacity. */
object BrandColors {
    val White = Color(0xFFFFFFFF)
    val Ink = Color(0xFF111827)
    val Green = Color(0xFF15803D) // 5.0:1 against white: passes AA for white button labels and text
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

/** Durations in ms. Motion marks a state change; nothing loops. */
object Motion {
    const val micro = 120
    const val control = 180
    const val screen = 220
    const val completion = 380
}

private val Sans = FontFamily.SansSerif
private const val TABULAR = "tnum"

val ClockInTypography = Typography(
    displayLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 56.sp, lineHeight = 60.sp,
        letterSpacing = (-1.5).sp, fontFeatureSettings = TABULAR),
    displayMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 48.sp, lineHeight = 52.sp,
        letterSpacing = (-1).sp, fontFeatureSettings = TABULAR),
    headlineLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 38.sp,
        letterSpacing = (-0.6).sp),
    headlineSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 22.sp, lineHeight = 28.sp,
        letterSpacing = (-0.2).sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 16.sp, letterSpacing = 0.1.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, letterSpacing = 0.2.sp,
        fontFeatureSettings = TABULAR),
    labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 17.sp),
)

/** True when the user turned animations off (developer options / accessibility "remove animations"). */
val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
fun ClockInTheme(content: @Composable () -> Unit) {
    val resolver = LocalContext.current.contentResolver
    val reduced = remember {
        Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
    CompositionLocalProvider(LocalReducedMotion provides reduced) {
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

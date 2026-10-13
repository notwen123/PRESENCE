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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.presence.ui.theme.BrandColors
import com.presence.ui.theme.LocalReducedMotion
import com.presence.ui.theme.SemanticColors
import com.presence.ui.theme.Spacing
import com.presence.wallet.WalletAccount

private const val EXPLORER = "https://testnet.monadvision.com/address/"

/**
 * Shown once, right after Google sign-in created the user's Monad wallet: the card springs in, the check draws
 * itself, and every line says why this wallet can be trusted. The full address is shown, copyable and checkable.
 */
@Composable
fun WalletReadyOverlay(wallet: WalletAccount, onDone: () -> Unit) {
    val reduced = LocalReducedMotion.current
    val pop = remember { Animatable(if (reduced) 1f else 0.82f) }
    val check = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(Unit) {
        if (reduced) return@LaunchedEffect
        pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
        check.animateTo(1f, tween(520))
    }
    val clipboard = LocalClipboardManager.current
    val uri = LocalUriHandler.current
    var copied by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color(0xCC0B1220))
        .clickable(remember { MutableInteractionSource() }, indication = null) {}, contentAlignment = Alignment.Center) {
        Column(Modifier.padding(Spacing.gutter).scale(pop.value).clip(RoundedCornerShape(28.dp)).background(SemanticColors.Canvas)
            .padding(Spacing.section), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(contentAlignment = Alignment.BottomEnd) {
                Pip(PipMood.Guard, size = 112.dp, entrance = true)
                DrawnCheck(check.value, Modifier.size(44.dp))
            }
            Spacer(Modifier.size(Spacing.related))
            Text("Your Monad wallet is ready", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                color = SemanticColors.PrimaryText, textAlign = TextAlign.Center)
            Spacer(Modifier.size(Spacing.compact))
            Text(wallet.address, style = MaterialTheme.typography.labelSmall, color = SemanticColors.PrimaryText,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                    .background(BrandColors.GreenMist).padding(Spacing.related))
            Spacer(Modifier.size(Spacing.compact))
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.related)) {
                TextAction(if (copied) "Copied ✓" else "Copy address") {
                    clipboard.setText(AnnotatedString(wallet.address)); copied = true
                }
                TextAction("View on Monad ↗") { uri.openUri(EXPLORER + wallet.address) }
            }
            Spacer(Modifier.size(Spacing.related))
            listOf(
                "Signed in with Google",
                "Self-custodial wallet, secured by Privy",
                "No seed phrase to write down or lose",
                "Monad testnet · chain 10143",
            ).forEach { TrustRow(it) }
            Spacer(Modifier.size(Spacing.section))
            PrimaryButton("Let's go", modifier = Modifier.fillMaxWidth(), onClick = onDone)
        }
    }
}

@Composable
private fun TrustRow(text: String) = Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(20.dp).clip(CircleShape).background(BrandColors.Green), contentAlignment = Alignment.Center) {
        Text("✓", color = BrandColors.White, style = MaterialTheme.typography.labelMedium)
    }
    Spacer(Modifier.size(Spacing.related))
    Text(text, style = MaterialTheme.typography.bodyMedium, color = SemanticColors.PrimaryText)
}

/** A green badge whose tick draws itself as [progress] goes 0 → 1. */
@Composable
private fun DrawnCheck(progress: Float, modifier: Modifier) = Canvas(modifier) {
    drawCircle(BrandColors.Green, alpha = (progress * 2).coerceAtMost(1f))
    drawCircle(BrandColors.White, style = Stroke(3.dp.toPx()), alpha = (progress * 2).coerceAtMost(1f))
    val tick = Path().apply {
        moveTo(size.width * 0.28f, size.height * 0.52f)
        lineTo(size.width * 0.44f, size.height * 0.68f)
        lineTo(size.width * 0.73f, size.height * 0.36f)
    }
    val part = Path()
    PathMeasure().apply { setPath(tick, false); getSegment(0f, length * progress, part, true) }
    drawPath(part, BrandColors.White, style = Stroke(4.dp.toPx(), cap = StrokeCap.Round))
}

/** Always-visible proof of who is signed in: the Monad wallet, its short address, and who secures it. */
@Composable
fun WalletChip(wallet: WalletAccount) {
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }
    Row(Modifier.clip(RoundedCornerShape(50)).background(BrandColors.GreenMist)
        .clickable { clipboard.setText(AnnotatedString(wallet.address)); copied = true }
        .padding(horizontal = Spacing.related, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(8.dp)) { drawCircle(BrandColors.Green, center = Offset(size.width / 2, size.height / 2)) }
        Spacer(Modifier.size(Spacing.compact))
        Text(if (copied) "Address copied ✓" else "Monad wallet ${shortAddress(wallet.address)}",
            style = MaterialTheme.typography.labelLarge, color = BrandColors.GreenDeep)
        Spacer(Modifier.size(Spacing.compact))
        Text(wallet.label, style = MaterialTheme.typography.labelMedium, color = SemanticColors.TertiaryText)
    }
}

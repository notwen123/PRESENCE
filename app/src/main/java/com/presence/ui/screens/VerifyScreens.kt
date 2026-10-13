package com.presence.ui.screens

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.presence.R
import com.presence.attest.Receipt
import com.presence.session.CheckStatus
import com.presence.session.Screen
import com.presence.session.reasonMessage
import com.presence.ui.components.Gap
import com.presence.ui.components.AttestationSeal
import com.presence.ui.components.MilestoneFlight
import com.presence.ui.components.SealState
import com.presence.ui.components.isMilestone
import com.presence.ui.components.Group
import com.presence.ui.components.Hairline
import com.presence.ui.components.KeyValue
import com.presence.ui.components.Page
import com.presence.ui.components.Pip
import com.presence.ui.components.PipMood
import com.presence.ui.components.PrimaryButton
import com.presence.ui.components.SessionComplete
import com.presence.ui.components.SecondaryButton
import com.presence.ui.components.SectionLabel
import com.presence.ui.components.StatusLabel
import com.presence.ui.components.TextAction
import com.presence.ui.components.TopBar
import com.presence.ui.components.formatToken
import com.presence.ui.components.minutesLabel
import com.presence.ui.theme.BrandColors
import com.presence.ui.theme.LocalReducedMotion
import com.presence.ui.theme.Motion
import com.presence.ui.theme.SemanticColors
import com.presence.ui.theme.Shapes
import com.presence.ui.theme.Spacing

// ---------- Verifying ----------

@Composable
fun VerifyingScreen(v: Screen.Verifying) = Page {
    TopBar()
    Gap(8)
    Pip(PipMood.Scientist, 120.dp, entrance = true)
    Gap(16)
    Text("Checking your session", style = MaterialTheme.typography.headlineLarge, color = SemanticColors.PrimaryText)
    Gap(8)
    Text("Each step turns green only when the server confirms it.",
        style = MaterialTheme.typography.bodyLarge, color = SemanticColors.SecondaryText)
    Gap(32)
    val current = v.checks.indexOfFirst { it.status == CheckStatus.PENDING }
    Column {
        v.checks.forEachIndexed { i, c ->
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { StatusMark(c.status, i == current) }
                Spacer(Modifier.width(Spacing.standard))
                Text(c.label, style = MaterialTheme.typography.bodyLarge,
                    color = if (c.status == CheckStatus.PENDING) SemanticColors.TertiaryText else SemanticColors.PrimaryText)
                Spacer(Modifier.weight(1f))
                statusNote(c.status)?.let {
                    Text(it, style = MaterialTheme.typography.labelMedium, color = SemanticColors.SecondaryText)
                }
            }
            if (i < v.checks.lastIndex) Hairline(Modifier.padding(start = 40.dp))
        }
    }
}

@Composable
private fun StatusMark(status: CheckStatus, current: Boolean) = when (status) {
    CheckStatus.PENDING ->
        if (current) CircularProgressIndicator(Modifier.size(16.dp), color = SemanticColors.PrimaryText, strokeWidth = 2.dp)
        else Box(Modifier.size(10.dp).border(1.5.dp, SemanticColors.Disabled, CircleShape))
    CheckStatus.PASSED -> CheckMark(BrandColors.Green, 20.dp)
    CheckStatus.FAILED -> Text("✕", color = SemanticColors.PrimaryText, style = MaterialTheme.typography.titleMedium)
    else -> Box(Modifier.size(10.dp).background(SemanticColors.Disabled, CircleShape))
}

private fun statusNote(status: CheckStatus): String? = when (status) {
    CheckStatus.DEV_BYPASS -> "Bypassed in development"
    CheckStatus.FLAGGED -> "Flagged · not enforced"
    CheckStatus.NOT_CONFIGURED -> "Not configured"
    CheckStatus.FAILED -> "Failed"
    else -> null
}

@Composable
private fun CheckMark(color: Color, size: androidx.compose.ui.unit.Dp) =
    Canvas(Modifier.size(size).semantics { contentDescription = "Passed" }) {
        val w = this.size.width
        val a = Offset(w * 0.2f, w * 0.52f)
        val b = Offset(w * 0.42f, w * 0.72f)
        val c = Offset(w * 0.8f, w * 0.3f)
        val stroke = w * 0.11f
        drawLine(color, a, b, stroke, StrokeCap.Round)
        drawLine(color, b, c, stroke, StrokeCap.Round)
    }

// ---------- Trust receipt ----------

@Composable
fun ReceiptScreen(r: Screen.Result, history: List<com.presence.attest.HistoryItem>, onDone: () -> Unit) = Box {
    val rc: Receipt = r.receipt
    val s = rc.settlement
    val ctx = LocalContext.current
    val uri = LocalUriHandler.current
    val reduced = LocalReducedMotion.current
    var showEvidence by rememberSaveable { mutableStateOf(false) }
    val anchored = s.status == "CONFIRMED"
    val paid = anchored && s.rewardToken > 0
    val symbol = s.rewardSymbol
    // The seal closes first (the server verified this session), then Pip celebrates once.
    val prefs = remember { ctx.getSharedPreferences("presence", android.content.Context.MODE_PRIVATE) }
    // Mode B: the streak moment comes first, once per verified session; the trust receipt follows.
    var complete by remember { mutableStateOf(prefs.getString("complete_seen", null) != rc.sessionId) }
    var sealed by remember { mutableStateOf(reduced) }
    LaunchedEffect(complete) { if (!reduced && !complete) { kotlinx.coroutines.delay(Motion.seal.toLong()); sealed = true } }
    // Mode C: a milestone gets its Flight once per milestone, never on an ordinary day.
    var flight by remember {
        mutableStateOf(isMilestone(rc.streak) && prefs.getInt("milestone_celebrated", 0) != rc.streak)
    }
    if (complete) {
        SessionComplete(rc.streak, history, reward = when {
            rc.reward.stake > 0 && paid -> "Stake returned · ${formatToken(rc.reward.stake)} $symbol"
            paid -> "+${formatToken(s.rewardToken)} $symbol"
            else -> null
        }) {
            val c = java.util.Calendar.getInstance() // today's feather has arrived here; Today won't replay it
            prefs.edit().putString("complete_seen", rc.sessionId)
                .putInt("feather_seen_day", c.get(java.util.Calendar.YEAR) * 1000 + c.get(java.util.Calendar.DAY_OF_YEAR)).apply()
            complete = false
        }
        return@Box
    }

    Page {
    TopBar { if (rc.mode == "development") StatusLabel("Development · ${s.network ?: "local"}") }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Gap(16)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            // The seal is a badge on Pip: it closes and draws its check first, then Pip celebrates once.
            Box(Modifier.size(176.dp)) {
                if (sealed) Pip(PipMood.Champion, 156.dp, Modifier.align(Alignment.TopCenter), entrance = true)
                else Pip(PipMood.Verified, 156.dp, Modifier.align(Alignment.TopCenter))
                Box(Modifier.align(Alignment.BottomEnd).size(64.dp).background(BrandColors.White, CircleShape)
                    .padding(6.dp)) { AttestationSeal(SealState.Verified, 52.dp) }
            }
            Gap(16)
            Text("You kept your commitment.", style = MaterialTheme.typography.headlineLarge,
                color = SemanticColors.PrimaryText, textAlign = TextAlign.Center)
            Gap(8)
            Text("${minutesLabel(rc.durationSeconds.toInt())} · ${rc.missionName}",
                style = MaterialTheme.typography.bodyLarge, color = SemanticColors.SecondaryText)
        }
        Gap(24)
        SectionLabel("Trust receipt")
        Gap(8)
        // Each step shows only what actually happened; nothing is marked done ahead of the chain.
        Group {
            ReceiptStep("Session submitted", "Done", done = true)
            Hairline()
            ReceiptStep("Evidence verified", "Verified by the server", done = true)
            Physical("Human touch", rc.checks["human_touch"])
            Physical("Phone stayed down", rc.checks["phone_down"])
            ReceiptStep("Recorded on Monad", when (s.status) {
                "CONFIRMED" -> "Confirmed"
                "NOT_CONFIGURED" -> "Not configured"
                "PENDING" -> "Pending"
                else -> "Failed"
            }, done = anchored, icon = R.drawable.monad_mark)
            Hairline()
            ReceiptStep(if (rc.reward.stake > 0) "Stake returned" else "Reward paid", when {
                rc.reward.stake > 0 && paid -> "${formatToken(rc.reward.stake)} $symbol" +
                    if (rc.reward.bonus > 0) " + ${formatToken(rc.reward.bonus)} bonus" else ""
                rc.reward.stake > 0 -> "Not returned yet"
                paid -> "+${formatToken(s.rewardToken)} $symbol"
                rc.reward.token > 0 || rc.reward.funding == "UNFUNDED" -> "Not paid (sponsor pool empty)"
                else -> "No token reward in this mode"
            }, done = paid)
        }
        if (!anchored && s.detail != null) {
            Gap(8)
            Text(s.detail, style = MaterialTheme.typography.bodyMedium, color = SemanticColors.SecondaryText)
        }
        if (rc.mode == "development") {
            Gap(12)
            Text("Development mode: the device check was bypassed and $symbol is a test token with no value.",
                style = MaterialTheme.typography.bodyMedium, color = SemanticColors.SecondaryText)
        }

        s.explorerUrl?.let { url ->
            Gap(24)
            Row(Modifier.fillMaxWidth().border(1.dp, SemanticColors.Border, Shapes.standard).padding(Spacing.standard),
                verticalAlignment = Alignment.CenterVertically) {
                Qr(url, 88)
                Spacer(Modifier.width(Spacing.standard))
                Column(Modifier.weight(1f)) {
                    Text("On-chain proof", style = MaterialTheme.typography.titleMedium, color = SemanticColors.PrimaryText)
                    Text("Anyone can check this transaction.", style = MaterialTheme.typography.bodyMedium,
                        color = SemanticColors.SecondaryText)
                    TextAction("View on Explorer ↗") { uri.openUri(url) }
                }
            }
        }

        Gap(8)
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.compact)) {
            TextAction(if (showEvidence) "Hide evidence" else "Show evidence") { showEvidence = !showEvidence }
            TextAction("Share") { shareReceipt(ctx, rc) }
        }
        AnimatedVisibility(showEvidence, enter = fadeIn(tween(Motion.control))) { Evidence(r) }
        Gap(16)
    }
    PrimaryButton("Done", onClick = onDone)
    Gap(16)
    }
    if (flight) MilestoneFlight(rc.streak, onDone = {
        prefs.edit().putInt("milestone_celebrated", rc.streak).apply(); flight = false
    }, onShare = { shareReceipt(ctx, rc) })
}

/** A receipt step: green check only when it really happened. */
@Composable
private fun ReceiptStep(label: String, value: String, done: Boolean, icon: Int? = null) =
    KeyValue(label, (if (done) "✓  " else "") + value, if (done) BrandColors.Green else SemanticColors.PrimaryText, icon = icon)

/** One physical-evidence row; hidden when the mission doesn't require it. */
@Composable
private fun Physical(label: String, status: String?) {
    when (status) {
        "PASSED" -> KeyValue(label, "Verified", BrandColors.Green)
        "FLAGGED" -> KeyValue(label, "Flagged · not enforced in dev")
        else -> return
    }
    Hairline()
}

/** QR of the explorer link: ink on white. */
@Composable
private fun Qr(text: String, sizeDp: Int) {
    val bits = remember(text) { QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0) }
    Canvas(Modifier.size(sizeDp.dp).semantics { contentDescription = "QR code of the on-chain proof" }) {
        val cell = size.width / bits.width
        for (y in 0 until bits.height) for (x in 0 until bits.width) if (bits[x, y])
            drawRect(BrandColors.Ink, Offset(x * cell, y * cell), Size(cell + 0.5f, cell + 0.5f))
    }
}

private fun shareReceipt(ctx: Context, rc: Receipt) {
    val text = buildString {
        append("I went facedown on FACEDOWN: ${rc.missionName}, ${minutesLabel(rc.durationSeconds.toInt())}, verified.\n")
        rc.settlement.explorerUrl?.let { append("On-chain proof: $it\n") }
        if (rc.mode == "development") append("(development mode, test network)\n")
    }
    ctx.startActivity(Intent.createChooser(
        Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), "Share"))
}

@Composable
private fun Evidence(r: Screen.Result) {
    val rc = r.receipt
    Column(
        Modifier.fillMaxWidth().background(SemanticColors.Subtle, Shapes.standard).padding(Spacing.standard),
        verticalArrangement = Arrangement.spacedBy(Spacing.related),
    ) {
        Mono("Evidence root", rc.evidenceRoot)
        Mono("Session", rc.sessionId)
        rc.checks.forEach { (k, v) -> Mono(k.replace('_', ' ').replaceFirstChar { it.uppercase() }, v) }
        r.checkpointHashes.forEachIndexed { i, h -> Mono("Check-in ${i + 1}", h) }
        rc.settlement.signature?.let { Mono("Transaction", it) }
        rc.settlement.rewardMint?.let { Mono("Token mint", it) }
    }
}

@Composable
private fun Mono(label: String, value: String) = Column {
    Text(label, style = MaterialTheme.typography.labelMedium, color = SemanticColors.TertiaryText)
    Text(value, style = MaterialTheme.typography.labelSmall, color = SemanticColors.PrimaryText)
}

// ---------- Not verified ----------

@Composable
fun FailedScreen(f: Screen.Failed, onRetry: () -> Unit, onHome: () -> Unit) = Page {
    var details by rememberSaveable { mutableStateOf(false) }
    TopBar()
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Gap(32)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Pip(PipMood.Encouraging, 160.dp, entrance = true)
            Gap(16)
            Text("Session not verified", style = MaterialTheme.typography.headlineLarge,
                color = SemanticColors.PrimaryText, textAlign = TextAlign.Center)
            Gap(8)
            Text(reasonMessage(f.reason), style = MaterialTheme.typography.bodyLarge,
                color = SemanticColors.SecondaryText, textAlign = TextAlign.Center)
        }
        f.stakeLockedUntil?.let {
            Gap(32)
            SectionLabel("Your stake")
            Gap(8)
            Text("Cool-down: your stake is locked, not lost: $it. Then it comes back to your wallet. " +
                "Quit again within a month and the next lock is longer.",
                style = MaterialTheme.typography.bodyLarge, color = SemanticColors.PrimaryText)
        }
        f.stakeReturned?.let {
            Gap(32)
            SectionLabel("Your stake")
            Gap(8)
            Text("No-Loss: your $it is on its way back to your wallet. No reward this time, and the streak restarts.",
                style = MaterialTheme.typography.bodyLarge, color = SemanticColors.PrimaryText)
        }
        f.stakeLost?.let {
            Gap(32)
            SectionLabel("Your stake")
            Gap(8)
            Text("Your $it stake went to the pool. It funds the bonus for people who finish. " +
                "Next time is a fresh start.",
                style = MaterialTheme.typography.bodyLarge, color = SemanticColors.PrimaryText)
        }
        Gap(16)
        TextAction(if (details) "Hide technical details" else "Technical details") { details = !details }
        if (details) {
            Column(Modifier.fillMaxWidth().background(SemanticColors.Subtle, Shapes.standard).padding(Spacing.standard)) {
                Mono("Reason", f.reason)
                if (f.detail.isNotBlank()) { Gap(8); Mono("Detail", f.detail) }
            }
        }
    }
    PrimaryButton("Try again", onClick = onRetry)
    Gap(8)
    SecondaryButton("Back to Today", onClick = onHome)
    Gap(16)
}

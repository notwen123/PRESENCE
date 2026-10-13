package com.presence.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.presence.BuildConfig
import com.presence.R
import com.presence.attest.HistoryItem
import com.presence.attest.MissionPolicy
import com.presence.attest.Profile
import com.presence.session.UiState
import com.presence.ui.components.ClockInBottomNavigation
import com.presence.ui.components.DurationSelector
import com.presence.ui.components.EnvironmentLabel
import com.presence.ui.components.Gap
import com.presence.ui.components.Group
import com.presence.ui.components.Hairline
import com.presence.ui.components.KeyValue
import com.presence.ui.components.MessageState
import com.presence.ui.components.Pip
import com.presence.ui.components.PipMood
import com.presence.ui.components.PrimaryButton
import com.presence.ui.components.SecondaryButton
import com.presence.ui.components.SectionLabel
import com.presence.ui.components.Tab
import com.presence.ui.components.TopBar
import com.presence.ui.components.formatToken
import com.presence.ui.components.minutesLabel
import com.presence.ui.components.shortAddress
import com.presence.ui.theme.BrandColors
import com.presence.ui.theme.Motion
import com.presence.ui.theme.SemanticColors
import com.presence.ui.theme.Spacing
import java.text.DateFormat
import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.semantics.Role
import com.presence.urge.Urges
import androidx.compose.material3.OutlinedTextField
import com.presence.PresenceMinutes
import com.presence.ui.components.ProgressRing
import androidx.compose.runtime.LaunchedEffect
import android.app.TimePickerDialog
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.presence.FocusAppointment
import com.presence.ui.components.TextAction
import com.presence.ui.theme.Shapes
import java.util.Date

@Composable
fun HomeScreen(
    s: UiState,
    onConnect: () -> Unit,
    onSelect: (MissionPolicy) -> Unit,
    onOpen: (MissionPolicy) -> Unit,
    onDisconnect: () -> Unit,
    onRetry: () -> Unit,
    onBuyFreeze: () -> Unit = {},
    onCalibrate: () -> Unit = {},
    onCreateSquad: (String) -> Unit = {},
    onJoinSquad: (String) -> Unit = {},
) {
    var tab by rememberSaveable { mutableStateOf(Tab.Today) }
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(tab) { if (shown) onRetry() else shown = true } // fresh squad, pools and live count per tab
    Column(Modifier.fillMaxSize().background(SemanticColors.Canvas)) {
        AnimatedContent(tab, Modifier.weight(1f), label = "tab",
            transitionSpec = { fadeIn(tween(Motion.screen)) togetherWith fadeOut(tween(Motion.micro)) }) { t ->
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = Spacing.gutter)) {
                TopBar {
                    EnvironmentLabel(s.health?.mode, s.health?.network)
                }
                when (t) {
                    Tab.Today -> Today(s, onConnect, onSelect, onOpen, onRetry)
                    Tab.Progress -> Progress(s, onConnect = { tab = Tab.Profile }, onBuyFreeze = onBuyFreeze,
                        onCreateSquad = onCreateSquad, onJoinSquad = onJoinSquad)
                    Tab.Profile -> ProfileTab(s, onConnect, onDisconnect, onRetry, onCalibrate)
                }
            }
        }
        ClockInBottomNavigation(tab) { tab = it }
    }
}

// ---------- Today ----------

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Today(
    s: UiState, onConnect: () -> Unit, onSelect: (MissionPolicy) -> Unit, onOpen: (MissionPolicy) -> Unit,
    onRetry: () -> Unit,
) {
    val m = s.mission
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                SectionLabel(greeting())
                Gap(4)
                Text("Put it down. Prove it.", style = MaterialTheme.typography.headlineLarge, color = SemanticColors.PrimaryText)
            }
            if (s.backendError == null && m != null)
                Pip(if (s.profile?.provedToday == true) PipMood.Proud else PipMood.Ready, 128.dp, entrance = true, live = true)
        }
        Text("Phone face-down for a set time. Finish, and a real, human session is verified on Solana.",
            style = MaterialTheme.typography.bodyLarge, color = SemanticColors.SecondaryText)
        if (s.facedownNow > 0) {
            Gap(12)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(BrandColors.Green, CircleShape))
                Spacer(Modifier.size(Spacing.related))
                Text(if (s.facedownNow == 1) "1 person is facedown right now" else "${s.facedownNow} people are facedown right now",
                    style = MaterialTheme.typography.labelMedium, color = SemanticColors.PrimaryText)
            }
        }

        Gap(20)
        PresenceRing()
        s.profile?.takeIf { it.streak > 0 }?.let {
            Gap(24)
            StreakLine(it)
        }

        if (s.backendError != null) {
            Gap(48)
            MessageState(PipMood.Retry, "Can't connect right now", s.backendError, action = "Try again", onAction = onRetry)
        } else if (m == null) {
            Gap(48)
            MessageState(PipMood.Thinking, "Loading sessions", "One moment.")
        } else {
            Gap(32)
            SectionLabel("Your next commitment")
            Gap(12)
            // You choose the length and whether to stake (self-selected goals cut phone use 73% more than
            // assigned ones: Sachdeva et al., Soc Sci Med 2026). Free is the default way in.
            val durations = s.missions.map { it.durationSeconds }.distinct()
            if (durations.size > 1) {
                DurationSelector(durations, m.durationSeconds, label = ::minutesLabel) { d ->
                    onSelect(s.missions.firstOrNull { it.durationSeconds == d && staked(it) == staked(m) }
                        ?: s.missions.first { it.durationSeconds == d })
                }
                Gap(8)
            }
            val variants = s.missions.filter { it.durationSeconds == m.durationSeconds }
            if (variants.size > 1) {
                DurationSelector(variants, m, label = {
                    when {
                        it.stake.noLoss -> "No-Loss"
                        staked(it) -> "Stake ${formatToken(it.stake.token)}"
                        else -> "Free"
                    }
                }, onSelect = onSelect)
                Gap(16)
            }
            Commitment(m, s)
            Gap(16)
            Appointment()
        }
        Gap(24)
    }

    if (m != null && s.backendError == null) {
        val used = s.profile?.claimsToday?.get(m.missionId) ?: 0
        val full = used >= m.maxClaimsPerDay
        when {
            s.wallet == null -> {
                PrimaryButton("Connect wallet", loading = s.busy, onClick = onConnect)
                Text("Uses your Solana wallet app. FACEDOWN never sees your keys.",
                    style = MaterialTheme.typography.bodyMedium, color = SemanticColors.TertiaryText,
                    modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.related))
            }
            full -> {
                PrimaryButton("Done for today", enabled = false) {}
                Gap(16)
            }
            else -> {
                PrimaryButton("Go facedown  →", enabled = !s.busy) { onOpen(m) }
                Gap(16)
            }
        }
    }
}

@Composable
private fun Commitment(m: MissionPolicy, s: UiState) {
    val symbol = m.reward.symbol
    val used = s.profile?.claimsToday?.get(m.missionId) ?: 0
    Text(m.name, style = MaterialTheme.typography.headlineSmall, color = SemanticColors.PrimaryText)
    Gap(4)
    Text("${minutesLabel(m.durationSeconds)}, phone down, ${m.requiredCheckpoints} quick check-ins",
        style = MaterialTheme.typography.bodyMedium, color = SemanticColors.SecondaryText)
    Gap(16)
    Group {
        if (!staked(m)) {
            KeyValue("Your stake", "None")
            Hairline()
            KeyValue("If you finish", if (m.reward.token > 0) "+${formatToken(m.reward.token)} $symbol" else "A verified session")
            s.pools[m.missionId]?.let {
                Hairline()
                KeyValue("Sponsor pool now", "${formatToken(it.remaining)} $symbol")
            }
            Hairline()
        }
        if (m.stake.noLoss) {
            KeyValue("Your stake", "${formatToken(m.stake.token)} $symbol, locked")
            Hairline()
            KeyValue("If you finish", "Stake back +${formatToken(m.reward.token)} $symbol")
            Hairline()
            KeyValue("If you stop early", "Stake back, no reward")
            s.pools[m.missionId]?.let {
                Hairline()
                KeyValue("Sponsor pool now", "${formatToken(it.remaining)} $symbol")
            }
            Hairline()
        } else if (m.stake.token > 0) {
            KeyValue("Your stake", "${formatToken(m.stake.token)} $symbol")
            Hairline()
            KeyValue("If you finish", "Stake back + up to ${(m.stake.bonusRate * 100).toInt()}%")
            if (m.stake.cooldown) {
                Hairline()
                KeyValue("If you quit", "Locked 7 days, not lost")
            }
            s.pools[m.missionId]?.let {
                Hairline()
                KeyValue("Bonus pool now", "${formatToken(it.remaining)} $symbol")
            }
            Hairline()
        }
        KeyValue("Today", if (s.wallet == null) "${m.maxClaimsPerDay} per day" else "$used of ${m.maxClaimsPerDay} used")
    }
    Gap(8)
    if (!staked(m) || m.stake.noLoss) {
        Text("Paid by a sponsor, not by other users. Nothing of yours is at risk.",
            style = MaterialTheme.typography.bodyMedium, color = SemanticColors.TertiaryText)
    } else {
        Text("The bonus is paid by sponsors and capped by what's in the pool. Nobody wins your stake if you quit.",
            style = MaterialTheme.typography.bodyMedium, color = SemanticColors.TertiaryText)
    }
}

private fun staked(m: MissionPolicy) = m.stake.token > 0

private fun greeting(): String = when (java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    else -> "Good evening"
}

/** Squad: one shared streak. If anyone misses a day it breaks for everyone. */
@Composable
private fun SquadSection(squad: com.presence.attest.Squad?, onCreate: (String) -> Unit, onJoin: (String) -> Unit) {
    val ctx = LocalContext.current
    SectionLabel("Squad")
    Gap(8)
    if (squad == null) {
        var code by remember { mutableStateOf("") }
        Text("3 to 8 friends, one shared streak. If anyone misses a day, it breaks for everyone.",
            style = MaterialTheme.typography.bodyMedium, color = SemanticColors.SecondaryText)
        Gap(8)
        PrimaryButton("Start a squad") { onCreate("My squad") }
        Gap(8)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(code, { code = it.uppercase().take(6) }, Modifier.weight(1f), singleLine = true,
                label = { Text("Invite code") })
            Spacer(Modifier.size(Spacing.related))
            TextAction("Join") { if (code.length == 6) onJoin(code) }
        }
        return
    }
    Group {
        KeyValue("Squad streak", if (squad.streak == 1) "1 day" else "${squad.streak} days", BrandColors.Green)
        Hairline()
        KeyValue("Done today", "${squad.doneToday} of ${squad.size}")
        squad.members.forEach { m ->
            Hairline()
            KeyValue(if (m.you) "You" else shortAddress(m.wallet), when {
                m.facedownNow -> "Facedown now"
                m.doneToday -> "Done today"
                else -> "Not yet"
            }, if (m.facedownNow || m.doneToday) BrandColors.Green else SemanticColors.TertiaryText)
        }
    }
    Gap(8)
    TextAction("Invite with code ${squad.code}") {
        ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,
            "Join my FACEDOWN squad: code ${squad.code}. One shared streak, so don't break it."), "Invite"))
    }
}

/** Today's Presence Minutes against the daily goal; opt-in, because it samples the sensor all day. */
@Composable
private fun PresenceRing() {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(PresenceMinutes.enabled(ctx)) }
    var minutes by remember { mutableStateOf(PresenceMinutes.today(ctx)) }
    LaunchedEffect(on) { while (on) { minutes = PresenceMinutes.today(ctx); kotlinx.coroutines.delay(30_000) } }
    Row(Modifier.fillMaxWidth().border(1.dp, SemanticColors.Border, Shapes.standard).padding(Spacing.standard),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            ProgressRing((minutes / PresenceMinutes.GOAL_MINUTES.toFloat()).coerceIn(0f, 1f), Modifier.size(56.dp))
            Text("${minutes * 100 / PresenceMinutes.GOAL_MINUTES}%", style = MaterialTheme.typography.labelMedium,
                color = SemanticColors.PrimaryText)
        }
        Spacer(Modifier.size(Spacing.standard))
        Column(Modifier.weight(1f)) {
            Text("Facedown today: ${minutes / 60} h ${minutes % 60} min", style = MaterialTheme.typography.titleMedium,
                color = SemanticColors.PrimaryText)
            Text(if (on) "Goal ${PresenceMinutes.GOAL_MINUTES / 60} h. Counted whenever the phone lies face-down and still."
                else "Count face-down time all day, even without a session.",
                style = MaterialTheme.typography.bodyMedium, color = SemanticColors.SecondaryText)
        }
        if (!on) TextAction("Turn on") { PresenceMinutes.setEnabled(ctx, true); on = true }
    }
}

/** Pick apps to pause before; turning the service on happens in Android's Accessibility settings. */
@Composable
private fun UrgeCatcherSettings() {
    val ctx = LocalContext.current
    val apps = remember { Urges.installed(ctx) }
    var watched by remember { mutableStateOf(Urges.watched(ctx)) }
    var on by remember { mutableStateOf(Urges.enabled(ctx)) }
    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycle) { // re-check after the user comes back from Settings
        val o = androidx.lifecycle.LifecycleEventObserver { _, e ->
            if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) on = Urges.enabled(ctx)
        }
        lifecycle.lifecycle.addObserver(o)
        onDispose { lifecycle.lifecycle.removeObserver(o) }
    }
    Text("Before an app you pick opens, FACEDOWN asks: go facedown for 10 minutes instead? It only sees which " +
        "app is opening, never what's on screen.", style = MaterialTheme.typography.bodyMedium, color = SemanticColors.SecondaryText)
    Gap(8)
    Group {
        if (apps.isEmpty()) KeyValue("Apps", "None of the usual apps installed")
        apps.entries.forEachIndexed { i, (pkg, name) ->
            if (i > 0) Hairline()
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(pkg in watched, role = Role.Switch) { Urges.setWatched(ctx, pkg, it); watched = Urges.watched(ctx) },
                verticalAlignment = Alignment.CenterVertically) {
                Text(name, style = MaterialTheme.typography.bodyLarge, color = SemanticColors.PrimaryText, modifier = Modifier.weight(1f))
                Switch(checked = pkg in watched, onCheckedChange = null,
                    colors = SwitchDefaults.colors(checkedTrackColor = BrandColors.Green))
            }
        }
    }
    Gap(12)
    if (on) Text("Urge Catcher is on.", style = MaterialTheme.typography.labelMedium, color = BrandColors.Green)
    else SecondaryButton("Turn on in Accessibility settings") {
        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/** Book a daily time; the phone reminds you then. */
@Composable
private fun Appointment() {
    val ctx = LocalContext.current
    var minutes by remember { mutableStateOf(FocusAppointment.get(ctx)) }
    fun pick() = TimePickerDialog(ctx, { _, h, min ->
        FocusAppointment.set(ctx, h * 60 + min); minutes = h * 60 + min
    }, (minutes ?: 21 * 60) / 60, (minutes ?: 0) % 60, android.text.format.DateFormat.is24HourFormat(ctx)).show()
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { pick() }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).border(1.dp, SemanticColors.Border, Shapes.standard)
            .clickable {
                if (Build.VERSION.SDK_INT >= 33) notify.launch(Manifest.permission.POST_NOTIFICATIONS) else pick()
            }
            .padding(horizontal = Spacing.standard),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = Spacing.related)) {
            Text("Daily focus appointment", style = MaterialTheme.typography.titleMedium, color = SemanticColors.PrimaryText)
            Text(minutes?.let { "Every day at ${FocusAppointment.label(it)}. We'll remind you." }
                ?: "Pick a time. People keep appointments far better than promises.",
                style = MaterialTheme.typography.bodyMedium, color = SemanticColors.SecondaryText)
        }
        if (minutes != null) TextAction("Off") { FocusAppointment.set(ctx, null); minutes = null }
        else Text("Book", style = MaterialTheme.typography.labelLarge, color = BrandColors.Green)
    }
}

@Composable
private fun StreakLine(p: Profile) = Row(verticalAlignment = Alignment.CenterVertically) {
    Text(if (p.streak == 1) "1-day streak" else "${p.streak}-day streak",
        style = MaterialTheme.typography.titleMedium, color = SemanticColors.PrimaryText)
    Spacer(Modifier.weight(1f))
    WeekDots(p)
}

/**
 * The last seven days, derived from the server's streak: a streak is by definition consecutive
 * days ending today (if done today) or yesterday.
 */
@Composable
private fun WeekDots(p: Profile) = Row(horizontalArrangement = Arrangement.spacedBy(6.dp),
    modifier = Modifier.semantics { contentDescription = "Days kept this week, from the streak" }) {
    for (daysAgo in 6 downTo 0) {
        val offset = if (p.provedToday) daysAgo else daysAgo - 1
        val kept = offset in 0 until p.streak
        Box(Modifier.size(10.dp).then(
            if (kept) Modifier.background(BrandColors.Green, CircleShape)
            else Modifier.border(1.dp, SemanticColors.Border, CircleShape)))
    }
}

// ---------- Progress ----------

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Progress(s: UiState, onConnect: () -> Unit,
                                                                    onBuyFreeze: () -> Unit, onCreateSquad: (String) -> Unit,
                                                                    onJoinSquad: (String) -> Unit) {
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Gap(16)
        Text("Progress", style = MaterialTheme.typography.headlineLarge, color = SemanticColors.PrimaryText)
        Gap(24)
        val p = s.profile
        if (s.wallet == null || p == null) {
            Gap(32)
            MessageState(PipMood.Resting, "Nothing here yet",
                "Connect your wallet on Today and your verified sessions will collect here.")
            return@Column
        }
        Row(Modifier.fillMaxWidth()) {
            Figure("Current streak", "${p.streak}", Modifier.weight(1f))
            Figure("Best streak", "${p.bestStreak}", Modifier.weight(1f))
            Figure("Verified", "${p.verifiedCount}", Modifier.weight(1f))
        }
        Gap(24)
        SquadSection(s.squad, onCreateSquad, onJoinSquad)
        Gap(24)
        Group {
            KeyValue("Streak freezes", "${p.streakFreezes}")
        }
        Gap(8)
        Text("A freeze keeps your streak through one missed day. It costs 2 ${s.missions.firstOrNull()?.reward?.symbol ?: "SKR"}; " +
            "half is burned on Solana.", style = MaterialTheme.typography.bodyMedium, color = SemanticColors.TertiaryText)
        Gap(8)
        SecondaryButton(if (s.busy) "Waiting for your wallet…" else "Buy a streak freeze", onClick = onBuyFreeze)
        Gap(32)
        SectionLabel("Recent sessions")
        Gap(4)
        if (s.history.isEmpty()) {
            Gap(32)
            MessageState(PipMood.Resting, "No sessions yet", "Finished and interrupted sessions appear here.")
        } else {
            s.history.forEach { HistoryRow(it); Hairline() }
        }
        Gap(24)
    }
}

@Composable
private fun Figure(label: String, value: String, modifier: Modifier) = Column(modifier) {
    Text(value, style = MaterialTheme.typography.displayMedium.copy(fontSize = MaterialTheme.typography.headlineLarge.fontSize),
        color = SemanticColors.PrimaryText)
    Text(label, style = MaterialTheme.typography.bodyMedium, color = SemanticColors.SecondaryText)
}

@Composable
private fun HistoryRow(h: HistoryItem) {
    val uri = LocalUriHandler.current
    val verified = h.state == "SETTLED" || h.state == "VERIFIED"
    val symbol = h.symbol ?: ""
    val detail = when {
        h.state == "SETTLED" && h.paid > 0 -> "Verified · ${formatToken(h.paid)} $symbol received"
        h.state == "SETTLED" -> "Verified · recorded on Solana"
        h.state == "VERIFIED" -> "Verified · not recorded on-chain"
        h.stakeForfeited > 0 -> "Interrupted · ${formatToken(h.stakeForfeited)} stake to the pool"
        h.stakeReturned > 0 -> "Interrupted · ${formatToken(h.stakeReturned)} stake returned"
        h.stakeLocked > 0 -> "Interrupted · ${formatToken(h.stakeLocked)} stake locked" + (h.unlockAt?.let {
            " until " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date((it * 1000).toLong()))
        } ?: "")
        else -> "Not verified"
    }
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp)
            .then(h.explorerUrl?.let { url -> Modifier.clickable { uri.openUri(url) } } ?: Modifier)
            .padding(vertical = Spacing.related),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).background(if (verified) BrandColors.Green else SemanticColors.Disabled, CircleShape))
        Spacer(Modifier.size(Spacing.related))
        Column(Modifier.weight(1f)) {
            Text(h.missionName, style = MaterialTheme.typography.titleMedium, color = SemanticColors.PrimaryText)
            Text(detail, style = MaterialTheme.typography.bodyMedium,
                color = if (verified) SemanticColors.SecondaryText else SemanticColors.TertiaryText)
        }
        Column(horizontalAlignment = Alignment.End) {
            h.startedAt?.let {
                Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date((it * 1000).toLong())),
                    style = MaterialTheme.typography.labelMedium, color = SemanticColors.TertiaryText)
            }
            h.explorerUrl?.let {
                Text("View ↗", style = MaterialTheme.typography.labelMedium, color = SemanticColors.PrimaryText,
                    modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

// ---------- Profile ----------

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ProfileTab(
    s: UiState, onConnect: () -> Unit, onDisconnect: () -> Unit, onRetry: () -> Unit, onCalibrate: () -> Unit,
) = Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
    Gap(16)
    Text("Profile", style = MaterialTheme.typography.headlineLarge, color = SemanticColors.PrimaryText)
    Gap(24)

    SectionLabel("Wallet")
    Gap(8)
    Group {
        KeyValue("Status", if (s.wallet != null) "Connected" else "Not connected")
        s.wallet?.let {
            Hairline()
            KeyValue(it.label.ifBlank { "Account" }, shortAddress(it.address), mono = true)
        }
    }
    Gap(12)
    if (s.wallet == null) PrimaryButton("Connect wallet", loading = s.busy, onClick = onConnect)
    else SecondaryButton("Disconnect wallet", onClick = onDisconnect)

    Gap(32)
    SectionLabel("Urge Catcher")
    Gap(8)
    UrgeCatcherSettings()

    Gap(32)
    SectionLabel("Your knock")
    Gap(8)
    Group {
        KeyValue("Check-in threshold", s.knockThreshold?.let { "%.2f m/s²".format(it) } ?: "Default (not calibrated)")
    }
    Gap(12)
    SecondaryButton(if (s.knockThreshold == null) "Calibrate my knock" else "Recalibrate", onClick = onCalibrate)

    Gap(32)
    SectionLabel("Environment")
    Gap(8)
    val h = s.health
    Group {
        KeyValue("Server", when {
            h != null -> "Connected"
            s.backendError != null -> "Unreachable"
            else -> "Checking…"
        })
        h?.let {
            Hairline()
            KeyValue("Mode", it.mode.replaceFirstChar { c -> c.uppercase() })
            Hairline()
            KeyValue("Network", it.network.replaceFirstChar { c -> c.uppercase() }, icon = R.drawable.solana_mark)
            Hairline()
            KeyValue("Seeker check", if (it.mode == "development") "Bypassed (development)" else "Required")
        }
    }
    if (s.backendError != null) SecondaryButton("Try again", Modifier.padding(top = 12.dp), onClick = onRetry)
    if (h != null && h.network != "mainnet") {
        Gap(8)
        Text("Off mainnet, stakes use a test token (${s.missions.firstOrNull()?.reward?.symbol ?: "SKR-TEST"}) with no value.",
            style = MaterialTheme.typography.bodyMedium, color = SemanticColors.TertiaryText)
    }

    Gap(32)
    SectionLabel("Privacy")
    Gap(8)
    Text("During a session FACEDOWN records whether the app stayed on screen, when you answered each check-in, " +
        "and two motion numbers per check-in from the accelerometer: how much of the time the phone lay still and face-down, and " +
        "how much your knock or tap moved it. Raw sensor data never leaves the phone. No location, contacts, microphone or " +
        "camera, and nothing in the background. " +
        "Only a fingerprint (hash) of the session is written to Solana.",
        style = MaterialTheme.typography.bodyMedium, color = SemanticColors.SecondaryText)
    Gap(8)
    Text("It proves this phone stayed in the session. It can't prove what you were thinking about, " +
        "or that you didn't use another device.",
        style = MaterialTheme.typography.bodyMedium, color = SemanticColors.SecondaryText)

    Gap(32)
    SectionLabel("About")
    Gap(8)
    Group {
        KeyValue("Version", BuildConfig.VERSION_NAME)
        Hairline()
        KeyValue("Verification", "PRESENCE · ATTEST")
    }
    Gap(24)
}

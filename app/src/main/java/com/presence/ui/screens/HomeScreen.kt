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
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextAlign
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
import com.presence.ui.components.PipSays
import com.presence.ui.components.FlameCount
import com.presence.ui.components.feather
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import com.presence.ui.components.StreakFlame
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
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.height
import com.presence.ui.components.FeatherTrail
import com.presence.ui.components.MILESTONES
import com.presence.ui.components.MilestoneFlight
import com.presence.ui.components.StreakHero
import com.presence.ui.components.FeatherWeek
import com.presence.ui.components.MilestoneJourney
import com.presence.ui.components.DayState
import com.presence.ui.components.weekOf
import com.presence.ui.components.nextMilestone
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import com.presence.ui.theme.Haptics
import androidx.compose.ui.unit.sp
import com.presence.ui.theme.LocalReducedMotion
import com.presence.ui.theme.MotionSettings
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
    val reduced = LocalReducedMotion.current
    Column(Modifier.fillMaxSize().background(SemanticColors.Canvas)) {
        // Tabs slide in the direction you moved along the bar; a short crossfade under reduced motion.
        AnimatedContent(tab, Modifier.weight(1f), label = "tab", transitionSpec = {
            if (reduced) fadeIn(tween(Motion.micro)) togetherWith fadeOut(tween(Motion.micro))
            else {
                val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                (slideInHorizontally(tween(Motion.screen, easing = Motion.Standard)) { it / 12 * dir } + fadeIn(tween(Motion.screen))) togetherWith
                    (slideOutHorizontally(tween(Motion.micro, easing = Motion.Exit)) { -it / 12 * dir } + fadeOut(tween(Motion.micro)))
            }
        }) { t ->
            Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = Spacing.gutter)) {
                TopBar {
                    EnvironmentLabel(s.health?.mode, s.health?.network)
                }
                when (t) {
                    Tab.Today -> Today(s, onConnect, onOpen, onRetry, goFocus = { tab = Tab.Focus }, goSquad = { tab = Tab.Squad })
                    Tab.Focus -> Focus(s, onConnect, onSelect, onOpen, onRetry)
                    Tab.Squad -> SquadTab(s, onCreateSquad, onJoinSquad, goProfile = { tab = Tab.Profile })
                    Tab.Progress -> Progress(s, onConnect = { tab = Tab.Profile }, onBuyFreeze = onBuyFreeze)
                    Tab.Profile -> ProfileTab(s, onConnect, onDisconnect, onRetry, onCalibrate)
                }
            }
        }
        ClockInBottomNavigation(tab) { tab = it }
    }
}

/** The last seven local days, oldest first: true where a session was verified (from the server's own receipts). */
private fun keptDays(history: List<HistoryItem>): List<Boolean> {
    val cal = java.util.Calendar.getInstance()
    fun key(ms: Long) = cal.apply { timeInMillis = ms }.let { it.get(java.util.Calendar.YEAR) * 1000 + it.get(java.util.Calendar.DAY_OF_YEAR) }
    val kept = history.filter { it.state == "SETTLED" || it.state == "VERIFIED" }
        .mapNotNull { h -> h.startedAt?.let { key((it * 1000).toLong()) } }.toSet()
    val now = System.currentTimeMillis()
    return (6 downTo 0).map { d -> key(now - d * 86_400_000L) in kept }
}

/**
 * Today's feather arrives once: the first time Today is shown after today's first verified session.
 * Remembered per day, so it never replays on every visit.
 */
@Composable
private fun arrivingToday(days: List<Boolean>): Pair<Int?, () -> Unit> {
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("presence", android.content.Context.MODE_PRIVATE) }
    val today = remember { java.util.Calendar.getInstance().let { it.get(java.util.Calendar.YEAR) * 1000 + it.get(java.util.Calendar.DAY_OF_YEAR) } }
    val pending = days.lastOrNull() == true && prefs.getInt("feather_seen_day", -1) != today
    return (if (pending) 6 else null) to { prefs.edit().putInt("feather_seen_day", today).apply() }
}

// ---------- Today ----------

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Today(
    s: UiState, onConnect: () -> Unit, onOpen: (MissionPolicy) -> Unit, onRetry: () -> Unit,
    goFocus: () -> Unit, goSquad: () -> Unit,
) {
    val m = s.mission
    val p = s.profile
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        SectionLabel(greeting())
        Gap(8)
        // Pip says where today stands; the pose follows the real state.
        val streak = p?.streak ?: 0
        if (s.backendError == null && m != null) PipSays(when {
            p?.provedToday == true -> PipMood.Champion; streak > 0 -> PipMood.Coach; else -> PipMood.Ready
        }, when {
            p?.provedToday == true -> "Nicely done today! Your flame is lit."
            streak > 0 -> "Your $streak-day streak needs today. Put the phone down with me!"
            else -> "Put it down. Prove it. I'll keep count."
        }, pipSize = 104.dp)
        else Text("Put it down.\nProve it.", style = MaterialTheme.typography.headlineLarge, color = SemanticColors.PrimaryText)
        if (s.backendError != null) {
            Gap(24)
            MessageState(PipMood.Mechanic, "Can't connect right now", s.backendError, action = "Try again", onAction = onRetry)
            return@Column
        }
        if (m == null) { Gap(16); SkeletonCard(); Gap(12); SkeletonCard(); return@Column }
        Gap(16)
        // Mode 1: compact streak with the feather week. Today's feather arrives once, after a real verified session.
        if (p != null) {
            val (arriving, onArrived) = arrivingToday(keptDays(s.history))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FlameCount(p.streak, lit = p.provedToday)
                Spacer(Modifier.size(Spacing.related))
                Column {
                    Text("day streak", style = MaterialTheme.typography.titleMedium, color = SemanticColors.PrimaryText)
                    Text(nextMilestone(p.streak)?.let { "${it - p.streak} to the $it-day milestone" } ?: "Every milestone reached",
                        style = MaterialTheme.typography.bodyMedium, color = SemanticColors.TertiaryText)
                }
            }
            Gap(12)
            FeatherWeek(weekOf(s.history), arrivingToday = arriving != null, onArrived = onArrived)
            Gap(20)
        }
        CommitmentCard(m, s, onConnect, onOpen, goFocus)
        Gap(16)
        PresenceRing()
        s.squad?.let { sq ->
            Gap(12)
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).border(1.dp, SemanticColors.Border, Shapes.standard)
                .clickable(onClick = goSquad).padding(horizontal = Spacing.standard, vertical = Spacing.related),
                verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(sq.name, style = MaterialTheme.typography.titleMedium, color = SemanticColors.PrimaryText)
                    Text("${sq.doneToday} of ${sq.size} done today · squad streak ${sq.streak}",
                        style = MaterialTheme.typography.bodyMedium, color = SemanticColors.SecondaryText)
                }
                Text("Squad  ›", style = MaterialTheme.typography.labelLarge, color = SemanticColors.PrimaryText)
            }
        }
        if (s.facedownNow > 0) { Gap(16); LiveLine(s.facedownNow) }
        Gap(24)
    }
}

/**
 * Today's one dominant element: the next commitment in near-black with the primary action inside it.
 * The terms are summarized; Focus holds the full detail.
 */
@Composable
private fun CommitmentCard(m: MissionPolicy, s: UiState, onConnect: () -> Unit, onOpen: (MissionPolicy) -> Unit, goFocus: () -> Unit) {
    val mode = when { m.stake.noLoss -> "No-Loss"; staked(m) -> "Stake ${formatToken(m.stake.token)} ${m.reward.symbol}"; else -> "Free" }
    val reward = when {
        m.stake.noLoss -> "Stake back +${formatToken(m.reward.token)} ${m.reward.symbol} when you finish"
        staked(m) -> "Stake back + up to ${(m.stake.bonusRate * 100).toInt()}% when you finish"
        m.reward.token > 0 -> "+${formatToken(m.reward.token)} ${m.reward.symbol} from a sponsor when you finish"
        else -> "A verified session when you finish"
    }
    val used = s.profile?.claimsToday?.get(m.missionId) ?: 0
    Column(Modifier.fillMaxWidth().background(BrandColors.Ink, Shapes.large).padding(Spacing.section)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("NEXT COMMITMENT", style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 1.2.sp),
                color = BrandColors.White.copy(alpha = 0.6f))
            Spacer(Modifier.weight(1f))
            Text("Change", style = MaterialTheme.typography.labelLarge, color = BrandColors.White,
                modifier = Modifier.clickable(onClick = goFocus).padding(4.dp))
        }
        Gap(8)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(minutesLabel(m.durationSeconds), style = MaterialTheme.typography.displayMedium, color = BrandColors.White)
            Spacer(Modifier.size(Spacing.related))
            Text(mode, style = MaterialTheme.typography.titleMedium, color = BrandColors.White.copy(alpha = 0.72f),
                modifier = Modifier.padding(bottom = 8.dp))
        }
        Text(reward, style = MaterialTheme.typography.bodyMedium, color = BrandColors.White.copy(alpha = 0.8f))
        Gap(20)
        when {
            s.wallet == null -> PrimaryButton("Connect wallet", loading = s.busy, onClick = onConnect)
            used >= m.maxClaimsPerDay -> Text("✓  Done for today. Pick another length on Focus.",
                style = MaterialTheme.typography.titleMedium, color = BrandColors.White)
            else -> PrimaryButton("Go facedown  →", enabled = !s.busy) { onOpen(m) }
        }
    }
}

/** One dominant action, shared by Today and Focus. */
@Composable
private fun CommitAction(s: UiState, m: MissionPolicy?, onConnect: () -> Unit, onOpen: (MissionPolicy) -> Unit) {
    if (m == null || s.backendError != null) return
    val used = s.profile?.claimsToday?.get(m.missionId) ?: 0
    when {
        s.wallet == null -> {
            PrimaryButton("Connect wallet", loading = s.busy, onClick = onConnect)
            Text("Uses MetaMask on Monad testnet. FACEDOWN never sees your keys.",
                style = MaterialTheme.typography.bodyMedium, color = SemanticColors.TertiaryText,
                modifier = Modifier.fillMaxWidth().padding(vertical = Spacing.related))
        }
        used >= m.maxClaimsPerDay -> {
            Text("✓  ${minutesLabel(m.durationSeconds)} is done for today. Pick another length to go again.",
                style = MaterialTheme.typography.bodyMedium, color = SemanticColors.Success,
                modifier = Modifier.fillMaxWidth().background(SemanticColors.SuccessTint, Shapes.standard).padding(Spacing.standard))
            Gap(16)
        }
        else -> { PrimaryButton("Go facedown  →", enabled = !s.busy) { onOpen(m) }; Gap(16) }
    }
}

/** Today's compact summary of the selected commitment; the details live on Focus. */
@Composable
private fun NextCommitment(m: MissionPolicy, s: UiState, goFocus: () -> Unit) {
    val mode = when { m.stake.noLoss -> "No-Loss"; staked(m) -> "Stake ${formatToken(m.stake.token)} ${m.reward.symbol}"; else -> "Free" }
    val reward = when {
        m.stake.noLoss -> "Stake back +${formatToken(m.reward.token)} ${m.reward.symbol}"
        staked(m) -> "Stake back + up to ${(m.stake.bonusRate * 100).toInt()}%"
        m.reward.token > 0 -> "+${formatToken(m.reward.token)} ${m.reward.symbol} from a sponsor"
        else -> "A verified session"
    }
    Column(Modifier.fillMaxWidth().background(SemanticColors.Subtle, Shapes.large).clickable(onClick = goFocus)
        .padding(Spacing.standard)) {
        SectionLabel("Next commitment")
        Gap(6)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(minutesLabel(m.durationSeconds), style = MaterialTheme.typography.displayMedium, color = SemanticColors.PrimaryText)
            Spacer(Modifier.size(Spacing.related))
            Text(mode, style = MaterialTheme.typography.titleMedium, color = SemanticColors.SecondaryText,
                modifier = Modifier.padding(bottom = 8.dp))
        }
        Text(reward, style = MaterialTheme.typography.bodyMedium, color = BrandColors.Green)
        Gap(4)
        Text("Change length or mode  ›", style = MaterialTheme.typography.labelMedium, color = SemanticColors.TertiaryText)
    }
}

@Composable
private fun LiveLine(n: Int) = Row(verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(8.dp).background(BrandColors.Green, CircleShape))
    Spacer(Modifier.size(Spacing.related))
    Text(if (n == 1) "1 person is facedown right now" else "$n people are facedown right now",
        style = MaterialTheme.typography.labelMedium, color = SemanticColors.PrimaryText)
}

/** Loading placeholder: shape, not numbers. Nothing pretends to be data. */
@Composable
private fun SkeletonCard() = Box(Modifier.fillMaxWidth().height(96.dp).background(SemanticColors.Subtle, Shapes.large)
    .semantics { contentDescription = "Loading" })

// ---------- Focus ----------

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Focus(
    s: UiState, onConnect: () -> Unit, onSelect: (MissionPolicy) -> Unit, onOpen: (MissionPolicy) -> Unit,
    onRetry: () -> Unit,
) {
    val m = s.mission
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Gap(8)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Focus", style = MaterialTheme.typography.headlineLarge, color = SemanticColors.PrimaryText)
                Gap(4)
                Text("Pick a length and what's at stake. Every term is shown before your wallet opens.",
                    style = MaterialTheme.typography.bodyLarge, color = SemanticColors.SecondaryText)
            }
            Pip(PipMood.Coach, 88.dp, entrance = true)
        }
        if (s.backendError != null) {
            Gap(32)
            MessageState(PipMood.Mechanic, "Can't connect right now", s.backendError, action = "Try again", onAction = onRetry)
            return@Column
        }
        if (m == null) { Gap(24); SkeletonCard(); return@Column }
        Gap(24)
        // You choose the length and what's at stake (self-selected goals cut phone use 73% more than
        // assigned ones: Sachdeva et al., Soc Sci Med 2026). Free is the default way in.
        SectionLabel("Length")
        Gap(8)
        val durations = s.missions.map { it.durationSeconds }.distinct()
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(Spacing.compact)) {
            durations.forEach { d ->
                val on = d == m.durationSeconds
                val view = LocalView.current
                Column(Modifier.weight(1f).height(76.dp)
                    .background(if (on) SemanticColors.SuccessTint else SemanticColors.Subtle, Shapes.standard)
                    .border(if (on) 1.5.dp else 0.dp, if (on) BrandColors.Green else Color.Transparent, Shapes.standard)
                    .selectable(on, role = Role.RadioButton) {
                        Haptics.tick(view)
                        onSelect(s.missions.firstOrNull { it.durationSeconds == d && staked(it) == staked(m) && it.stake.noLoss == m.stake.noLoss }
                            ?: s.missions.first { it.durationSeconds == d })
                    },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text("${d / 60}", style = MaterialTheme.typography.headlineSmall,
                        color = if (on) BrandColors.Green else SemanticColors.PrimaryText)
                    Text("min", style = MaterialTheme.typography.labelMedium, color = SemanticColors.SecondaryText)
                }
            }
        }
        val variants = s.missions.filter { it.durationSeconds == m.durationSeconds }
        if (variants.size > 1) {
            Gap(24)
            SectionLabel("What's at stake")
            Gap(8)
            // Each mode states its own consequence before you choose it.
            Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(Spacing.compact)) {
                variants.forEach { v -> ModeCard(v, v == m) { onSelect(v) } }
            }
        }
        Gap(20)
        Box(Modifier.animateContentSize(tween(Motion.control, easing = Motion.Standard))) {
            Column { Commitment(m, s) }
        }
        Gap(16)
        Appointment()
        Gap(24)
    }
    CommitAction(s, m, onConnect, onOpen)
}

@Composable
private fun ModeCard(v: MissionPolicy, on: Boolean, onSelect: () -> Unit) {
    val sym = v.reward.symbol
    val (title, terms) = when {
        v.stake.noLoss -> "No-Loss" to "Lock ${formatToken(v.stake.token)} $sym; it always comes back. Finish for +${formatToken(v.reward.token)}."
        staked(v) -> "Stake ${formatToken(v.stake.token)} $sym" to "Finish: back + up to ${(v.stake.bonusRate * 100).toInt()}%. Quit: locked 7 days, not lost."
        else -> "Free" to (if (v.reward.token > 0) "Nothing at risk. A sponsor pays +${formatToken(v.reward.token)} $sym." else "Nothing at risk.")
    }
    val view = LocalView.current
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp)
        .background(if (on) SemanticColors.SuccessTint else BrandColors.White, Shapes.standard)
        .border(2.dp, if (on) BrandColors.Green else SemanticColors.Border, Shapes.standard)
        .selectable(on, role = Role.RadioButton) { Haptics.tick(view); onSelect() }
        .padding(Spacing.standard), verticalAlignment = Alignment.CenterVertically) {
        ModeTile(v, on)
        Spacer(Modifier.size(Spacing.related))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = SemanticColors.PrimaryText)
            Text(terms, style = MaterialTheme.typography.bodyMedium, color = SemanticColors.SecondaryText)
        }
        Spacer(Modifier.size(Spacing.related))
        Box(Modifier.size(22.dp).border(2.dp, if (on) BrandColors.Green else SemanticColors.Disabled, CircleShape),
            contentAlignment = Alignment.Center) {
            if (on) Box(Modifier.size(12.dp).background(BrandColors.Green, CircleShape))
        }
    }
}

// ---------- Squad ----------

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.SquadTab(
    s: UiState, onCreate: (String) -> Unit, onJoin: (String) -> Unit, goProfile: () -> Unit,
) = Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
    Gap(8)
    Text("Squad", style = MaterialTheme.typography.headlineLarge, color = SemanticColors.PrimaryText)
    if (s.facedownNow > 0) { Gap(8); LiveLine(s.facedownNow) }
    Gap(20)
    if (s.wallet == null) {
        MessageState(PipMood.Resting, "Connect a wallet first", "Squads are tied to your wallet.", action = "Go to Profile",
            onAction = goProfile)
        return@Column
    }
    SquadSection(s.squad, onCreate, onJoin)
    Gap(24)
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
    if (squad == null) {
        var code by remember { mutableStateOf("") }
        Column(Modifier.fillMaxWidth().background(SemanticColors.SuccessTint, Shapes.large).padding(Spacing.section),
            horizontalAlignment = Alignment.CenterHorizontally) {
            Pip(PipMood.Explorer, 128.dp, entrance = true)
            Gap(12)
            Text("Put it down together", style = MaterialTheme.typography.headlineSmall, color = SemanticColors.PrimaryText)
            Gap(6)
            Text("3 to 8 friends share one streak. Each day it grows when everyone has a verified session.",
                style = MaterialTheme.typography.bodyMedium, color = SemanticColors.SecondaryText, textAlign = TextAlign.Center)
            Gap(16)
            PrimaryButton("Start a squad") { onCreate("My squad") }
        }
        Gap(20)
        SectionLabel("Have a code?")
        Gap(8)
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(code, { code = it.uppercase().take(6) }, Modifier.weight(1f), singleLine = true,
                label = { Text("Invite code") })
            Spacer(Modifier.size(Spacing.related))
            SecondaryButton("Join", Modifier.width(96.dp)) { if (code.length == 6) onJoin(code) }
        }
        return
    }
    // Mode 4: the group's feathers. One per member, filled only for members with a verified session today.
    val everyone = squad.size > 1 && squad.doneToday == squad.size
    Column(Modifier.fillMaxWidth().background(if (everyone) SemanticColors.SuccessSoft else SemanticColors.SuccessTint, Shapes.large)
        .padding(Spacing.section)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(squad.name, style = MaterialTheme.typography.titleMedium, color = SemanticColors.SecondaryText)
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("${squad.streak}", style = MaterialTheme.typography.displayMedium, color = SemanticColors.PrimaryText)
                    Spacer(Modifier.size(Spacing.compact))
                    Text("day squad streak", style = MaterialTheme.typography.titleMedium, color = SemanticColors.PrimaryText,
                        modifier = Modifier.padding(bottom = 8.dp))
                }
            }
            Pip(if (everyone) PipMood.Champion else PipMood.Ready, 76.dp, entrance = everyone)
        }
        Gap(12)
        FeatherTrail(squad.members.map { it.doneToday }, featherSize = 18.dp)
        Gap(8)
        Text(if (everyone) "Everyone went facedown today." else "${squad.doneToday} of ${squad.size} done today",
            style = MaterialTheme.typography.bodyMedium, color = if (everyone) BrandColors.Green else SemanticColors.SecondaryText)
    }
    Gap(20)
    SectionLabel("Members")
    Gap(8)
    Column(Modifier.border(1.dp, SemanticColors.Border, Shapes.standard)) {
        squad.members.forEachIndexed { i, m ->
            if (i > 0) Hairline(Modifier.padding(start = 64.dp))
            Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(horizontal = Spacing.standard, vertical = Spacing.compact),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).background(if (m.doneToday) BrandColors.Green else SemanticColors.Subtle, CircleShape),
                    contentAlignment = Alignment.Center) {
                    Text(m.wallet.take(2).uppercase(), style = MaterialTheme.typography.labelMedium,
                        color = if (m.doneToday) BrandColors.White else SemanticColors.SecondaryText)
                }
                Spacer(Modifier.size(Spacing.related))
                Text(if (m.you) "You" else shortAddress(m.wallet), style = MaterialTheme.typography.titleMedium,
                    color = SemanticColors.PrimaryText, modifier = Modifier.weight(1f))
                Text(when { m.facedownNow -> "Facedown now"; m.doneToday -> "Done today"; else -> "Not yet today" },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (m.facedownNow || m.doneToday) BrandColors.Green else SemanticColors.TertiaryText)
            }
        }
    }
    Gap(20)
    Row(Modifier.fillMaxWidth().border(1.dp, SemanticColors.Border, Shapes.standard).padding(Spacing.standard),
        verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            SectionLabel("Invite code")
            Text(squad.code, style = MaterialTheme.typography.headlineSmall.copy(letterSpacing = 3.sp), color = SemanticColors.PrimaryText)
        }
        SecondaryButton("Share", Modifier.width(110.dp)) {
            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,
                "Join my FACEDOWN squad: code ${squad.code}. One shared streak, so don't break it."), "Invite"))
        }
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
            Text(if (on) "${minutes / 60} h ${minutes % 60} min facedown" else "Facedown time",
                style = MaterialTheme.typography.titleMedium, color = SemanticColors.PrimaryText)
            Text(if (on) "Today, of a ${PresenceMinutes.GOAL_MINUTES / 60} h goal" else "Count it all day, no session needed",
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

// ---------- Progress ----------

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Progress(s: UiState, onConnect: () -> Unit, onBuyFreeze: () -> Unit) {
    val ctx = LocalContext.current
    var replay by remember { mutableStateOf<Int?>(null) }
    var openDay by remember { mutableStateOf<Int?>(null) }
    var protection by remember { mutableStateOf(false) }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Gap(8)
        Text("Progress", style = MaterialTheme.typography.headlineLarge, color = SemanticColors.PrimaryText)
        Gap(16)
        val p = s.profile
        if (s.wallet == null || p == null) {
            Gap(24)
            MessageState(PipMood.Resting, "Nothing here yet",
                "Connect your wallet and your verified sessions will collect here.", action = "Connect", onAction = onConnect)
            return@Column
        }
        // A. The hero: one focal number, a message for the real state, Pip on the way to the next milestone.
        StreakHero(p.streak, p.provedToday)
        Gap(28)
        // B. The week: five honest states, today's feather arrives once.
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("This week")
            Spacer(Modifier.weight(1f))
            Text("Best ${p.bestStreak} · ${p.verifiedCount} verified", style = MaterialTheme.typography.labelMedium,
                color = SemanticColors.TertiaryText)
        }
        Gap(12)
        val week = weekOf(s.history)
        val (arriving, onArrived) = arrivingToday(keptDays(s.history))
        Column(Modifier.animateContentSize(tween(Motion.control, easing = Motion.Standard))) {
            FeatherWeek(week, featherSize = 20.dp, arrivingToday = arriving != null, onArrived = onArrived,
                onDay = { i -> openDay = if (openDay == i || week[i].state == DayState.Upcoming) null else i })
            openDay?.let { i -> DaySessions(i, s.history) }
        }
        Gap(28)
        // C. Milestones: earned, next, later.
        SectionLabel("Milestones")
        Gap(12)
        MilestoneJourney(p.streak, p.bestStreak, onEarned = { replay = it })
        Gap(28)
        // D. Streak protection: compact here; the decision lives in a sheet.
        Row(Modifier.fillMaxWidth().border(1.dp, SemanticColors.Border, Shapes.standard).clickable { protection = true }
            .padding(Spacing.standard), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(SemanticColors.SuccessTint, CircleShape), contentAlignment = Alignment.Center) {
                ShieldGlyph(held = p.streakFreezes > 0)
            }
            Spacer(Modifier.size(Spacing.related))
            Column(Modifier.weight(1f)) {
                Text(if (p.streakFreezes == 1) "1 streak freeze" else "${p.streakFreezes} streak freezes",
                    style = MaterialTheme.typography.titleMedium, color = SemanticColors.PrimaryText)
                Text("Each one covers a missed day", style = MaterialTheme.typography.bodyMedium, color = SemanticColors.SecondaryText)
            }
            Text("Terms  ›", style = MaterialTheme.typography.labelLarge, color = SemanticColors.PrimaryText)
        }
        Gap(28)
        // E. Sessions.
        SectionLabel("Recent sessions")
        Gap(8)
        if (s.history.isEmpty()) {
            Text("Finished and interrupted sessions appear here.", style = MaterialTheme.typography.bodyMedium,
                color = SemanticColors.TertiaryText)
        } else {
            Column(Modifier.border(1.dp, SemanticColors.Border, Shapes.standard)) {
                s.history.take(12).forEachIndexed { i, h ->
                    if (i > 0) Hairline(Modifier.padding(start = 64.dp))
                    HistoryRow(h)
                }
            }
        }
        Gap(24)
    }
    replay?.let { n -> MilestoneFlight(n, onDone = { replay = null }, onShare = { shareMilestone(ctx, n) }) }
    if (protection) androidx.compose.material3.ModalBottomSheet(onDismissRequest = { protection = false },
        containerColor = BrandColors.White) {
        FreezeTerms(s, onBuy = { protection = false; onBuyFreeze() }, onCancel = { protection = false })
    }
}

/** A shield: outlined when nothing protects you, filled when something does (a freeze, a No-Loss lock). */
@Composable
private fun ShieldGlyph(held: Boolean, color: Color = BrandColors.Green, size: androidx.compose.ui.unit.Dp = 20.dp) {
    androidx.compose.foundation.Canvas(Modifier.size(size)) {
        val w = this.size.width; val h = this.size.height
        val shield = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.5f, h * 0.06f); lineTo(w * 0.88f, h * 0.2f); lineTo(w * 0.88f, h * 0.48f)
            cubicTo(w * 0.88f, h * 0.72f, w * 0.7f, h * 0.86f, w * 0.5f, h * 0.95f)
            cubicTo(w * 0.3f, h * 0.86f, w * 0.12f, h * 0.72f, w * 0.12f, h * 0.48f)
            lineTo(w * 0.12f, h * 0.2f); close()
        }
        if (held) drawPath(shield, color)
        else drawPath(shield, if (color == BrandColors.Green) SemanticColors.SecondaryText else color,
            style = androidx.compose.ui.graphics.drawscope.Stroke(1.8.dp.toPx(), join = androidx.compose.ui.graphics.StrokeJoin.Round))
    }
}

/** The mode's icon tile: a feather (Free), a shield (No-Loss), a coin stack (Stake). Filled green when chosen. */
@Composable
private fun ModeTile(v: MissionPolicy, on: Boolean) {
    val ink = if (on) BrandColors.White else BrandColors.Green
    Box(Modifier.size(48.dp).background(if (on) BrandColors.Green else SemanticColors.SuccessTint, Shapes.small),
        contentAlignment = Alignment.Center) {
        when {
            v.stake.noLoss -> ShieldGlyph(true, ink, 24.dp)
            staked(v) -> androidx.compose.foundation.Canvas(Modifier.size(24.dp)) {
                val cw = size.width * 0.8f; val ch = size.height * 0.22f
                for (i in 0..2) {
                    val y = size.height * (0.72f - i * 0.24f)
                    drawOval(ink, androidx.compose.ui.geometry.Offset((size.width - cw) / 2, y), androidx.compose.ui.geometry.Size(cw, ch))
                    drawOval(if (on) BrandColors.Green else BrandColors.GreenMist,
                        androidx.compose.ui.geometry.Offset((size.width - cw) / 2 + 2.dp.toPx(), y + 1.2.dp.toPx()),
                        androidx.compose.ui.geometry.Size(cw - 4.dp.toPx(), ch - 3.dp.toPx()))
                }
            }
            else -> androidx.compose.foundation.Canvas(Modifier.size(26.dp)) {
                rotate(35f) {
                    translate(left = size.width * 0.19f) { feather(ink, filled = true, w = size.width * 0.62f, h = size.height) }
                }
            }
        }
    }
}

/** Mode 5: a decision, not a celebration. Every term before any token moves. */
@Composable
private fun FreezeTerms(s: UiState, onBuy: () -> Unit, onCancel: () -> Unit) = Column(Modifier.padding(horizontal = Spacing.gutter)) {
    val symbol = s.missions.firstOrNull()?.reward?.symbol ?: "TEST"
    PipSays(PipMood.Guard, "A freeze guards your streak on a day you miss.", pipSize = 88.dp)
    Gap(16)
    Text("Streak freeze", style = MaterialTheme.typography.headlineSmall, color = SemanticColors.PrimaryText)
    Gap(12)
    Group {
        KeyValue("Price", "2 $symbol")
        Hairline()
        KeyValue("What it does", "Covers 1 missed day")
        Hairline()
        KeyValue("Burned on-chain", "1 $symbol")
        Hairline()
        KeyValue("Kept by FACEDOWN", "1 $symbol")
        Hairline()
        KeyValue("You have", "${s.profile?.streakFreezes ?: 0}")
    }
    Gap(12)
    Text("Your wallet asks you to approve one transfer; nothing moves until you do. A freeze is used automatically " +
        "the next time you miss a day, and only then." + if (s.health?.network != "mainnet") " Off mainnet, $symbol is a test token with no value." else "",
        style = MaterialTheme.typography.bodyMedium, color = SemanticColors.SecondaryText)
    Gap(20)
    PrimaryButton(if (s.busy) "Waiting for your wallet…" else "Buy for 2 $symbol", loading = s.busy, onClick = onBuy)
    Gap(4)
    TextAction("Not now", Modifier.align(Alignment.CenterHorizontally), onCancel)
    Gap(24)
}

/** The sessions of one day of this week, in place under the week. */
@Composable
private fun DaySessions(index: Int, history: List<HistoryItem>) {
    val cal = java.util.Calendar.getInstance()
    fun key(ms: Long) = cal.apply { timeInMillis = ms }.let { it.get(java.util.Calendar.YEAR) * 1000 + it.get(java.util.Calendar.DAY_OF_YEAR) }
    val now = System.currentTimeMillis()
    cal.timeInMillis = now
    val dow = (cal.get(java.util.Calendar.DAY_OF_WEEK) + 5) % 7
    val day = key(now + (index - dow) * 86_400_000L)
    val sessions = history.filter { h -> h.startedAt?.let { key((it * 1000).toLong()) == day } == true }
    Column(Modifier.fillMaxWidth().padding(top = Spacing.related).background(SemanticColors.Subtle, Shapes.standard)
        .padding(Spacing.related)) {
        if (sessions.isEmpty()) Text("No sessions that day.", style = MaterialTheme.typography.bodyMedium, color = SemanticColors.TertiaryText)
        sessions.forEach { h ->
            Text("${h.missionName} · ${minutesLabel(h.durationSeconds.toInt())} · " +
                if (h.state == "SETTLED" || h.state == "VERIFIED") "verified" else "not verified",
                style = MaterialTheme.typography.bodyMedium, color = SemanticColors.PrimaryText)
        }
    }
}

private fun shareMilestone(ctx: android.content.Context, n: Int) {
    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT,
        "$n days of putting my phone face-down on FACEDOWN, each one verified on Monad."), "Share"))
}

@Composable
private fun Figure(label: String, value: String, modifier: Modifier) = Column(modifier) {
    Text(value, style = MaterialTheme.typography.displayMedium.copy(fontSize = MaterialTheme.typography.headlineLarge.fontSize),
        color = SemanticColors.PrimaryText)
    Text(label, style = MaterialTheme.typography.bodyMedium, color = SemanticColors.SecondaryText)
}

/** One session: status glyph, title, when and how long, then the real outcome; tap for the on-chain record. */
@Composable
private fun HistoryRow(h: HistoryItem) {
    val uri = LocalUriHandler.current
    val verified = h.state == "SETTLED" || h.state == "VERIFIED"
    val symbol = h.symbol ?: ""
    val outcome = when {
        h.state == "SETTLED" && h.paid > 0 -> "+${formatToken(h.paid)} $symbol"
        h.state == "SETTLED" -> "On Monad"
        h.state == "VERIFIED" -> "Not on-chain yet"
        h.stakeLocked > 0 -> "Stake locked"
        h.stakeReturned > 0 -> "Stake returned"
        h.stakeForfeited > 0 -> "Stake to pool"
        else -> "Not verified"
    }
    val meta = buildString {
        h.startedAt?.let { append(java.text.SimpleDateFormat("EEE d MMM, HH:mm", java.util.Locale.getDefault()).format(Date((it * 1000).toLong()))) }
        append(" · ${minutesLabel(h.durationSeconds.toInt())}")
        if (!verified) append(" · interrupted")
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 68.dp)
        .then(h.explorerUrl?.let { url -> Modifier.clickable { uri.openUri(url) } } ?: Modifier)
        .padding(horizontal = Spacing.standard, vertical = Spacing.related), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).background(if (verified) SemanticColors.SuccessTint else SemanticColors.Subtle, CircleShape),
            contentAlignment = Alignment.Center) {
            FeatherTrail(listOf(verified), featherSize = 11.dp)
        }
        Spacer(Modifier.size(Spacing.related))
        Column(Modifier.weight(1f)) {
            Text(h.missionName, style = MaterialTheme.typography.titleMedium, color = SemanticColors.PrimaryText)
            Text(meta, style = MaterialTheme.typography.bodyMedium, color = SemanticColors.SecondaryText)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(outcome, style = MaterialTheme.typography.labelLarge,
                color = if (verified && h.paid > 0) BrandColors.Green else SemanticColors.PrimaryText)
            if (h.explorerUrl != null) Text("Receipt ›", style = MaterialTheme.typography.labelMedium, color = SemanticColors.TertiaryText)
        }
    }
}

// ---------- Profile ----------

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ProfileTab(
    s: UiState, onConnect: () -> Unit, onDisconnect: () -> Unit, onRetry: () -> Unit, onCalibrate: () -> Unit,
) = Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
    Gap(8)
    Text("Profile", style = MaterialTheme.typography.headlineLarge, color = SemanticColors.PrimaryText)
    Gap(20)
    // Identity first: who is signed in, on which network, and what that wallet has done here.
    Column(Modifier.fillMaxWidth().background(SemanticColors.Subtle, Shapes.large).padding(Spacing.section)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).background(if (s.wallet != null) BrandColors.Green else SemanticColors.Disabled, CircleShape),
                contentAlignment = Alignment.Center) {
                Text(s.wallet?.address?.take(2)?.uppercase() ?: "–", style = MaterialTheme.typography.titleMedium, color = BrandColors.White)
            }
            Spacer(Modifier.size(Spacing.standard))
            Column(Modifier.weight(1f)) {
                Text(s.wallet?.let { shortAddress(it.address) } ?: "No wallet connected", style = MaterialTheme.typography.titleMedium,
                    color = SemanticColors.PrimaryText)
                Text(s.wallet?.let { "${it.label.ifBlank { "Wallet" }} · ${s.health?.network?.replaceFirstChar { c -> c.uppercase() } ?: "…"}" }
                    ?: "Connect to start a session", style = MaterialTheme.typography.bodyMedium, color = SemanticColors.SecondaryText)
            }
        }
        s.profile?.let { p ->
            Gap(16)
            Row(Modifier.fillMaxWidth()) {
                Figure("Verified", "${p.verifiedCount}", Modifier.weight(1f))
                Figure("Best streak", "${p.bestStreak}", Modifier.weight(1f))
                Figure("Freezes", "${p.streakFreezes}", Modifier.weight(1f))
            }
        }
        Gap(16)
        if (s.wallet == null) PrimaryButton("Connect wallet", loading = s.busy, onClick = onConnect)
        else SecondaryButton("Disconnect wallet", onClick = onDisconnect)
    }

    Gap(32)
    SectionLabel("Urge Catcher")
    Gap(8)
    UrgeCatcherSettings()

    Gap(32)
    SectionLabel("Motion")
    Gap(8)
    val ctx2 = LocalContext.current
    DurationSelector(listOf(false, true), MotionSettings.reduced, label = { if (it) "Reduced" else "Full" }) {
        MotionSettings.set(ctx2, it)
    }
    Gap(8)
    Text("Reduced turns off mascot motion, celebrations and spatial transitions. Android's \"Remove animations\" " +
        "setting always does the same.", style = MaterialTheme.typography.bodyMedium, color = SemanticColors.TertiaryText)

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
            KeyValue("Device check", if (it.mode == "development") "Bypassed (development)" else "Required")
        }
    }
    if (s.backendError != null) SecondaryButton("Try again", Modifier.padding(top = 12.dp), onClick = onRetry)
    if (h != null && h.network != "mainnet") {
        Gap(8)
        Text("Off mainnet, stakes use a test token (${s.missions.firstOrNull()?.reward?.symbol ?: "TEST"}) with no value.",
            style = MaterialTheme.typography.bodyMedium, color = SemanticColors.TertiaryText)
    }

    Gap(32)
    SectionLabel("Privacy")
    Gap(8)
    Text("During a session FACEDOWN records whether the app stayed on screen, when you answered each check-in, " +
        "and two motion numbers per check-in from the accelerometer: how much of the time the phone lay still and face-down, and " +
        "how much your knock or tap moved it. Raw sensor data never leaves the phone. No location, contacts, microphone or " +
        "camera, and nothing in the background. " +
        "Only a fingerprint (hash) of the session is written to Monad.",
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

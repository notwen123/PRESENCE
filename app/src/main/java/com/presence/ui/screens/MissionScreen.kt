package com.presence.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.presence.session.UiState
import com.presence.ui.components.EnvironmentLabel
import com.presence.ui.components.Gap
import com.presence.ui.components.Group
import com.presence.ui.components.Hairline
import com.presence.ui.components.KeyValue
import com.presence.ui.components.Page
import com.presence.ui.components.PrimaryButton
import com.presence.ui.components.SectionLabel
import com.presence.ui.components.TopBar
import com.presence.ui.components.formatToken
import com.presence.ui.components.minutesLabel
import com.presence.ui.theme.BrandColors
import com.presence.ui.theme.SemanticColors
import com.presence.ui.theme.Spacing

@Composable
private fun Rule(text: String) = Row(Modifier.padding(vertical = 6.dp)) {
    Text("•", style = MaterialTheme.typography.bodyLarge, color = SemanticColors.TertiaryText)
    Spacer(Modifier.width(Spacing.related))
    Text(text, style = MaterialTheme.typography.bodyLarge, color = SemanticColors.PrimaryText)
}

/** Commitment confirmation: every term the user agrees to, before any wallet prompt. */
@Composable
fun MissionScreen(s: UiState, onBack: () -> Unit, onStart: () -> Unit) = Page {
    val m = s.mission ?: return@Page
    val noLoss = m.stake.noLoss
    val staked = m.stake.token > 0 && !noLoss  // money actually at risk
    val stake = "${formatToken(m.stake.token)} ${m.reward.symbol}"
    var consent by rememberSaveable { mutableStateOf(false) }

    TopBar(onBack = onBack) { EnvironmentLabel(s.health?.mode, s.health?.network) }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Gap(8)
        SectionLabel("Confirm your commitment")
        Gap(8)
        Text(m.name, style = MaterialTheme.typography.headlineLarge, color = SemanticColors.PrimaryText)
        Gap(24)
        Group {
            KeyValue("Duration", minutesLabel(m.durationSeconds))
            Hairline()
            KeyValue("Check-ins", "${m.requiredCheckpoints}, one every ${minutesLabel(m.checkpointIntervalSeconds)}")
            if (!staked && !noLoss && m.reward.token > 0) {
                Hairline()
                KeyValue("Stake", "None")
                Hairline()
                KeyValue("If you finish", "+${formatToken(m.reward.token)} ${m.reward.symbol} from the sponsor")
            }
            if (noLoss) {
                Hairline()
                KeyValue("Stake", "$stake, locked")
                Hairline()
                KeyValue("If you finish", "Stake back +${formatToken(m.reward.token)} ${m.reward.symbol}")
                Hairline()
                KeyValue("If you stop early", "Stake back, no reward")
            }
            if (staked) {
                Hairline()
                KeyValue("Stake", stake)
                Hairline()
                KeyValue("If you finish", "Stake back + up to ${(m.stake.bonusRate * 100).toInt()}%")
                if (m.stake.cooldown) {
                    Hairline()
                    KeyValue("If you quit", "Locked 7 days (14, then 30)")
                }
            }
        }

        Gap(32)
        SectionLabel(if (staked) "To keep your stake" else "To finish")
        Gap(4)
        Rule("Keep FACEDOWN open and lay the phone face-down on a table. The screen goes dark.")
        Rule("When it buzzes, knock on the back of the phone. That's ${m.requiredCheckpoints} knocks in ${minutesLabel(m.durationSeconds)}.")
        Rule("Stay online until the result appears.")

        Gap(24)
        SectionLabel(when {
            staked && m.stake.cooldown -> "Your stake is locked if"
            staked -> "You lose the stake if"
            else -> "The session fails if"
        })
        Gap(4)
        Rule("You switch apps, go home, or lock the phone")
        Rule("You miss a check-in, or pick the phone up and keep it face-up")
        Rule("The session runs out before it's finished")

        Gap(24)
        SectionLabel("What's verified")
        Gap(8)
        Text("The FACEDOWN server keeps the time, so a phone clock can't be faked. It checks that this phone stayed " +
            "in the app, lay still and face-down, and was answered at unpredictable moments by a real knock: the " +
            "motion sensor feels every knock or tap, so a script or AI agent answering for you is caught. It can't tell what you focused on." +
            if (staked) " Forfeited stakes go to the pool that pays finishers' bonuses." else "",
            style = MaterialTheme.typography.bodyMedium, color = SemanticColors.SecondaryText)
        Gap(24)
        SectionLabel("Next")
        Gap(8)
        Text(if (staked || noLoss) "Your wallet asks you to approve the sign-in and the $stake stake. Nothing moves until you approve."
            else "Your wallet asks you to sign in.",
            style = MaterialTheme.typography.bodyMedium, color = SemanticColors.SecondaryText)
        Gap(24)
    }

    Hairline()
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp)
            .toggleable(consent, role = Role.Checkbox) { consent = it }
            .padding(vertical = Spacing.related),
        verticalAlignment = Alignment.Top,
    ) {
        Checkbox(checked = consent, onCheckedChange = null,
            colors = CheckboxDefaults.colors(checkedColor = BrandColors.Green, checkmarkColor = BrandColors.White,
                uncheckedColor = SemanticColors.SecondaryText))
        Spacer(Modifier.width(Spacing.related))
        Text(
            (if (staked && m.stake.cooldown) "I understand $stake is locked for 7 days or more if I quit. "
             else if (staked) "I understand I lose $stake if I leave or miss a check-in. " else "") +
                "During the session FACEDOWN records only whether it stays on screen, when I answer, and two " +
                "motion numbers (how still and face-down the phone lay, how much my knocks moved it).",
            style = MaterialTheme.typography.bodyMedium, color = SemanticColors.PrimaryText,
        )
    }
    PrimaryButton(
        when {
            s.busy -> "Waiting for your wallet…"
            staked -> "Stake $stake & go facedown"
            noLoss -> "Lock $stake & go facedown"
            else -> "Go facedown"
        },
        enabled = consent, loading = s.busy, onClick = onStart)
    Gap(16)
}

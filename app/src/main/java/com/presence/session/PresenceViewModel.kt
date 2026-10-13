package com.presence.session

import android.content.Context
import android.content.SharedPreferences
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.SystemClock
import android.util.Base64
import com.presence.ui.components.formatToken
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.funkatronics.encoders.Base58
import com.presence.attest.AttestApi
import com.presence.attest.AttestException
import com.presence.attest.CheckpointBody
import com.presence.attest.MissionPolicy
import com.presence.evidence.CheckpointData
import com.presence.evidence.EphemeralKey
import com.presence.evidence.EvidenceChain
import com.presence.evidence.MotionProbe
import com.presence.evidence.toHex
import com.presence.wallet.WalletRepository
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives one bounded mission: SIWS authorize -> timed checkpoints -> evidence root -> server verify.
 * The client only collects and signs evidence. Every PASS/FAIL shown comes from the ATTEST server.
 */
@HiltViewModel
class PresenceViewModel @Inject constructor(
    private val api: AttestApi,
    private val wallet: WalletRepository,
    @ApplicationContext private val context: Context,
    private val prefs: SharedPreferences,
) : ViewModel() {

    private val _state = MutableStateFlow(UiState(wallet = wallet.account,
        knockThreshold = prefs.getFloat(KEY_KNOCK, 0f).takeIf { it > 0f }))
    val state: StateFlow<UiState> = _state

    private var missionJob: Job? = null

    // Evidence window counters, reset at every checkpoint. Only counts are kept, never raw input.
    @Volatile private var windowTaps = 0
    @Volatile private var windowResponseMs = -1L
    // Set while a session runs, so onPulse can time the answer against the window's prompt.
    @Volatile private var sessionStart = 0L
    @Volatile private var intervalMsRunning = 10_000L
    @Volatile private var promptAtRunning: List<Long> = emptyList()
    @Volatile private var windowForeground = true
    @Volatile private var anyBackground = false
    @Volatile private var motion: MotionProbe? = null

    init {
        refresh()
    }

    fun refresh(attempt: Int = 0): Job = viewModelScope.launch {
        try {
            val health = api.health()
            val missions = api.missions()
            val profile = wallet.account?.let { api.profile(it.address) }
            val history = wallet.account?.let { runCatching { api.history(it.address) }.getOrNull() } ?: emptyList()
            val pools = runCatching { api.pools().missions }.getOrDefault(emptyMap())
            val live = runCatching { api.live().facedownNow }.getOrDefault(0)
            val squad = wallet.account?.let { runCatching { api.squad(it.address) }.getOrNull() }
            _state.update { s ->
                val selected = missions.firstOrNull { m -> m.missionId == s.mission?.missionId } ?: missions.firstOrNull()
                s.copy(health = health, missions = missions, mission = selected, profile = profile, pools = pools, history = history, facedownNow = live, squad = squad,
                    backendError = null)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(backendError = reasonMessage((e as? AttestException)?.reason ?: "NETWORK_ERROR")) }
            // A free server may still be waking up: retry network failures a few times, 5/10/15/20 s apart.
            if (((e as? AttestException)?.reason ?: "NETWORK_ERROR") == "NETWORK_ERROR" && attempt < 4) {
                delay(5_000L * (attempt + 1))
                refresh(attempt + 1)
            }
        }
    }

    fun connect(sender: ActivityResultSender) = viewModelScope.launch {
        guarded {
            val account = wallet.connect(sender)
            _state.update { it.copy(wallet = account) }
            // Off mainnet, give a fresh test wallet fee SOL and test tokens so it can stake (server-side guarded).
            ensureTestFunds(account.address)
            refresh()
        }
    }

    /** Off mainnet only: one faucet grant (fee SOL + test tokens) per wallet; the server enforces both. */
    private suspend fun ensureTestFunds(address: String) {
        if (_state.value.health?.network == "mainnet") return
        try {
            api.faucet(address)
        } catch (e: AttestException) {
            log(null, "faucet", e.reason)
        }
    }

    /** Buy one streak freeze: the wallet signs an SKR payment; the server burns half and credits the freeze. */
    fun buyFreeze(sender: ActivityResultSender) = viewModelScope.launch {
        val account = wallet.account ?: return@launch
        guarded {
            _state.update { it.copy(busy = true) }
            val order = api.freezeOrder(account.address)
            val signed = wallet.signTransaction(sender, Base64.decode(order.transaction, Base64.NO_WRAP))
            val paid = api.freezePay(order.orderId, Base64.encodeToString(signed, Base64.NO_WRAP))
            _state.update { it.copy(busy = false, message = "Streak freeze added. ${formatToken(paid.burned)} ${order.symbol} burned.") }
            refresh()
        }
    }

    private var calibration: Job? = null

    /** Learn this user's knock: phone face-down, three knocks; each felt knock gets a short buzz. */
    fun calibrate() {
        calibration?.cancel()
        val vibrator = context.getSystemService(Vibrator::class.java)
        val probe = MotionProbe(context, knock = MotionProbe.CALIBRATION_KNOCK)
        _state.update { it.copy(screen = Screen.Calibrate()) }
        probe.onKnock = { jolt ->
            val c = _state.value.screen as? Screen.Calibrate
            if (c != null && c.threshold == null && c.faceDown) {
                val jolts = c.jolts + jolt
                probe.quiet(500)
                vibrator?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
                if (jolts.size < 3) _state.update { it.copy(screen = c.copy(jolts = jolts)) }
                else {
                    val t = MotionProbe.personalKnock(jolts)
                    prefs.edit().putFloat(KEY_KNOCK, t).apply()
                    _state.update { it.copy(knockThreshold = t, screen = c.copy(jolts = jolts, threshold = t)) }
                }
            }
        }
        probe.start()
        calibration = viewModelScope.launch {
            try {
                while (isActive) {
                    _state.update { s -> (s.screen as? Screen.Calibrate)?.let { s.copy(screen = it.copy(faceDown = probe.faceDown)) } ?: s }
                    delay(200)
                }
            } finally {
                probe.stop()
            }
        }
    }

    fun endCalibration() {
        calibration?.cancel()
        _state.update { it.copy(screen = Screen.Home) }
    }

    fun createSquad(name: String) = squadAction { api.createSquad(it, name) }
    fun joinSquad(code: String) = squadAction { api.joinSquad(it, code) }

    private fun squadAction(call: suspend (String) -> com.presence.attest.Squad) = viewModelScope.launch {
        val address = wallet.account?.address ?: return@launch
        try {
            val sq = call(address)
            _state.update { it.copy(squad = sq) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            val detail = (e as? AttestException)?.detail?.takeIf { it.isNotBlank() }
            _state.update { it.copy(message = detail?.replaceFirstChar { c -> c.uppercase() } ?: reasonMessage("NETWORK_ERROR")) }
        }
    }

    fun disconnect() {
        wallet.disconnect()
        _state.update { it.copy(wallet = null, profile = null, history = emptyList()) }
    }

    fun openMission() = _state.update { it.copy(screen = Screen.Mission) }

    fun openMission(m: MissionPolicy) = _state.update { it.copy(mission = m, screen = Screen.Mission) }

    fun select(m: MissionPolicy) = _state.update { it.copy(mission = m) }

    fun home() {
        missionJob?.cancel()
        _state.update { it.copy(screen = Screen.Home) }
        refresh()
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    /** "I'm here" tapped on the screen: the probe scores how much the finger moved the phone. */
    fun onPulse() {
        motion?.onTap()
        pulse()
    }

    /** About to buzz: the vibration motor shakes the accelerometer, so don't read it as a knock. */
    fun onBuzz() = motion?.quiet(900)

    private fun pulse() {
        windowTaps++
        if (windowResponseMs < 0 && promptAtRunning.isNotEmpty()) {
            val elapsed = SystemClock.elapsedRealtime() - sessionStart
            val window = (elapsed / intervalMsRunning).toInt().coerceAtMost(promptAtRunning.size - 1)
            windowResponseMs = (elapsed - window * intervalMsRunning - promptAtRunning[window]).coerceAtLeast(0)
        }
        _state.update { s ->
            val a = s.screen as? Screen.Active ?: return@update s
            s.copy(screen = a.copy(confirmedThisWindow = true))
        }
    }

    fun onForeground(foreground: Boolean) {
        if (!foreground) {
            windowForeground = false
            anyBackground = true
        }
    }

    fun startMission(sender: ActivityResultSender) {
        val mission = _state.value.mission ?: return
        val account = wallet.account ?: run {
            _state.update { it.copy(message = reasonMessage("WALLET_NOT_CONNECTED")) }
            return
        }
        missionJob?.cancel()
        missionJob = viewModelScope.launch {
            val key = EphemeralKey.create()
            var staked: String? = null
            try {
                _state.update { it.copy(busy = true) }
                if (mission.stake.token > 0) ensureTestFunds(account.address)
                val session = api.createSession(account.address, mission.missionId, key.publicKeyB64)
                val stakeTx = session.stake?.let { Base64.decode(it.transaction, Base64.NO_WRAP) }
                val (signature, stakeSigned) = wallet.signSession(sender, session.siwsMessage, stakeTx)
                val auth = api.authorize(session.sessionId, Base58.encodeToString(signature),
                    stakeSigned?.let { Base64.encodeToString(it, Base64.NO_WRAP) })
                staked = session.stake?.let { "${formatToken(it.amount)} ${it.symbol}" }
                log(session.sessionId, "authorized", auth.eligibility)
                _state.update { it.copy(busy = false) }

                // Short countdown after returning from the wallet so the first window isn't missed.
                // Server timing starts at authorize; this only makes the first window longer, never shorter.
                for (n in 3 downTo 1) {
                    _state.update { it.copy(screen = Screen.Countdown(n)) }
                    delay(1000)
                }
                val chain = EvidenceChain(session.sessionId, session.nonce)
                runProcess(session.sessionId, session.policy, chain, key)

                val checks = mutableListOf(
                    Check("Wallet signature", CheckStatus.PASSED),
                    Check("Seeker device check",
                        if (auth.eligibility == "SGT_VERIFIED") CheckStatus.PASSED else CheckStatus.DEV_BYPASS),
                    Check("One-time session code", CheckStatus.PASSED),
                    Check("Session evidence"),
                    Check("Session rules"),
                    Check("Human touch"),
                    Check("Phone stayed down"),
                    Check("Not claimed before"),
                    Check("Recorded on Solana"),
                )
                fun show() = _state.update { it.copy(screen = Screen.Verifying(checks.toList())) }
                show()

                val root = chain.root()
                api.submitEvidence(session.sessionId, root.toHex(), key.sign(root))
                checks[3] = checks[3].copy(status = CheckStatus.PASSED)
                show()

                val result = api.verify(session.sessionId)
                val receipt = result.receipt
                if (!result.verified || receipt == null) {
                    throw AttestException(result.reason ?: "POLICY_NOT_SATISFIED", result.detail ?: "")
                }
                fun physical(key: String) = when (receipt.checks[key]) {
                    "PASSED" -> CheckStatus.PASSED
                    "FLAGGED" -> CheckStatus.FLAGGED
                    else -> CheckStatus.NOT_CONFIGURED
                }
                checks[4] = checks[4].copy(status = CheckStatus.PASSED)
                checks[5] = checks[5].copy(status = physical("human_touch"))
                checks[6] = checks[6].copy(status = physical("phone_down"))
                checks[7] = checks[7].copy(status = CheckStatus.PASSED)
                checks[8] = checks[8].copy(status = when (receipt.settlement.status) {
                    "CONFIRMED" -> CheckStatus.PASSED
                    "NOT_CONFIGURED" -> CheckStatus.NOT_CONFIGURED
                    else -> CheckStatus.FAILED
                })
                show()
                delay(900) // let the final check land before the receipt
                _state.update { it.copy(screen = Screen.Result(receipt, chain.hashes.map { h -> h.toHex() })) }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val err = e as? AttestException ?: AttestException("CLIENT_ERROR", e.javaClass.simpleName)
                log(null, "failed", err.reason)
                // A stake is only lost if the session got past authorize (the server forfeits it).
                val atStake = staked.takeIf { err.reason !in setOf("STAKE_REQUIRED", "STAKE_FAILED", "NETWORK_ERROR", "WALLET_REJECTED") }
                val noLoss = mission.stake.noLoss
                // Cool-down: the server decides how long (7 / 14 / 30 days); read it back from the receipt.
                val lockedUntil = if (atStake != null && mission.stake.cooldown) runCatching {
                    api.history(account.address).firstOrNull()?.unlockAt?.let { ts ->
                        "$atStake, until " + java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM)
                            .format(java.util.Date((ts * 1000).toLong()))
                    }
                }.getOrNull() else null
                _state.update { it.copy(busy = false, screen = Screen.Failed(err.reason, err.detail,
                    stakeLost = atStake.takeUnless { noLoss || mission.stake.cooldown },
                    stakeReturned = atStake.takeIf { noLoss }, stakeLockedUntil = lockedUntil)) }
            } finally {
                key.destroy()
            }
        }
    }

    private suspend fun runProcess(sessionId: String, policy: MissionPolicy, chain: EvidenceChain, key: EphemeralKey) {
        val start = SystemClock.elapsedRealtime()
        val intervalMs = policy.checkpointIntervalSeconds * 1000L
        val durationMs = policy.durationSeconds * 1000L
        windowTaps = 0
        windowForeground = true
        anyBackground = false
        val probe = MotionProbe(context, knock = _state.value.knockThreshold ?: MotionProbe.KNOCK).also { it.start(); motion = it }
        // A knock on the back answers the check-in, so a face-down phone never has to be picked up.
        probe.onKnock = { if ((_state.value.screen as? Screen.Active)?.promptVisible == true) pulse() }
        var done = 0
        // Each window's presence check appears at an unpredictable moment, 15-60% into the window.
        val promptAt = List(policy.requiredCheckpoints) { (intervalMs * (0.15 + Math.random() * 0.45)).toLong() }
        sessionStart = start
        intervalMsRunning = intervalMs
        promptAtRunning = promptAt
        windowResponseMs = -1

        fun publish(confirmed: Boolean) = _state.update {
            val elapsed = SystemClock.elapsedRealtime() - start
            val window = (elapsed / intervalMs).toInt().coerceAtMost(policy.requiredCheckpoints - 1)
            it.copy(screen = Screen.Active(
                elapsedMs = elapsed,
                checkpointsDone = done,
                checkpointsTotal = policy.requiredCheckpoints,
                durationMs = durationMs,
                confirmedThisWindow = confirmed,
                leftForeground = anyBackground,
                sealed = chain.hashes.take(done).map { h -> h.toHex() },
                promptVisible = !confirmed && elapsed % intervalMs >= promptAt[window],
                faceDown = probe.faceDown,
            ))
        }
        publish(false)

        val ticker = viewModelScope.launch {
            while (isActive) {
                val a = _state.value.screen as? Screen.Active
                publish(a?.confirmedThisWindow ?: false)
                delay(200)
            }
        }
        try {
            for (i in 0 until policy.requiredCheckpoints) {
                val due = start + (i + 1) * intervalMs
                delay((due - SystemClock.elapsedRealtime()).coerceAtLeast(0))
                val (stillPct, touchMotion) = probe.takeWindow()
                val cp = CheckpointData(
                    index = i,
                    timestampMs = System.currentTimeMillis(),
                    elapsedMs = SystemClock.elapsedRealtime() - start,
                    foreground = windowForeground,
                    interactions = windowTaps,
                    responseMs = windowResponseMs,
                    stillPct = stillPct,
                    touchMotion = touchMotion,
                )
                windowTaps = 0
                windowResponseMs = -1
                windowForeground = true
                val hash = chain.append(cp)
                api.checkpoint(sessionId, CheckpointBody(
                    cp.index, cp.timestampMs, cp.elapsedMs, cp.foreground, cp.interactions, cp.responseMs,
                    cp.stillPct, cp.touchMotion, hash.toHex(), key.sign(hash)))
                done = i + 1
                runCatching { api.live().facedownNow }.onSuccess { n -> _state.update { it.copy(facedownNow = n) } }
                publish(false)
            }
        } finally {
            ticker.cancel()
            probe.stop()
            motion = null
        }
    }

    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(busy = false, message = reasonMessage((e as? AttestException)?.reason ?: "CLIENT_ERROR")) }
        }
    }

    // Never logs keys, signatures, auth tokens or evidence contents.
    private fun log(sessionId: String?, stage: String, result: String) =
        Log.i("PRESENCE", "stage=$stage session=$sessionId result=$result")

    private companion object {
        const val KEY_KNOCK = "knock_threshold"
    }
}

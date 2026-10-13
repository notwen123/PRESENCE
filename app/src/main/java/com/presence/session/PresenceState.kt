package com.presence.session

import com.presence.attest.Health
import com.presence.attest.MissionPolicy
import com.presence.attest.Profile
import com.presence.attest.Receipt
import com.presence.wallet.WalletAccount

/** FLAGGED: the check found a problem but development mode doesn't enforce it. */
enum class CheckStatus { PENDING, PASSED, DEV_BYPASS, NOT_CONFIGURED, FLAGGED, FAILED }

/** One verification step shown to the user. Only marked PASSED after the server confirmed it. */
data class Check(val label: String, val status: CheckStatus = CheckStatus.PENDING)

sealed interface Screen {
    data object Home : Screen
    data object Mission : Screen
    data class Countdown(val seconds: Int) : Screen
    data class Active(
        val elapsedMs: Long,
        val checkpointsDone: Int,
        val checkpointsTotal: Int,
        val durationMs: Long,
        val confirmedThisWindow: Boolean,
        val leftForeground: Boolean,
        /** Hex hashes of checkpoints the server has accepted, in order. */
        val sealed: List<String> = emptyList(),
        /** The presence check for the current window is showing. */
        val promptVisible: Boolean = false,
        /** The accelerometer says the screen is facing the table. */
        val faceDown: Boolean = false,
    ) : Screen
    data class Verifying(val checks: List<Check>) : Screen
    /** Knock calibration: [jolts] felt so far (mm/s^2); [threshold] once done. */
    data class Calibrate(val jolts: List<Int> = emptyList(), val threshold: Float? = null, val faceDown: Boolean = false) : Screen
    data class Result(val receipt: Receipt, val checkpointHashes: List<String>) : Screen
    /** [stakeLost]: forfeited to the pool. [stakeReturned]: a No-Loss stake on its way back. */
    data class Failed(val reason: String, val detail: String, val stakeLost: String? = null,
                      val stakeReturned: String? = null, val stakeLockedUntil: String? = null) : Screen
}

data class UiState(
    val screen: Screen = Screen.Home,
    val wallet: WalletAccount? = null,
    val health: Health? = null,
    val backendError: String? = null,
    val missions: List<MissionPolicy> = emptyList(),
    val pools: Map<String, com.presence.attest.PoolInfo> = emptyMap(),
    /** The mission the user picked; defaults to the first one. */
    val mission: MissionPolicy? = null,
    val profile: Profile? = null,
    val history: List<com.presence.attest.HistoryItem> = emptyList(),
    /** The user's calibrated knock threshold (m/s^2), or null if never calibrated. */
    val knockThreshold: Float? = null,
    val squad: com.presence.attest.Squad? = null,
    /** Sessions running on the server right now, including yours. */
    val facedownNow: Int = 0,
    val busy: Boolean = false,
    val message: String? = null,
)

/** User-facing copy per failure reason; technical detail stays in logs / the evidence view. */
fun reasonMessage(reason: String): String = when (reason) {
    "SESSION_EXPIRED" -> "The session expired before it was finished."
    "INVALID_NONCE", "NONCE_REPLAY" -> "This session was already used. Start a new one."
    "INVALID_SIGNATURE" -> "The wallet signature could not be verified."
    "INVALID_IDENTITY" -> "The wallet or session key is not valid."
    "SGT_NOT_ELIGIBLE" -> "No Seeker Genesis Token found in this wallet."
    "EVIDENCE_CHAIN_INVALID" -> "Session evidence was inconsistent and was rejected."
    "POLICY_NOT_SATISFIED" -> "The session was interrupted: the app left the screen or a check-in was missed."
    "AUTOMATION_DETECTED" -> "The check-ins didn't come from a person: the taps didn't move the phone, " +
        "or they were answered like a script."
    "ALREADY_CLAIMED" -> "You've used today's clock-ins for this session length."
    "STAKE_REQUIRED", "STAKE_FAILED" -> "The stake didn't go through, so the session didn't start. Nothing was taken."
    "ATTESTATION_FAILED" -> "Your session was verified, but recording it on Solana failed."
    "NETWORK_ERROR" -> "Can't reach the FACEDOWN server. Check your connection and try again."
    "WALLET_NOT_CONNECTED" -> "Connect your wallet first."
    "WALLET_NOT_FOUND" -> "No Mobile Wallet Adapter wallet is installed."
    "WALLET_REJECTED" -> "The wallet request was cancelled. Nothing was signed or taken."
    "CLIENT_ERROR" -> "Something went wrong on this device."
    else -> "Something went wrong ($reason)."
}

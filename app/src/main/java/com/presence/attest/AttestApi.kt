package com.presence.attest

import com.presence.BuildConfig
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Server-side failure reasons (backend/attest/errors.py) plus client-only ones. */
class AttestException(val reason: String, val detail: String = "") : Exception("$reason $detail")

@Serializable data class Health(val mode: String, val network: String, val settlement: String,
                               /** "enforced" or "reported" (development: emulators have no real motion). */
                               val physical: String = "enforced")

@Serializable
data class Reward(
    val xp: Int,
    val token: Double = 0.0,
    val symbol: String = "SKR",
    val funding: String? = null,
    val stake: Double = 0.0,
    val bonus: Double = 0.0,
)

@Serializable data class StakeTerms(val token: Double = 0.0, @SerialName("bonus_rate") val bonusRate: Double = 0.0,
                                   /** Returned whether you finish or not; only finishers earn. */
                                   @SerialName("no_loss") val noLoss: Boolean = false,
                                   /** Quitting locks the stake for 7 / 14 / 30 days instead of losing it. */
                                   val cooldown: Boolean = false)

@Serializable data class StakeOffer(val amount: Double, val symbol: String, val transaction: String)

@Serializable data class PoolInfo(val symbol: String, val forfeited: Double = 0.0, val remaining: Double = 0.0)

@Serializable data class Pools(val missions: Map<String, PoolInfo> = emptyMap())

@Serializable
data class MissionPolicy(
    @SerialName("mission_id") val missionId: String,
    val name: String,
    val description: String = "",
    val steps: List<String>,
    @SerialName("required_assurance") val requiredAssurance: String,
    @SerialName("duration_seconds") val durationSeconds: Int,
    @SerialName("required_checkpoints") val requiredCheckpoints: Int,
    @SerialName("checkpoint_interval_seconds") val checkpointIntervalSeconds: Int,
    @SerialName("min_interactions_per_checkpoint") val minInteractionsPerCheckpoint: Int,
    @SerialName("max_claims_per_identity_per_day") val maxClaimsPerDay: Int = 1,
    val reward: Reward,
    val stake: StakeTerms = StakeTerms(),
    val sponsor: String = "",
    val why: String = "",
)

@Serializable
data class SessionCreated(
    @SerialName("session_id") val sessionId: String,
    val nonce: String,
    @SerialName("siws_message") val siwsMessage: String,
    val policy: MissionPolicy,
    val mode: String,
    val stake: StakeOffer? = null,
)

@Serializable data class Authorized(val eligibility: String, @SerialName("sgt_mint") val sgtMint: String? = null)

@Serializable
data class Profile(
    val xp: Int,
    val league: String,
    val rank: Int? = null,
    val streak: Int,
    @SerialName("best_streak") val bestStreak: Int,
    @SerialName("verified_count") val verifiedCount: Int,
    val reputation: Int,
    @SerialName("proved_today") val provedToday: Boolean,
    @SerialName("claims_today") val claimsToday: Map<String, Int> = emptyMap(),
    @SerialName("streak_freezes") val streakFreezes: Int = 0,
)

@Serializable
data class SquadMember(val wallet: String, val you: Boolean = false, @SerialName("done_today") val doneToday: Boolean = false,
                       @SerialName("facedown_now") val facedownNow: Boolean = false)

@Serializable
data class Squad(val name: String, val code: String, val streak: Int, @SerialName("done_today") val doneToday: Int,
                 val size: Int, val members: List<SquadMember>)

@Serializable
data class FreezeOrder(@SerialName("order_id") val orderId: String, val transaction: String, val price: Double,
                       val burn: Double, val symbol: String)

@Serializable
data class FreezePaid(val freezes: Int, @SerialName("burn_signature") val burnSignature: String? = null,
                      val burned: Double = 0.0)

/** One finished session from GET /history/{wallet}; every field comes from the stored server receipt. */
@Serializable
data class HistoryItem(
    @SerialName("session_id") val sessionId: String,
    @SerialName("mission_name") val missionName: String,
    val state: String,
    @SerialName("started_at") val startedAt: Double? = null,
    @SerialName("duration_seconds") val durationSeconds: Double = 0.0,
    val reason: String? = null,
    @SerialName("stake_forfeited") val stakeForfeited: Double = 0.0,
    @SerialName("stake_returned") val stakeReturned: Double = 0.0,
    @SerialName("stake_locked") val stakeLocked: Double = 0.0,
    @SerialName("unlock_at") val unlockAt: Double? = null,
    val paid: Double = 0.0,
    val symbol: String? = null,
    @SerialName("settlement_status") val settlementStatus: String? = null,
    @SerialName("explorer_url") val explorerUrl: String? = null,
)

@Serializable
data class Settlement(
    val status: String,
    val signature: String? = null,
    val network: String? = null,
    val detail: String? = null,
    @SerialName("explorer_url") val explorerUrl: String? = null,
    @SerialName("reward_token") val rewardToken: Double = 0.0,
    @SerialName("reward_symbol") val rewardSymbol: String = "SKR",
    @SerialName("reward_mint") val rewardMint: String? = null,
)

@Serializable
data class Receipt(
    val status: String,
    val mode: String,
    @SerialName("session_id") val sessionId: String,
    @SerialName("mission_name") val missionName: String,
    val assurance: String,
    @SerialName("duration_seconds") val durationSeconds: Double,
    @SerialName("checkpoint_count") val checkpointCount: Int,
    @SerialName("evidence_root") val evidenceRoot: String,
    val checks: Map<String, String>,
    val reward: Reward,
    @SerialName("league_before") val leagueBefore: String,
    @SerialName("rank_before") val rankBefore: Int,
    @SerialName("league_after") val leagueAfter: String,
    @SerialName("rank_after") val rankAfter: Int,
    val streak: Int,
    val settlement: Settlement,
)

@Serializable
data class VerifyResult(
    val verified: Boolean,
    val reason: String? = null,
    val detail: String? = null,
    val receipt: Receipt? = null,
)

@Serializable @PublishedApi internal data class ErrorBody(val error: String, val detail: String = "")

@Singleton
class AttestApi @Inject constructor(@PublishedApi internal val http: HttpClient) {
    @PublishedApi internal val json = Json { ignoreUnknownKeys = true }
    @PublishedApi internal val base = BuildConfig.BACKEND_URL.trimEnd('/')

    suspend fun health(): Health = get("/health")
    suspend fun missions(): List<MissionPolicy> = get("/missions")
    suspend fun profile(wallet: String): Profile = get("/profile/$wallet")
    suspend fun history(wallet: String): List<HistoryItem> = get("/history/$wallet")
    suspend fun live(): Live = get("/live")

    suspend fun createSession(wallet: String, missionId: String, ephemeralPubkey: String): SessionCreated =
        post("/session", mapOf("wallet" to wallet, "mission_id" to missionId, "ephemeral_pubkey" to ephemeralPubkey))

    suspend fun authorize(sessionId: String, signatureB58: String, stakeTxB64: String?): Authorized =
        post("/session/$sessionId/authorize",
            mapOf("signature" to signatureB58) + (stakeTxB64?.let { mapOf("stake_transaction" to it) } ?: emptyMap()))

    suspend fun pools(): Pools = get("/sponsor/pools")

    /** DEVELOPMENT ONLY (server refuses on mainnet): fee SOL + test tokens for a new test wallet. */
    suspend fun squad(wallet: String): Squad? = get("/squads/$wallet")
    suspend fun createSquad(wallet: String, name: String): Squad = post("/squads", mapOf("wallet" to wallet, "name" to name))
    suspend fun joinSquad(wallet: String, code: String): Squad = post("/squads/join", mapOf("wallet" to wallet, "code" to code))

    suspend fun freezeOrder(wallet: String): FreezeOrder = post("/streak/freeze", mapOf("wallet" to wallet))
    suspend fun freezePay(orderId: String, signedTxB64: String): FreezePaid =
        post("/streak/freeze/$orderId", mapOf("signature" to "-", "stake_transaction" to signedTxB64))

    suspend fun faucet(wallet: String) {
        call { http.post("$base/dev/faucet") { jsonBody(json.encodeToString(mapOf("wallet" to wallet))) } }
    }

    suspend fun checkpoint(sessionId: String, body: CheckpointBody) {
        call { http.post("$base/session/$sessionId/checkpoint") { jsonBody(json.encodeToString(body)) } }
    }

    suspend fun submitEvidence(sessionId: String, rootHex: String, signature: String) {
        call { http.post("$base/session/$sessionId/evidence") {
            jsonBody(json.encodeToString(mapOf("evidence_root" to rootHex, "signature" to signature)))
        } }
    }

    suspend fun verify(sessionId: String): VerifyResult = post("/verify", mapOf("session_id" to sessionId))

    private suspend inline fun <reified T> get(path: String): T =
        json.decodeFromString(call { http.get("$base$path") })

    private suspend inline fun <reified T> post(path: String, body: Map<String, String>): T =
        json.decodeFromString(call { http.post("$base$path") { jsonBody(json.encodeToString(body)) } })

    @PublishedApi
    internal fun HttpRequestBuilder.jsonBody(text: String) {
        contentType(ContentType.Application.Json)
        setBody(text)
    }

    /** Returns the response body, or throws AttestException with the server's failure reason. */
    @PublishedApi
    internal suspend fun call(request: suspend () -> HttpResponse): String {
        val response = try {
            request()
        } catch (e: IOException) {
            throw AttestException("NETWORK_ERROR", e.message ?: "")
        }
        val text = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val err = runCatching { json.decodeFromString<ErrorBody>(text) }.getOrNull()
            throw AttestException(err?.error ?: "HTTP_${response.status.value}", err?.detail ?: "")
        }
        return text
    }
}

@Serializable data class Live(@SerialName("facedown_now") val facedownNow: Int = 0)

@Serializable
data class CheckpointBody(
    val index: Int,
    @SerialName("timestamp_ms") val timestampMs: Long,
    @SerialName("elapsed_ms") val elapsedMs: Long,
    val foreground: Boolean,
    val interactions: Int,
    @SerialName("response_ms") val responseMs: Long,
    @SerialName("still_pct") val stillPct: Int,
    @SerialName("touch_motion") val touchMotion: Int,
    val hash: String,
    val signature: String,
)

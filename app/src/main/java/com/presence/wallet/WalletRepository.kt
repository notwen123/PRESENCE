package com.presence.wallet

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import com.presence.AppForeground
import com.presence.MainActivity
import com.presence.R
import android.content.SharedPreferences
import android.net.Uri
import com.presence.attest.AttestApi
import com.presence.attest.AttestException
import com.presence.BuildConfig
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import dagger.hilt.android.qualifiers.ApplicationContext
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.privy.auth.oAuth.OAuthProvider
import io.privy.logging.PrivyLogLevel
import io.privy.sdk.Privy
import io.privy.sdk.PrivyConfig
import io.privy.wallet.ethereum.EmbeddedEthereumWallet
import io.privy.wallet.ethereum.EthereumRpcRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.math.BigInteger
import javax.inject.Inject
import javax.inject.Singleton

data class WalletAccount(val address: String, val label: String)

/**
 * Privy on Monad testnet: "Continue with Google" creates a self-custodial embedded wallet (no seed phrase, no other
 * app). It signs the session's sign-in message and the stake / freeze transactions in-app; the app broadcasts them
 * to Monad itself. Builds without Privy IDs fall back to MetaMask below.
 *
 * MetaMask on Monad testnet. MetaMask mobile signs inside its own dapp browser: the app opens ATTEST's /metamask
 * page there (MetaMask deep link), the page asks MetaMask to sign, posts the signature to ATTEST, and the app
 * polls ATTEST until it lands. Persists only the public address. [sender] is unused (kept for call sites).
 */
@Singleton
class WalletRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: SharedPreferences,
    private val api: AttestApi,
    private val http: HttpClient,
) {
    private val privy: Privy? by lazy {
        BuildConfig.PRIVY_APP_ID.takeIf { it.isNotBlank() }?.let {
            Privy.init(context.applicationContext, PrivyConfig(appId = it, appClientId = BuildConfig.PRIVY_CLIENT_ID,
                logLevel = PrivyLogLevel.ERROR))
        }
    }
    private val usePrivy get() = BuildConfig.PRIVY_APP_ID.isNotBlank()

    /** The signed-in Google user's embedded Monad wallet; [login] opens Google sign-in if there is no session. */
    private suspend fun privyWallet(login: Boolean): EmbeddedEthereumWallet {
        val p = withContext(Dispatchers.Main) { privy } ?: throw AttestException("WALLET_NOT_FOUND", "Privy is not configured")
        val user = p.getUser() ?: if (login) p.oAuth.login(OAuthProvider.Google, PRIVY_SCHEME)
            .getOrElse { throw AttestException("WALLET_REJECTED", it.message ?: "Google sign-in was cancelled") }
        else throw AttestException("WALLET_REJECTED", "Please sign in with Google again")
        return user.embeddedEthereumWallets.firstOrNull() ?: user.createEthereumWallet()
            .getOrElse { throw AttestException("WALLET_REJECTED", "Could not create your wallet: ${it.message}") }
    }

    private suspend fun EmbeddedEthereumWallet.call(request: EthereumRpcRequest): String =
        provider.request(request).getOrElse { throw AttestException("WALLET_REJECTED", it.message ?: "Signing failed") }.data

    private suspend fun monad(method: String, vararg params: JsonElement): JsonElement {
        val body = buildJsonObject { put("jsonrpc", "2.0"); put("id", 1); put("method", method)
            put("params", buildJsonArray { params.forEach { add(it) } }) }
        val reply = Json.parseToJsonElement(http.post(MONAD_RPC) { contentType(ContentType.Application.Json); setBody(body.toString()) }.bodyAsText()).jsonObject
        reply["error"]?.let { throw AttestException("NETWORK_ERROR", it.jsonObject["message"]?.jsonPrimitive?.content ?: "Monad RPC error") }
        return reply["result"] ?: throw AttestException("NETWORK_ERROR", "Monad RPC returned nothing")
    }
    private fun JsonElement.big() = BigInteger(jsonPrimitive.content.removePrefix("0x").ifEmpty { "0" }, 16)
    private fun BigInteger.hex() = "0x" + toString(16)

    /** Privy signs [unsigned] ({to, data, value} from ATTEST) as an EIP-1559 Monad transaction; we broadcast it. */
    private suspend fun sendWithPrivy(unsigned: JsonObject): String {
        val w = privyWallet(login = false)
        val call = buildJsonObject { put("from", w.address); put("to", unsigned["to"]!!); put("data", unsigned["data"]!!)
            put("value", unsigned["value"] ?: JsonPrimitive("0x0")) }
        val gas = monad("eth_estimateGas", call).big() * BigInteger.valueOf(12) / BigInteger.TEN
        val base = monad("eth_getBlockByNumber", JsonPrimitive("latest"), JsonPrimitive(false)).jsonObject["baseFeePerGas"]!!.big()
        val tip = monad("eth_maxPriorityFeePerGas").big()
        val nonce = monad("eth_getTransactionCount", JsonPrimitive(w.address), JsonPrimitive("pending")).big()
        val tx = buildJsonObject { call.forEach { (k, v) -> put(k, v) }; put("chainId", MONAD_CHAIN_ID); put("type", 2)
            put("nonce", nonce.hex()); put("gasLimit", gas.hex()); put("maxFeePerGas", (base + tip).hex())
            put("maxPriorityFeePerGas", tip.hex()) }
        val signed = w.call(EthereumRpcRequest.ethSignTransaction(tx.toString()))
        return monad("eth_sendRawTransaction", JsonPrimitive(signed)).jsonPrimitive.content
    }

    var account: WalletAccount? = load()
        private set

    private fun load(): WalletAccount? =
        prefs.getString(KEY_ADDRESS, null)?.takeIf { it.startsWith("0x") }?.let { WalletAccount(it, walletLabel) }
    private val walletLabel get() = if (usePrivy) "Google · Privy" else "MetaMask"

    private fun openInMetaMask(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent.setPackage("io.metamask")) }
            .recoverCatching { context.startActivity(intent.setPackage(null)) }
            .getOrElse { throw AttestException("WALLET_NOT_FOUND", "Install MetaMask to continue") }
    }

    /**
     * MetaMask's browser can't open other apps, and Android won't let us jump to the front from the background,
     * so once MetaMask has signed we post a heads-up notification that brings the user straight back.
     */
    private fun notifyReturn(text: String) {
        if (AppForeground.resumed.value) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(NotificationChannel(RETURN_CHANNEL, "Wallet", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(context, 7, Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        runCatching {
            nm.notify(RETURN_ID, NotificationCompat.Builder(context, RETURN_CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("Signed in MetaMask ✓")
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build())
        }
    }

    fun clearReturnNotice() { context.getSystemService(NotificationManager::class.java)?.cancel(RETURN_ID) }

    suspend fun connect(@Suppress("UNUSED_PARAMETER") sender: ActivityResultSender): WalletAccount {
        if (usePrivy) return WalletAccount(privyWallet(login = true).address, walletLabel).also {
            prefs.edit().putString(KEY_ADDRESS, it.address).apply()
            account = it
        }
        val code = api.walletLink().code
        openInMetaMask(api.metamaskLink("code=$code"))
        repeat(90) {  // up to 3 minutes for the person to approve in MetaMask
            delay(2000)
            api.walletLinkStatus(code).address?.let { address ->
                notifyReturn("Tap to return to FACEDOWN.")
                return WalletAccount(address, "MetaMask").also {
                    prefs.edit().putString(KEY_ADDRESS, it.address).apply()
                    account = it
                }
            }
        }
        throw AttestException("WALLET_REJECTED", "MetaMask did not confirm in time")
    }

    /** Opens MetaMask to sign this session's sign-in message; returns once ATTEST has accepted the signature. */
    suspend fun authorizeSession(sessionId: String, stakeTxB64: String? = null) {
        if (usePrivy) {
            val w = privyWallet(login = false)
            val signature = w.call(EthereumRpcRequest.personalSign(api.sessionSiwe(sessionId).message, w.address))
            val stakeHash = stakeTxB64?.let { sendWithPrivy(Json.parseToJsonElement(String(android.util.Base64.decode(it, android.util.Base64.NO_WRAP))).jsonObject) }
            api.authorize(sessionId, signature, stakeHash?.let { android.util.Base64.encodeToString(it.toByteArray(), android.util.Base64.NO_WRAP) })
            return
        }
        openInMetaMask(api.metamaskLink("session=$sessionId"))
        repeat(90) {
            delay(2000)
            if (api.sessionSiwe(sessionId).state != "CREATED") { notifyReturn("Tap to return and start your session."); return }
        }
        throw AttestException("WALLET_REJECTED", "MetaMask did not sign in time")
    }

    /** Opens MetaMask to pay for a streak freeze; returns once ATTEST has confirmed the payment on Monad. */
    suspend fun payFreeze(orderId: String, txB64: String? = null) {
        if (usePrivy && txB64 != null) {
            val hash = sendWithPrivy(Json.parseToJsonElement(String(android.util.Base64.decode(txB64, android.util.Base64.NO_WRAP))).jsonObject)
            api.freezePay(orderId, android.util.Base64.encodeToString(hash.toByteArray(), android.util.Base64.NO_WRAP))
            return
        }
        openInMetaMask(api.metamaskLink("freeze=$orderId"))
        repeat(90) {
            delay(2000)
            if (api.freezeStatus(orderId).status == "PAID") { notifyReturn("Freeze bought. Tap to return."); return }
        }
        throw AttestException("WALLET_REJECTED", "MetaMask did not pay in time")
    }

    suspend fun signTransaction(@Suppress("UNUSED_PARAMETER") sender: ActivityResultSender, tx: ByteArray): ByteArray =
        throw AttestException("WALLET_REJECTED", "token payments are not available on Monad yet")

    suspend fun disconnect() {
        if (usePrivy) runCatching { withContext(Dispatchers.Main) { privy }?.logout() }
        prefs.edit().remove(KEY_ADDRESS).apply()
        account = null
    }

    private companion object {
        const val KEY_ADDRESS = "wallet_address"
        const val RETURN_CHANNEL = "wallet_return"
        const val RETURN_ID = 77
        const val PRIVY_SCHEME = "facedown-privy://"
        const val MONAD_RPC = "https://testnet-rpc.monad.xyz"
        const val MONAD_CHAIN_ID = 10143
    }
}

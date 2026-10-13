package com.presence.wallet

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import com.presence.attest.AttestApi
import com.presence.attest.AttestException
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import javax.inject.Inject
import javax.inject.Singleton

data class WalletAccount(val address: String, val label: String)

/**
 * MetaMask on Monad testnet. MetaMask mobile signs inside its own dapp browser: the app opens ATTEST's /metamask
 * page there (MetaMask deep link), the page asks MetaMask to sign, posts the signature to ATTEST, and the app
 * polls ATTEST until it lands. Persists only the public address. [sender] is unused (kept for call sites).
 */
@Singleton
class WalletRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: SharedPreferences,
    private val api: AttestApi,
) {
    var account: WalletAccount? = load()
        private set

    private fun load(): WalletAccount? =
        prefs.getString(KEY_ADDRESS, null)?.takeIf { it.startsWith("0x") }?.let { WalletAccount(it, "MetaMask") }

    private fun openInMetaMask(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent.setPackage("io.metamask")) }
            .recoverCatching { context.startActivity(intent.setPackage(null)) }
            .getOrElse { throw AttestException("WALLET_NOT_FOUND", "Install MetaMask to continue") }
    }

    suspend fun connect(@Suppress("UNUSED_PARAMETER") sender: ActivityResultSender): WalletAccount {
        val code = api.walletLink().code
        openInMetaMask(api.metamaskLink("code=$code"))
        repeat(90) {  // up to 3 minutes for the person to approve in MetaMask
            delay(2000)
            api.walletLinkStatus(code).address?.let { address ->
                return WalletAccount(address, "MetaMask").also {
                    prefs.edit().putString(KEY_ADDRESS, it.address).apply()
                    account = it
                }
            }
        }
        throw AttestException("WALLET_REJECTED", "MetaMask did not confirm in time")
    }

    /** Opens MetaMask to sign this session's sign-in message; returns once ATTEST has accepted the signature. */
    suspend fun authorizeSession(sessionId: String) {
        openInMetaMask(api.metamaskLink("session=$sessionId"))
        repeat(90) {
            delay(2000)
            if (api.sessionSiwe(sessionId).state != "CREATED") return
        }
        throw AttestException("WALLET_REJECTED", "MetaMask did not sign in time")
    }

    suspend fun signTransaction(@Suppress("UNUSED_PARAMETER") sender: ActivityResultSender, tx: ByteArray): ByteArray =
        throw AttestException("WALLET_REJECTED", "token payments are not available on Monad yet")

    fun disconnect() {
        prefs.edit().remove(KEY_ADDRESS).apply()
        account = null
    }

    private companion object {
        const val KEY_ADDRESS = "wallet_address"
    }
}

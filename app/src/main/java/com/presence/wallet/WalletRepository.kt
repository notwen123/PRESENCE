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
    suspend fun authorizeSession(sessionId: String) {
        openInMetaMask(api.metamaskLink("session=$sessionId"))
        repeat(90) {
            delay(2000)
            if (api.sessionSiwe(sessionId).state != "CREATED") { notifyReturn("Tap to return and start your session."); return }
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
        const val RETURN_CHANNEL = "wallet_return"
        const val RETURN_ID = 77
    }
}

package com.presence.wallet

import android.content.SharedPreferences
import android.net.Uri
import com.funkatronics.encoders.Base58
import com.presence.attest.AttestException
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.AdapterOperations
import com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import com.solana.mobilewalletadapter.clientlib.successPayload
import javax.inject.Inject
import javax.inject.Singleton

data class WalletAccount(val address: String, val label: String)

/** How FACEDOWN introduces itself to the wallet. The domain matches the server's sign-in domain. */
object WalletIdentity {
    val uri: Uri = Uri.parse("https://presence.local")
    val icon: Uri = Uri.parse("favicon.ico")
    const val name = "FACEDOWN"
}

/**
 * Mobile Wallet Adapter (Seed Vault on Seeker). Persists only the public address, label and the
 * MWA auth token (app-private prefs; backups are disabled in the manifest).
 */
@Singleton
class WalletRepository @Inject constructor(
    private val adapter: MobileWalletAdapter,
    private val prefs: SharedPreferences,
) {
    var account: WalletAccount? = load()
        private set

    private fun load(): WalletAccount? {
        val address = prefs.getString(KEY_ADDRESS, null) ?: return null
        prefs.getString(KEY_TOKEN, null)?.let { adapter.authToken = it } ?: return null
        return WalletAccount(address, prefs.getString(KEY_LABEL, "") ?: "")
    }

    suspend fun connect(sender: ActivityResultSender): WalletAccount =
        when (val result = adapter.connect(sender)) {
            is TransactionResult.Success -> {
                val auth = result.authResult
                WalletAccount(Base58.encodeToString(auth.publicKey), auth.accountLabel ?: "Wallet").also {
                    prefs.edit()
                        .putString(KEY_ADDRESS, it.address)
                        .putString(KEY_LABEL, it.label)
                        .putString(KEY_TOKEN, auth.authToken)
                        .apply()
                    account = it
                }
            }
            is TransactionResult.NoWalletFound -> throw AttestException("WALLET_NOT_FOUND")
            is TransactionResult.Failure -> throw AttestException("WALLET_REJECTED", result.e.message ?: "")
        }

    /** Detached ed25519 signature of [message] by the connected account (SIWS-format text). */
    suspend fun signMessage(sender: ActivityResultSender, message: String): ByteArray {
        val current = account ?: throw AttestException("WALLET_NOT_CONNECTED")
        val address = Base58.decode(current.address)
        val result = walletTransact(sender) {
            signMessagesDetached(arrayOf(message.toByteArray()), arrayOf(address))
        }
        return when (result) {
            is TransactionResult.Success ->
                result.successPayload?.messages?.firstOrNull()?.signatures?.firstOrNull()
                    ?: throw AttestException("INVALID_SIGNATURE", "wallet returned no signature")
            is TransactionResult.NoWalletFound -> throw AttestException("WALLET_NOT_FOUND")
            is TransactionResult.Failure -> throw AttestException("WALLET_REJECTED", result.e.message ?: "")
        }
    }

    /**
     * One wallet session for starting a mission: sign the SIWS-format message and, if the mission is
     * staked, the stake transaction ATTEST issued. ATTEST checks the transaction is byte-identical
     * before submitting it, so the wallet can only ever approve the exact stake it was shown.
     */
    suspend fun signSession(sender: ActivityResultSender, message: String, stakeTx: ByteArray?): Pair<ByteArray, ByteArray?> {
        val current = account ?: throw AttestException("WALLET_NOT_CONNECTED")
        val address = Base58.decode(current.address)
        val result = walletTransact(sender) {
            val signed = signMessagesDetached(arrayOf(message.toByteArray()), arrayOf(address))
            val stake = stakeTx?.let { signTransactions(arrayOf(it)) }
            Pair(signed, stake)
        }
        return when (result) {
            is TransactionResult.Success -> {
                val (signed, stake) = result.payload
                val sig = signed.messages.firstOrNull()?.signatures?.firstOrNull()
                    ?: throw AttestException("INVALID_SIGNATURE", "wallet returned no signature")
                val stakeSigned = stake?.signedPayloads?.firstOrNull()
                if (stakeTx != null && stakeSigned == null) throw AttestException("STAKE_FAILED", "wallet did not sign the stake")
                sig to stakeSigned
            }
            is TransactionResult.NoWalletFound -> throw AttestException("WALLET_NOT_FOUND")
            is TransactionResult.Failure -> throw AttestException("WALLET_REJECTED", result.e.message ?: "")
        }
    }

    /** Sign one transaction ATTEST built (e.g. a streak-freeze payment); ATTEST submits it. */
    suspend fun signTransaction(sender: ActivityResultSender, tx: ByteArray): ByteArray {
        val result = walletTransact(sender) { signTransactions(arrayOf(tx)) }
        return when (result) {
            is TransactionResult.Success -> result.payload.signedPayloads.firstOrNull()
                ?: throw AttestException("WALLET_REJECTED", "wallet returned no transaction")
            is TransactionResult.NoWalletFound -> throw AttestException("WALLET_NOT_FOUND")
            is TransactionResult.Failure -> throw AttestException("WALLET_REJECTED", result.e.message ?: "")
        }
    }

    /**
     * Every wallet call goes through here. Wallets rotate the auth token when they approve a request
     * (Solflare does), so the newest one is saved each time; a saved token the wallet no longer accepts
     * ("authorization request failed", e.g. after an app restart restored an old one) is dropped and the
     * call is retried once with a fresh authorization.
     */
    private suspend fun <T> walletTransact(
        sender: ActivityResultSender,
        block: suspend AdapterOperations.(MobileWalletAdapterClient.AuthorizationResult) -> T,
    ): TransactionResult<T> {
        var result = adapter.transact(sender, block = block)
        if (result is TransactionResult.Failure && adapter.authToken != null &&
            "authoriz" in (result.e.message ?: "").lowercase()) {
            adapter.authToken = null
            result = adapter.transact(sender, block = block)
        }
        if (result is TransactionResult.Success) prefs.edit().putString(KEY_TOKEN, result.authResult.authToken).apply()
        return result
    }

    fun disconnect() {
        prefs.edit().remove(KEY_ADDRESS).remove(KEY_LABEL).remove(KEY_TOKEN).apply()
        adapter.authToken = null
        account = null
    }

    private companion object {
        const val KEY_ADDRESS = "wallet_address"
        const val KEY_LABEL = "wallet_label"
        const val KEY_TOKEN = "wallet_auth_token"
    }
}

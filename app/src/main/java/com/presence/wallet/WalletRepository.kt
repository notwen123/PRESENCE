package com.presence.wallet

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import com.presence.attest.AttestException
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import dagger.hilt.android.qualifiers.ApplicationContext
import io.metamask.androidsdk.DappMetadata
import io.metamask.androidsdk.Ethereum
import io.metamask.androidsdk.EthereumFlow
import io.metamask.androidsdk.EthereumMethod
import io.metamask.androidsdk.EthereumRequest
import io.metamask.androidsdk.Result
import io.metamask.androidsdk.SDKOptions
import javax.inject.Inject
import javax.inject.Singleton

data class WalletAccount(val address: String, val label: String)

/** How FACEDOWN introduces itself to the wallet. The domain matches the server's sign-in domain. */
object WalletIdentity {
    val uri: Uri = Uri.parse("https://presence.local")
    const val name = "FACEDOWN"
}

/**
 * MetaMask (via the MetaMask Android SDK) on Monad testnet. The account is an EVM address; sign-in is an
 * EIP-4361 message signed with personal_sign and verified by ATTEST. Persists only the public address.
 * [sender] parameters are kept so call sites stay unchanged; MetaMask opens itself through its SDK.
 */
@Singleton
class WalletRepository @Inject constructor(
    @ApplicationContext context: Context,
    private val prefs: SharedPreferences,
) {
    private val metamask = EthereumFlow(Ethereum(context, DappMetadata(WalletIdentity.name, WalletIdentity.uri.toString()),
        SDKOptions("", mapOf(MONAD_TESTNET to "https://testnet-rpc.monad.xyz"))))

    var account: WalletAccount? = load()
        private set

    private fun load(): WalletAccount? =
        prefs.getString(KEY_ADDRESS, null)?.takeIf { it.startsWith("0x") }?.let { WalletAccount(it, "MetaMask") }

    suspend fun connect(@Suppress("UNUSED_PARAMETER") sender: ActivityResultSender): WalletAccount =
        when (val r = metamask.connect()) {
            is Result.Success -> {
                val address = (r as? Result.Success.Item)?.value?.takeIf { it.startsWith("0x") } ?: metamask.selectedAddress
                if (address.isBlank()) throw AttestException("WALLET_REJECTED", "MetaMask returned no account")
                WalletAccount(address, "MetaMask").also {
                    prefs.edit().putString(KEY_ADDRESS, it.address).apply()
                    account = it
                }
            }
            is Result.Error -> throw AttestException("WALLET_REJECTED", r.error.message)
        }

    /** personal_sign of [message] (EIP-191); returns the 65-byte signature. */
    suspend fun signMessage(@Suppress("UNUSED_PARAMETER") sender: ActivityResultSender, message: String): ByteArray {
        val current = account ?: throw AttestException("WALLET_NOT_CONNECTED")
        if (metamask.selectedAddress.isBlank()) metamask.connect()
        val r = metamask.sendRequest(EthereumRequest(method = EthereumMethod.PERSONAL_SIGN.value,
            params = listOf(message, current.address)))
        val hex = (r as? Result.Success.Item)?.value
            ?: throw AttestException("WALLET_REJECTED", (r as? Result.Error)?.error?.message ?: "MetaMask returned no signature")
        return hex.removePrefix("0x").chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }

    /** Sign in for a mission. Monad missions carry no stake, so there is never a stake transaction to sign. */
    suspend fun signSession(sender: ActivityResultSender, message: String, stakeTx: ByteArray?): Pair<ByteArray, ByteArray?> {
        if (stakeTx != null) throw AttestException("STAKE_FAILED", "staked missions are not available on Monad yet")
        return signMessage(sender, message) to null
    }

    suspend fun signTransaction(@Suppress("UNUSED_PARAMETER") sender: ActivityResultSender, tx: ByteArray): ByteArray =
        throw AttestException("WALLET_REJECTED", "token payments are not available on Monad yet")

    fun disconnect() {
        prefs.edit().remove(KEY_ADDRESS).apply()
        account = null
    }

    private companion object {
        const val KEY_ADDRESS = "wallet_address"
        const val MONAD_TESTNET = "0x279f" // chain id 10143
    }
}

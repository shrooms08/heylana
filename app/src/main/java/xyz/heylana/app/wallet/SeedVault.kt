package xyz.heylana.app.wallet

import android.net.Uri
import androidx.activity.ComponentActivity
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import org.sol4k.Base58
import xyz.heylana.app.BuildConfig
import com.solana.mobilewalletadapter.clientlib.protocol.JsonRpc20Client
import com.solana.mobilewalletadapter.clientlib.protocol.MobileWalletAdapterClient
import xyz.heylana.app.HeylanaLog
import xyz.heylana.app.brain.AddressText

/**
 * Talking to the user's wallet — Seed Vault Wallet on the Seeker — over Mobile
 * Wallet Adapter.
 *
 * Heylana holds no key. It asks the wallet to do two things, and the user
 * approves each one in the wallet's own screen:
 *
 *  - **connect**: authorise, then sign a plain sign-in message the worker made
 *    (proof the wallet is theirs, never a transaction);
 *  - **pay**: sign and send the one Pro payment transaction.
 *
 * Must be created while the activity is being created, before it starts: the
 * wallet is opened for a result, and Android only lets that be registered early.
 */
class SeedVault(activity: ComponentActivity) {

    private val sender = ActivityResultSender(activity)

    private val adapter = MobileWalletAdapter(
        connectionIdentity = ConnectionIdentity(
            identityUri = Uri.parse(BuildConfig.PROXY_URL.trimEnd('/').ifEmpty { FALLBACK_IDENTITY_URI }),
            iconUri = Uri.parse(ICON_PATH),
            identityName = IDENTITY_NAME
        ),
        // The user may take a while to approve in Seed Vault. At the library's
        // 90-second default a slow approval lost the signed result.
        timeout = CLIENT_TIMEOUT_MS
    )

    /** How a trip to the wallet ended. */
    sealed interface Trip<out T> {
        data class Done<T>(val value: T) : Trip<T>

        /** There is no wallet app on this phone that speaks Mobile Wallet Adapter. */
        data object NoWallet : Trip<Nothing>

        /** Declined, dismissed, or failed; [problem] is already in plain words. */
        data class Stopped(val problem: WalletProblem) : Trip<Nothing>
    }

    /** What a connection produced: who, and the signed sign-in message. */
    data class SignedIn(val pubkey: String, val nonce: String, val signature: String)

    /**
     * Authorise, fetch a challenge for the account the wallet chose, and have the
     * wallet sign it — all in one visit to the wallet, so the user approves once
     * for the connection and once for the signature.
     */
    suspend fun connect(api: WalletApi, cluster: Cluster): Trip<SignedIn> {
        HeylanaLog.state("wallet: connecting on ${cluster.id}")
        adapter.blockchain = cluster.blockchain
        val result = adapter.transact(sender) { auth ->
            val account = auth.accounts.firstOrNull()?.publicKey
                ?: throw WalletStop(WalletProblem.UNKNOWN)
            val pubkey = Base58.encode(account)
            when (val challenge = api.challenge(pubkey)) {
                is Answer.Ok -> {
                    val (nonce, message) = challenge.value
                    val signed = signMessagesDetached(
                        arrayOf(message.toByteArray(Charsets.UTF_8)),
                        arrayOf(account)
                    )
                    val signature = signed.messages.first().signatures.first()
                    SignedIn(pubkey, nonce, Base58.encode(signature))
                }
                is Answer.Refused -> throw WalletStop(WalletProblem.fromWorker(challenge.reason))
                is Answer.Unreachable -> throw WalletStop(WalletProblem.UNREACHABLE)
            }
        }
        return finish(result)
    }

    /** Hands the unsigned payment to the wallet to sign and send; returns its signature. */
    suspend fun pay(unsignedTransaction: ByteArray, cluster: Cluster): Trip<String> {
        HeylanaLog.state("wallet: asking to sign and send the payment on ${cluster.id}")
        adapter.blockchain = cluster.blockchain
        val result = adapter.transact(sender) {
            val sent = signAndSendTransactions(arrayOf(unsignedTransaction))
            Base58.encode(sent.signatures.first())
        }
        return finish(result)
    }

    private fun <T> finish(result: TransactionResult<T>): Trip<T> = when (result) {
        is TransactionResult.Success -> {
            HeylanaLog.state("wallet: raw result success")
            Trip.Done(result.payload)
        }
        is TransactionResult.NoWalletFound -> {
            HeylanaLog.state("wallet: raw result no wallet found")
            Trip.NoWallet
        }
        is TransactionResult.Failure -> {
            val chain = generateSequence(result.e as Throwable?) { it.cause }.toList()
            val stop = chain.filterIsInstance<WalletStop>().firstOrNull()
            val remote = chain.filterIsInstance<JsonRpc20Client.JsonRpc20RemoteException>().firstOrNull()
            val signed = chain.filterIsInstance<MobileWalletAdapterClient.NotSubmittedException>()
                .firstOrNull()?.signatures?.size ?: 0
            val message = (result.message ?: result.e?.message).orEmpty()
            // What the wallet actually returned: types, its error code, and the
            // message with any address shortened.
            HeylanaLog.state(
                "wallet: raw result failure types=${chain.joinToString(">") { it.javaClass.simpleName }} " +
                    "code=${remote?.code ?: "none"} signatures=$signed " +
                    "message=${AddressText.shorten(message).replace('\n', ' ').take(RAW_MESSAGE_CHARS)}"
            )
            // A timeout is not a no: the wallet may have signed and sent anyway.
            val timedOut = chain.any { it is java.util.concurrent.TimeoutException }
            val problem = stop?.problem
                ?: if (timedOut) WalletProblem.UNKNOWN
                else WalletProblem.fromRemote(remote?.code, result.e?.javaClass?.simpleName, message)
            HeylanaLog.state("wallet: stopped ${problem.name}")
            Trip.Stopped(problem)
        }
    }

    /** Carries a problem we already understand out of the wallet session. */
    private class WalletStop(val problem: WalletProblem) : Exception(problem.name)

    private companion object {
        /**
         * Seed Vault shows who is asking and fetches the icon from this address.
         * heylana.xyz does not exist yet, so it is Heylana's worker, which serves
         * the mark; this is only used if a build has no worker address at all.
         */
        const val FALLBACK_IDENTITY_URI = "https://heylana.xyz"

        /** Relative to the identity address: the wallet shows the mark from there. */
        const val ICON_PATH = "heylana-mark.png"
        const val IDENTITY_NAME = "Heylana"
        const val RAW_MESSAGE_CHARS = 160
        const val CLIENT_TIMEOUT_MS = 180_000
    }
}

package xyz.heylana.app.wallet

import android.net.Uri
import androidx.activity.ComponentActivity
import com.solana.mobilewalletadapter.clientlib.ActivityResultSender
import com.solana.mobilewalletadapter.clientlib.ConnectionIdentity
import com.solana.mobilewalletadapter.clientlib.MobileWalletAdapter
import com.solana.mobilewalletadapter.clientlib.TransactionResult
import org.sol4k.Base58
import xyz.heylana.app.HeylanaLog

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
            identityUri = Uri.parse(IDENTITY_URI),
            iconUri = Uri.parse(ICON_PATH),
            identityName = IDENTITY_NAME
        )
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
        is TransactionResult.Success -> Trip.Done(result.payload)
        is TransactionResult.NoWalletFound -> Trip.NoWallet
        is TransactionResult.Failure -> {
            val stop = generateSequence(result.e as Throwable?) { it.cause }
                .filterIsInstance<WalletStop>().firstOrNull()
            val problem = stop?.problem ?: WalletProblem.from(result.e?.javaClass?.simpleName, result.message)
            HeylanaLog.state("wallet: stopped ${problem.name}")
            Trip.Stopped(problem)
        }
    }

    /** Carries a problem we already understand out of the wallet session. */
    private class WalletStop(val problem: WalletProblem) : Exception(problem.name)

    private companion object {
        const val IDENTITY_URI = "https://heylana.xyz"

        /** Relative to the identity address: the wallet shows the mark from there. */
        const val ICON_PATH = "heylana-mark.png"
        const val IDENTITY_NAME = "Heylana"
    }
}

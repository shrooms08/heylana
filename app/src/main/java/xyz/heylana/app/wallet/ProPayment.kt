package xyz.heylana.app.wallet

import kotlinx.coroutines.delay
import org.sol4k.PublicKey
import xyz.heylana.app.HeylanaLog

/** How paying for Pro ended. */
sealed interface PayOutcome {
    data class Paid(val standing: Standing) : PayOutcome
    data class Stopped(val problem: WalletProblem) : PayOutcome
}

/**
 * One Pro payment, start to finish: a fresh blockhash from the worker, the
 * transfer built on the phone, Seed Vault to sign and send it, then the worker
 * asked until it has seen the payment on chain.
 *
 * Heylana never holds a key and never sends the transaction itself; the wallet
 * does both, after the user approves it there. The cluster goes to both the
 * blockhash and the wallet, so the transaction is made for the chain it lands on.
 */
class ProPayment(
    private val blockhash: suspend (Cluster) -> Answer<String>,
    private val signAndSend: suspend (ByteArray, Cluster) -> SeedVault.Trip<String>,
    private val confirm: suspend (reference: String, signature: String) -> Answer<Standing>,
    private val log: (String) -> Unit = { HeylanaLog.state(it) }
) {

    constructor(api: WalletApi, seedVault: SeedVault) : this(
        blockhash = { cluster -> api.blockhash(cluster) },
        signAndSend = { transaction, cluster -> seedVault.pay(transaction, cluster) },
        confirm = { reference, signature -> api.confirm(reference, signature) }
    )

    /**
     * [onSent] is called the moment the wallet hands back a signature, before
     * any waiting — so a payment that confirms late can still be claimed.
     */
    suspend fun pay(
        session: WalletSession,
        quote: Quote,
        cluster: Cluster,
        onSent: (reference: String, signature: String) -> Unit
    ): PayOutcome {
        val recent = when (val answer = blockhash(cluster)) {
            is Answer.Ok -> answer.value
            is Answer.Refused -> return PayOutcome.Stopped(WalletProblem.fromWorker(answer.reason))
            is Answer.Unreachable -> return PayOutcome.Stopped(WalletProblem.UNREACHABLE)
        }

        val unsigned = runCatching {
            PaymentTransaction.serializeUnsigned(
                PaymentTransaction.Order(
                    payer = PublicKey(session.pubkey),
                    mint = PublicKey(quote.mint),
                    tokenProgram = PublicKey(quote.tokenProgram),
                    treasury = PublicKey(quote.treasury),
                    amount = quote.amount,
                    decimals = quote.decimals,
                    reference = PublicKey(quote.reference),
                    recentBlockhash = recent
                )
            )
        }.getOrElse {
            log("pay: could not build the transfer")
            return PayOutcome.Stopped(WalletProblem.UNKNOWN)
        }

        val signature = when (val trip = signAndSend(unsigned, cluster)) {
            is SeedVault.Trip.Done -> trip.value
            SeedVault.Trip.NoWallet -> return PayOutcome.Stopped(WalletProblem.NO_WALLET)
            is SeedVault.Trip.Stopped -> return PayOutcome.Stopped(trip.problem)
        }
        onSent(quote.reference, signature)
        log("pay: sent on ${cluster.id}, waiting for confirmation")

        return ConfirmPoll.await(check = { confirm(quote.reference, signature) })
    }
}

/**
 * Asking the worker whether the payment has landed, for up to a minute.
 *
 * "Not confirmed yet", a server hiccup and a dropped connection all mean ask
 * again; anything else the worker says is its final word on this payment.
 */
object ConfirmPoll {

    const val TIMEOUT_MS = 60_000L
    const val INTERVAL_MS = 2_000L

    suspend fun await(
        check: suspend () -> Answer<Standing>,
        now: () -> Long = System::currentTimeMillis,
        sleep: suspend (Long) -> Unit = { delay(it) },
        timeoutMs: Long = TIMEOUT_MS,
        intervalMs: Long = INTERVAL_MS
    ): PayOutcome {
        val deadline = now() + timeoutMs
        while (true) {
            when (val answer = check()) {
                is Answer.Ok -> return PayOutcome.Paid(answer.value)
                is Answer.Refused -> if (!keepWaiting(answer)) {
                    return PayOutcome.Stopped(WalletProblem.fromWorker(answer.reason))
                }
                is Answer.Unreachable -> Unit
            }
            if (now() + intervalMs > deadline) return PayOutcome.Stopped(WalletProblem.TOOK_TOO_LONG)
            sleep(intervalMs)
        }
    }

    fun keepWaiting(refused: Answer.Refused): Boolean =
        refused.reason == "not_confirmed" || refused.code >= SERVER_ERROR || refused.code == TOO_MANY

    private const val SERVER_ERROR = 500
    private const val TOO_MANY = 429
}

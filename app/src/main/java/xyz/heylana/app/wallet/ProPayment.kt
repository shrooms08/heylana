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
    /** [signature] is null when the wallet gave none: the worker then looks the payment up by its reference. */
    private val confirm: suspend (reference: String, signature: String?) -> Answer<Standing>,
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
            is SeedVault.Trip.Stopped -> {
                // Declined or short of funds: nothing was paid.
                if (trip.problem != WalletProblem.UNKNOWN && trip.problem != WalletProblem.TOOK_TOO_LONG) {
                    return PayOutcome.Stopped(trip.problem)
                }
                // The wallet may have paid anyway. The reference is on the payment,
                // so it can be found without a signature — and is remembered in
                // case it lands after this minute.
                log("pay: wallet gave no signature (${trip.problem.name}), looking for the payment by its reference")
                onSent(quote.reference, "")
                return ConfirmPoll.await(check = { confirm(quote.reference, null) }, log = log)
            }
        }
        onSent(quote.reference, signature)
        log("pay: sent on ${cluster.id}, waiting for confirmation")

        return ConfirmPoll.await(check = { confirm(quote.reference, signature) }, log = log)
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

    /** Growing waits (2s, 3s, 5s…) for up to a minute; each look logged with its result. */
    suspend fun await(
        check: suspend () -> Answer<Standing>,
        sleep: suspend (Long) -> Unit = { delay(it) },
        timeoutMs: Long = TIMEOUT_MS,
        log: (String) -> Unit = {}
    ): PayOutcome {
        val waits = Backoff.delays(timeoutMs)
        var attempt = 0
        while (true) {
            attempt++
            val answer = check()
            log("pay: check #$attempt result=${describe(answer)}")
            when (answer) {
                is Answer.Ok -> return PayOutcome.Paid(answer.value)
                is Answer.Refused -> if (!keepWaiting(answer)) {
                    return PayOutcome.Stopped(WalletProblem.fromWorker(answer.reason))
                }
                is Answer.Unreachable -> Unit
            }
            if (attempt > waits.size) return PayOutcome.Stopped(WalletProblem.TOOK_TOO_LONG)
            sleep(waits[attempt - 1])
        }
    }

    fun keepWaiting(refused: Answer.Refused): Boolean =
        refused.reason == "not_confirmed" || refused.code >= SERVER_ERROR || refused.code == TOO_MANY

    private const val SERVER_ERROR = 500
    private const val TOO_MANY = 429
}

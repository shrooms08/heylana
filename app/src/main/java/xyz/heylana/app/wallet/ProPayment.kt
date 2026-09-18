package xyz.heylana.app.wallet

import kotlinx.coroutines.delay
import xyz.heylana.app.HeylanaLog

/** How paying for Pro ended. */
sealed interface PayOutcome {
    data class Paid(val standing: Standing) : PayOutcome
    data class Stopped(val problem: WalletProblem, val line: String = problem.words) : PayOutcome
}

/**
 * One Pro payment, the safe way: the worker builds the exact transfer from the quote
 * and simulates it; only a passing simulation comes back as bytes; the app checks they
 * are the quoted payment to the treasury; Seed Vault, where the user approves or
 * rejects it; then the worker is asked until it has seen the payment on chain.
 *
 * A failed simulation never opens the wallet, and a rejection is never retried.
 * Heylana never holds a key and never sends the transaction itself.
 */
class ProPayment(
    private val build: suspend (reference: String, cluster: Cluster) -> Answer<BuiltTransfer>,
    private val signAndSend: suspend (ByteArray, Cluster) -> SeedVault.Trip<String>,
    /** [signature] is null when the wallet gave none: the worker then looks the payment up by its reference. */
    private val confirm: suspend (reference: String, signature: String?) -> Answer<Standing>,
    private val log: (String) -> Unit = { HeylanaLog.state(it) }
) {

    constructor(api: WalletApi, seedVault: SeedVault) : this(
        // Pay was tapped: the worker's confirmation token first, then the bytes (R3).
        build = { reference, cluster -> api.confirmedBuild("pay", reference, cluster) },
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
        log("pay: building and simulating cluster=${cluster.id}")
        val built = when (val answer = build(quote.reference, cluster)) {
            is Answer.Ok -> answer.value
            is Answer.Refused -> return PayOutcome.Stopped(
                WalletProblem.fromWorker(answer.reason),
                answer.detail.ifBlank { WalletProblem.fromWorker(answer.reason).words }
            )
            is Answer.Unreachable -> return PayOutcome.Stopped(WalletProblem.UNREACHABLE)
        }
        val simulation = built.simulation
        if (simulation is SimulationResult.Failed) {
            log("pay: simulation failed reason=${simulation.reason}, wallet not opened")
            return PayOutcome.Stopped(WalletProblem.UNKNOWN, BuildText.failed(simulation.words))
        }
        val bytes = built.transaction
        val matches = bytes != null && BuiltCheck.check(
            bytes,
            BuiltCheck.Expected(
                from = session.pubkey, to = quote.treasury, mint = quote.mint,
                tokenProgram = quote.tokenProgram, units = quote.amount
            )
        ) == BuiltCheck.Verdict.Matches
        if (bytes == null || !matches) {
            log("pay: built transfer differs from the quote, wallet not opened")
            return PayOutcome.Stopped(WalletProblem.UNKNOWN, BuildText.NOT_WHAT_WAS_CONFIRMED)
        }

        log("pay: simulation passed, opening Seed Vault cluster=${cluster.id}")
        val signature = when (val trip = signAndSend(bytes, cluster)) {
            is SeedVault.Trip.Done -> trip.value
            SeedVault.Trip.NoWallet -> return PayOutcome.Stopped(WalletProblem.NO_WALLET)
            is SeedVault.Trip.Stopped -> {
                if (trip.problem == WalletProblem.CANCELLED) {
                    return PayOutcome.Stopped(WalletProblem.CANCELLED, BuildText.REJECTED)
                }
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
                is Answer.Refused -> {
                    if (answer.code == GONE) return PayOutcome.Stopped(WalletProblem.UNKNOWN, BuildText.EXPIRED)
                    if (!keepWaiting(answer)) return PayOutcome.Stopped(WalletProblem.fromWorker(answer.reason))
                }
                is Answer.Unreachable -> Unit
            }
            if (attempt > waits.size) return PayOutcome.Stopped(WalletProblem.TOOK_TOO_LONG)
            sleep(waits[attempt - 1])
        }
    }

    fun keepWaiting(refused: Answer.Refused): Boolean =
        refused.reason == "not_confirmed" || refused.code >= SERVER_ERROR || refused.code == TOO_MANY

    private const val GONE = 410
    private const val SERVER_ERROR = 500
    private const val TOO_MANY = 429
}

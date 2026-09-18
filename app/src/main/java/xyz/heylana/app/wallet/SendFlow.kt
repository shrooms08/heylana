package xyz.heylana.app.wallet

import kotlinx.coroutines.delay
import xyz.heylana.app.HeylanaLog

/** How a confirmed send ended, told back to the buddy. */
sealed interface SendResult {
    /** [autoSigned]: the wallet signed too fast for anyone to have approved it (it trusts Heylana). */
    data class Sent(val shortSignature: String, val autoSigned: Boolean = false) : SendResult
    data class Stopped(val line: String) : SendResult
}

/** Where a confirmed send has got to, for the mode chip: simulating, approve in wallet, sent. */
enum class SendStage { SIMULATING, APPROVE_IN_WALLET, CHECKING }

/** Carries progress and the result from [SendActivity] to the overlay service, in the same process. */
object SendRelay {
    @Volatile
    var listener: ((SendResult) -> Unit)? = null

    @Volatile
    var progress: ((SendStage) -> Unit)? = null
}

/**
 * A confirmed send, the safe way: the worker builds the exact transfer and simulates
 * it again on the send's cluster; only a passing simulation comes back as bytes; the
 * app checks those bytes are the transfer the user confirmed; then Seed Vault, where
 * the user approves or rejects it; then the chain, asked with growing waits.
 *
 * A failed simulation, a wrong network or bytes that do not match never open the
 * wallet. A rejection is final and never retried. A signature is never submitted a
 * second time: when the outcome is unknown, the send is looked for on chain by its
 * reference, and "not found" says to check the wallet, never to try again.
 */
class SendFlow(
    /** The final build: fresh blockhash, fresh simulation, bytes only if it passed. */
    private val build: suspend (id: String, cluster: Cluster) -> Answer<BuiltTransfer>,
    private val signAndSend: suspend (ByteArray, Cluster) -> SeedVault.Trip<SeedVault.Signed>,
    /** [signature] is null when the wallet gave none: the worker then looks for the transfer itself. */
    private val confirm: suspend (id: String, signature: String?) -> Answer<String>,
    private val log: (String) -> Unit = { HeylanaLog.state(it) },
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    private val stage: (SendStage) -> Unit = {}
) {

    constructor(api: WalletApi, seedVault: SeedVault, stage: (SendStage) -> Unit = {}) : this(
        // Confirm was tapped: the worker's confirmation token first, then the bytes (R3).
        build = { id, cluster -> api.confirmedBuild("send", id, cluster) },
        signAndSend = { transaction, cluster -> seedVault.pay(transaction, cluster) },
        confirm = { id, signature -> api.confirmSend(id, signature) },
        stage = stage
    )

    /** Everything the transfer needs, and nothing the user did not confirm. */
    data class Request(
        val id: String,
        val to: String,
        val mint: String?,
        val tokenProgram: String?,
        val units: Long,
        val decimals: Int,
        val cluster: Cluster
    ) {
        companion object {
            fun of(quote: SendQuote) = Request(
                id = quote.id,
                to = quote.toAddress,
                mint = quote.mint,
                tokenProgram = quote.tokenProgram,
                units = quote.units,
                decimals = quote.decimals,
                cluster = quote.cluster
            )
        }
    }

    suspend fun run(request: Request, from: String): SendResult {
        stage(SendStage.SIMULATING)
        log("send: building and simulating cluster=${request.cluster.id}")
        val built = when (val answer = build(request.id, request.cluster)) {
            is Answer.Ok -> answer.value
            is Answer.Refused -> {
                log("send: not built reason=${answer.reason} cluster=${request.cluster.id}")
                return SendResult.Stopped(answer.detail.ifBlank { WalletProblem.fromWorker(answer.reason).words })
            }
            is Answer.Unreachable -> return SendResult.Stopped(WalletProblem.UNREACHABLE.words)
        }
        val simulation = built.simulation
        if (simulation is SimulationResult.Failed) {
            log("send: simulation failed reason=${simulation.reason}, wallet not opened")
            return SendResult.Stopped(BuildText.failed(simulation.words))
        }
        val bytes = built.transaction ?: return SendResult.Stopped(BuildText.NOT_WHAT_WAS_CONFIRMED)
        val verdict = BuiltCheck.check(
            bytes,
            BuiltCheck.Expected(from = from, to = request.to, mint = request.mint, tokenProgram = request.tokenProgram, units = request.units)
        )
        if (verdict is BuiltCheck.Verdict.Differs) {
            log("send: built transfer differs from the confirmed one why=${verdict.why}, wallet not opened")
            return SendResult.Stopped(BuildText.NOT_WHAT_WAS_CONFIRMED)
        }

        stage(SendStage.APPROVE_IN_WALLET)
        log("send: simulation passed, opening Seed Vault cluster=${request.cluster.id}")
        val signed = when (val trip = signAndSend(bytes, request.cluster)) {
            is SeedVault.Trip.Done -> trip.value
            SeedVault.Trip.NoWallet -> return SendResult.Stopped(WalletProblem.NO_WALLET.words)
            is SeedVault.Trip.Stopped -> {
                // Rejected in the wallet: nothing was submitted, and it is never asked again.
                if (trip.problem == WalletProblem.CANCELLED) {
                    log("send: rejected in the wallet")
                    return SendResult.Stopped(BuildText.REJECTED)
                }
                if (trip.problem !in UNSURE) return SendResult.Stopped(trip.problem.words)
                // Anything else, a timeout included, may still have gone through.
                log("send: wallet gave no signature (${trip.problem.name}), looking for it on chain cluster=${request.cluster.id}")
                stage(SendStage.CHECKING)
                return awaitLanded(request.id, null)
            }
        }
        stage(SendStage.CHECKING)
        val automatic = BuildText.signedAutomatically(signed.afterOpenMs)
        log("send: signed in Seed Vault after_open_ms=${signed.afterOpenMs} automatic=$automatic, waiting for it to land cluster=${request.cluster.id}")
        val landed = awaitLanded(request.id, signed.signature)
        return if (landed is SendResult.Sent && automatic) landed.copy(autoSigned = true) else landed
    }

    /**
     * Asks the worker whether the send has landed, with growing waits (2s, 3s, 5s…) for up
     * to a minute. Landed is "Sent"; a blockhash that ran out with nothing on chain is
     * expired; a transfer that is not this send, or not yours, ends it at once; anything
     * else is asked again. Every look is logged with its result.
     */
    private suspend fun awaitLanded(id: String, signature: String?): SendResult {
        val waits = Backoff.delays(LAND_TIMEOUT_MS)
        val started = now()
        var attempt = 0
        while (true) {
            attempt++
            val answer = confirm(id, signature)
            log(
                "send: check #$attempt after=${now() - started}ms " +
                    "signature=${if (signature == null) "none" else "given"} result=${describe(answer)}"
            )
            when (answer) {
                is Answer.Ok -> return SendResult.Sent(answer.value)
                is Answer.Refused -> when (answer.code) {
                    MISMATCH, NOT_YOURS -> return SendResult.Stopped(DID_NOT_MATCH)
                    GONE -> return SendResult.Stopped(BuildText.EXPIRED)
                }
                is Answer.Unreachable -> Unit
            }
            if (attempt > waits.size) {
                return SendResult.Stopped(if (signature == null) BuildText.NOT_FOUND else BuildText.UNKNOWN_SIGNED)
            }
            sleep(waits[attempt - 1])
        }
    }

    companion object {
        const val LAND_TIMEOUT_MS = 60_000L
        private const val MISMATCH = 402
        private const val NOT_YOURS = 403
        private const val GONE = 410

        /** What the wallet can end with even after a send went out, a timeout among them. */
        private val UNSURE = setOf(WalletProblem.UNKNOWN, WalletProblem.TOOK_TOO_LONG)

        const val DID_NOT_MATCH =
            "That send didn't land the way it was prepared. Check your wallet before trying again."
    }
}

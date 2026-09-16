package xyz.heylana.app.wallet

import kotlinx.coroutines.delay
import org.sol4k.PublicKey
import xyz.heylana.app.HeylanaLog

/** How a confirmed send ended, told back to the buddy. */
sealed interface SendResult {
    data class Sent(val shortSignature: String) : SendResult
    data class Stopped(val line: String) : SendResult
}

/** Carries the result from [SendActivity] to the overlay service, in the same process. */
object SendRelay {
    @Volatile
    var listener: ((SendResult) -> Unit)? = null
}

/**
 * A confirmed send, from blockhash to landed.
 *
 * The cluster comes from the quote, which comes from the worker, and it goes to
 * both places a network is chosen: the blockhash request (a transaction belongs
 * to whichever network its blockhash came from) and Seed Vault's authorisation.
 * The transfer itself has no network field. Heylana builds it unsigned; Seed
 * Vault signs and sends it, or does not.
 */
class SendFlow(
    private val blockhash: suspend (Cluster) -> Answer<String>,
    private val signAndSend: suspend (ByteArray, Cluster) -> SeedVault.Trip<String>,
    private val confirm: suspend (id: String, signature: String) -> Answer<String>,
    private val log: (String) -> Unit = { HeylanaLog.state(it) },
    private val now: () -> Long = System::currentTimeMillis,
    private val sleep: suspend (Long) -> Unit = { delay(it) }
) {

    constructor(api: WalletApi, seedVault: SeedVault) : this(
        blockhash = { cluster -> api.blockhash(cluster) },
        signAndSend = { transaction, cluster -> seedVault.pay(transaction, cluster) },
        confirm = { id, signature -> api.confirmSend(id, signature) }
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
        log("send: blockhash asked cluster=${request.cluster.id}")
        val recent = when (val answer = blockhash(request.cluster)) {
            is Answer.Ok -> answer.value
            is Answer.Refused -> return SendResult.Stopped(
                answer.detail.ifBlank { WalletProblem.fromWorker(answer.reason).words }
            )
            is Answer.Unreachable -> return SendResult.Stopped(WalletProblem.UNREACHABLE.words)
        }

        val unsigned = runCatching {
            SendTransaction.serializeUnsigned(
                SendTransaction.Order(
                    from = PublicKey(from),
                    to = PublicKey(request.to),
                    mint = request.mint?.let { PublicKey(it) },
                    tokenProgram = request.tokenProgram?.let { PublicKey(it) },
                    units = request.units,
                    decimals = request.decimals,
                    recentBlockhash = recent
                )
            )
        }.getOrElse {
            log("send: could not build the transfer")
            return SendResult.Stopped(WalletProblem.UNKNOWN.words)
        }

        log("send: opening Seed Vault cluster=${request.cluster.id}")
        val signature = when (val trip = signAndSend(unsigned, request.cluster)) {
            is SeedVault.Trip.Done -> trip.value
            SeedVault.Trip.NoWallet -> return SendResult.Stopped(WalletProblem.NO_WALLET.words)
            is SeedVault.Trip.Stopped -> return SendResult.Stopped(trip.problem.words)
        }
        log("send: signed in Seed Vault, waiting for it to land cluster=${request.cluster.id}")
        return awaitLanded(request.id, signature)
    }

    /** Not confirmed yet, a hiccup, or no connection: ask again, for up to a minute. */
    private suspend fun awaitLanded(id: String, signature: String): SendResult {
        val deadline = now() + LAND_TIMEOUT_MS
        while (true) {
            when (val answer = confirm(id, signature)) {
                is Answer.Ok -> return SendResult.Sent(answer.value)
                is Answer.Refused -> {
                    val waiting = answer.reason == "not_confirmed" || answer.code >= SERVER_ERROR || answer.code == TOO_MANY
                    if (!waiting) return SendResult.Stopped(DID_NOT_MATCH)
                }
                is Answer.Unreachable -> Unit
            }
            if (now() + POLL_MS > deadline) return SendResult.Stopped(NOT_CONFIRMED_YET)
            sleep(POLL_MS)
        }
    }

    companion object {
        const val LAND_TIMEOUT_MS = 60_000L
        const val POLL_MS = 2_000L
        private const val SERVER_ERROR = 500
        private const val TOO_MANY = 429

        const val DID_NOT_MATCH =
            "That send didn't land the way it was prepared. Check your wallet before trying again."
        const val NOT_CONFIRMED_YET =
            "Seed Vault sent it, but I couldn't confirm it landed yet. Check your wallet in a minute."
    }
}

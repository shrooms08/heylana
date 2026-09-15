package xyz.heylana.app.wallet

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.sol4k.PublicKey
import xyz.heylana.app.HeylanaLog
import xyz.heylana.app.settings.HeylanaSettings

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
 * The send the user just confirmed on the strip, handed to Seed Vault.
 *
 * Invisible, like the microphone prompt: the overlay service has no activity of
 * its own, and Mobile Wallet Adapter needs one to open the wallet from. This
 * builds the transfer, Seed Vault shows it, and the user signs it or does not.
 * Heylana never signs. Then it asks the worker until the send has landed,
 * reports back, and finishes.
 */
class SendActivity : ComponentActivity() {

    private lateinit var seedVault: SeedVault
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Created with the activity, before it starts, as Mobile Wallet Adapter requires.
        seedVault = SeedVault(this)
        // Recreated while Seed Vault was open: the first instance owns the send.
        if (savedInstanceState != null) return

        val request = Request.from(intent)
        if (request == null) {
            deliver(SendResult.Stopped(WalletProblem.UNKNOWN.words))
            finish()
            return
        }
        scope.launch {
            deliver(send(request))
            finish()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun send(request: Request): SendResult {
        val settings = HeylanaSettings.get(this)
        val session = settings.walletSession ?: return SendResult.Stopped(SendText.NO_WALLET)
        val api = WalletApi(settings)

        val blockhash = when (val answer = api.blockhash(request.cluster)) {
            is Answer.Ok -> answer.value
            is Answer.Refused -> return SendResult.Stopped(WalletProblem.fromWorker(answer.reason).words)
            is Answer.Unreachable -> return SendResult.Stopped(WalletProblem.UNREACHABLE.words)
        }
        val unsigned = runCatching {
            SendTransaction.serializeUnsigned(
                SendTransaction.Order(
                    from = PublicKey(session.pubkey),
                    to = PublicKey(request.to),
                    mint = request.mint?.let { PublicKey(it) },
                    tokenProgram = request.tokenProgram?.let { PublicKey(it) },
                    units = request.units,
                    decimals = request.decimals,
                    recentBlockhash = blockhash
                )
            )
        }.getOrElse {
            HeylanaLog.state("send: could not build the transfer")
            return SendResult.Stopped(WalletProblem.UNKNOWN.words)
        }

        val signature = when (val trip = seedVault.pay(unsigned, request.cluster)) {
            is SeedVault.Trip.Done -> trip.value
            SeedVault.Trip.NoWallet -> return SendResult.Stopped(WalletProblem.NO_WALLET.words)
            is SeedVault.Trip.Stopped -> return SendResult.Stopped(trip.problem.words)
        }
        HeylanaLog.state("send: signed in Seed Vault, waiting for it to land")
        return awaitLanded(api, request.id, signature)
    }

    /** Not confirmed yet, a hiccup, or no connection: ask again, for up to a minute. */
    private suspend fun awaitLanded(api: WalletApi, id: String, signature: String): SendResult {
        val deadline = System.currentTimeMillis() + LAND_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            when (val answer = api.confirmSend(id, signature)) {
                is Answer.Ok -> return SendResult.Sent(answer.value)
                is Answer.Refused -> {
                    val waiting = answer.reason == "not_confirmed" || answer.code >= 500 || answer.code == 429
                    if (!waiting) return SendResult.Stopped(DID_NOT_MATCH)
                }
                is Answer.Unreachable -> Unit
            }
            delay(POLL_MS)
        }
        return SendResult.Stopped(NOT_CONFIRMED_YET)
    }

    private fun deliver(result: SendResult) {
        SendRelay.listener?.invoke(result)
    }

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
            fun from(intent: Intent?): Request? {
                intent ?: return null
                val id = intent.getStringExtra(EXTRA_ID) ?: return null
                val to = intent.getStringExtra(EXTRA_TO) ?: return null
                val units = intent.getLongExtra(EXTRA_UNITS, 0L).takeIf { it > 0 } ?: return null
                return Request(
                    id = id,
                    to = to,
                    mint = intent.getStringExtra(EXTRA_MINT),
                    tokenProgram = intent.getStringExtra(EXTRA_PROGRAM),
                    units = units,
                    decimals = intent.getIntExtra(EXTRA_DECIMALS, 0),
                    cluster = Cluster.fromWorker(intent.getStringExtra(EXTRA_CLUSTER))
                )
            }
        }
    }

    companion object {
        private const val EXTRA_ID = "send_id"
        private const val EXTRA_TO = "send_to"
        private const val EXTRA_MINT = "send_mint"
        private const val EXTRA_PROGRAM = "send_program"
        private const val EXTRA_UNITS = "send_units"
        private const val EXTRA_DECIMALS = "send_decimals"
        private const val EXTRA_CLUSTER = "send_cluster"

        private const val LAND_TIMEOUT_MS = 60_000L
        private const val POLL_MS = 2_000L

        const val DID_NOT_MATCH =
            "That send didn't land the way it was prepared. Check your wallet before trying again."
        const val NOT_CONFIRMED_YET =
            "Seed Vault sent it, but I couldn't confirm it landed yet. Check your wallet in a minute."

        fun intentFor(context: Context, quote: SendQuote): Intent =
            Intent(context, SendActivity::class.java)
                .putExtra(EXTRA_ID, quote.id)
                .putExtra(EXTRA_TO, quote.toAddress)
                .putExtra(EXTRA_MINT, quote.mint)
                .putExtra(EXTRA_PROGRAM, quote.tokenProgram)
                .putExtra(EXTRA_UNITS, quote.units)
                .putExtra(EXTRA_DECIMALS, quote.decimals)
                .putExtra(EXTRA_CLUSTER, quote.cluster.id)
    }
}

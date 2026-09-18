package xyz.heylana.app.wallet

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import xyz.heylana.app.settings.HeylanaSettings

/**
 * The send the user just confirmed on the strip, handed to Seed Vault.
 *
 * Invisible, like the microphone prompt: the overlay service has no activity of
 * its own, and Mobile Wallet Adapter needs one to open the wallet from. The work
 * itself is [SendFlow]; this only carries the confirmed send in, and the result
 * back out to the buddy.
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

        val request = requestFrom(intent)
        val settings = HeylanaSettings.get(this)
        val session = settings.walletSession
        when {
            request == null -> deliverAndFinish(SendResult.Stopped(WalletProblem.UNKNOWN.words))
            session == null -> deliverAndFinish(SendResult.Stopped(SendText.NO_WALLET))
            else -> scope.launch {
                deliverAndFinish(SendFlow(WalletApi(settings), seedVault) { stage -> SendRelay.progress?.invoke(stage) }.run(request, session.pubkey))
            }
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun deliverAndFinish(result: SendResult) {
        SendRelay.listener?.invoke(result)
        finish()
    }

    companion object {
        private const val EXTRA_ID = "send_id"
        private const val EXTRA_TO = "send_to"
        private const val EXTRA_MINT = "send_mint"
        private const val EXTRA_PROGRAM = "send_program"
        private const val EXTRA_UNITS = "send_units"
        private const val EXTRA_DECIMALS = "send_decimals"
        private const val EXTRA_CLUSTER = "send_cluster"

        fun intentFor(context: Context, quote: SendQuote): Intent {
            val request = SendFlow.Request.of(quote)
            return Intent(context, SendActivity::class.java)
                .putExtra(EXTRA_ID, request.id)
                .putExtra(EXTRA_TO, request.to)
                .putExtra(EXTRA_MINT, request.mint)
                .putExtra(EXTRA_PROGRAM, request.tokenProgram)
                .putExtra(EXTRA_UNITS, request.units)
                .putExtra(EXTRA_DECIMALS, request.decimals)
                .putExtra(EXTRA_CLUSTER, request.cluster.id)
        }

        private fun requestFrom(intent: Intent?): SendFlow.Request? {
            intent ?: return null
            val id = intent.getStringExtra(EXTRA_ID) ?: return null
            val to = intent.getStringExtra(EXTRA_TO) ?: return null
            val units = intent.getLongExtra(EXTRA_UNITS, 0L).takeIf { it > 0 } ?: return null
            val cluster = intent.getStringExtra(EXTRA_CLUSTER) ?: return null
            return SendFlow.Request(
                id = id,
                to = to,
                mint = intent.getStringExtra(EXTRA_MINT),
                tokenProgram = intent.getStringExtra(EXTRA_PROGRAM),
                units = units,
                decimals = intent.getIntExtra(EXTRA_DECIMALS, 0),
                cluster = Cluster.fromWorker(cluster)
            )
        }
    }
}

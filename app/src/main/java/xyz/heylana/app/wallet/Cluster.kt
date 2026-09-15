package xyz.heylana.app.wallet

import com.solana.mobilewalletadapter.clientlib.Blockchain
import com.solana.mobilewalletadapter.clientlib.Solana

/**
 * Which Solana the worker takes payments on. The worker says so on /me and the
 * app follows it, so Seed Vault is asked about the same chain the worker will
 * look for the payment on. Mainnet unless the worker says devnet.
 */
enum class Cluster(val id: String, val blockchain: Blockchain, val hasSkr: Boolean) {
    MAINNET("mainnet-beta", Solana.Mainnet, hasSkr = true),

    /** Play money for testing. There is no SKR here. */
    DEVNET("devnet", Solana.Devnet, hasSkr = false);

    companion object {
        fun fromWorker(id: String?): Cluster = if (id == DEVNET.id) DEVNET else MAINNET
    }
}

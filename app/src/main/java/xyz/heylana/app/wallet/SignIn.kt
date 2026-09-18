package xyz.heylana.app.wallet

import xyz.heylana.app.settings.HeylanaSettings

/**
 * One full sign-in: Seed Vault signs the worker's plain sign-in message (never a
 * transaction), the worker checks it, and the 30-day session is kept. Shared by the first
 * run and Settings.
 */
object SignIn {

    sealed interface Result {
        data class Done(val session: WalletSession, val standing: Standing, val welcomeGranted: Boolean) : Result
        data class Stopped(val words: String, val noWallet: Boolean = false) : Result
    }

    suspend fun connect(settings: HeylanaSettings, seedVault: SeedVault, api: WalletApi, cluster: Cluster): Result {
        val signedIn = when (val trip = seedVault.connect(api, cluster)) {
            is SeedVault.Trip.Done -> trip.value
            SeedVault.Trip.NoWallet -> return Result.Stopped(WalletProblem.NO_WALLET.words, noWallet = true)
            is SeedVault.Trip.Stopped -> return Result.Stopped(trip.problem.words)
        }
        return when (val verified = api.verify(signedIn.pubkey, signedIn.nonce, signedIn.signature)) {
            is Answer.Ok -> {
                val session = WalletSession(signedIn.pubkey, verified.value.session)
                settings.walletSession = session
                Result.Done(session, verified.value.standing, verified.value.welcomeGranted)
            }
            is Answer.Refused -> Result.Stopped(WalletProblem.fromWorker(verified.reason).words)
            is Answer.Unreachable -> Result.Stopped(WalletProblem.UNREACHABLE.words)
        }
    }

    /** "7c2y…SxSv": the wallet as the menu's footer shows it. */
    fun short(pubkey: String): String = if (pubkey.length <= 9) pubkey else pubkey.take(4) + "…" + pubkey.takeLast(4)
}

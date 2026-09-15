package xyz.heylana.app.brain

/**
 * Solana apps Heylana recognises by package name, and what kind of screen each is.
 *
 * Every package here was checked against a primary source: its Google Play
 * listing, or — for Solana Mobile's own apps, which are not on Play — the
 * package actually installed on the Seeker. Add an app only with the same proof;
 * a wrong package name quietly does nothing, a guessed one is worse.
 */
object SolanaApps {

    enum class Kind { WALLET, SIGNING, STORE, SWAP, STAKE, OTHER }

    data class App(val packageName: String, val name: String, val kind: Kind)

    val ALL = listOf(
        // Solana Mobile, installed on the Seeker.
        App("com.solanamobile.wallet", "Seed Vault Wallet", Kind.WALLET),
        App("com.solanamobile.seedvaultimpl", "Seed Vault", Kind.SIGNING),
        App("com.solanamobile.dappstore", "Solana dApp Store", Kind.STORE),
        // Google Play listings.
        App("app.phantom", "Phantom", Kind.WALLET),
        App("com.solflare.mobile", "Solflare", Kind.WALLET),
        App("app.backpack.mobile", "Backpack", Kind.WALLET),
        App("com.magiceden.wallet", "Magic Eden Wallet", Kind.WALLET),
        App("com.helium.wallet.app", "Helium Wallet", Kind.WALLET),
        App("com.nimiq.pay", "Nimiq Pay", Kind.WALLET),
        App("ag.jup.jupiter.android", "Jupiter", Kind.SWAP),
        App("com.drip.haus", "DRiP", Kind.OTHER),
        // Installed on the Seeker from the dApp Store.
        App("so.sanctum.app", "Sanctum", Kind.STAKE)
    )

    private val byPackage = ALL.associateBy { it.packageName }

    fun of(packageName: String?): App? = packageName?.let { byPackage[it] }
}

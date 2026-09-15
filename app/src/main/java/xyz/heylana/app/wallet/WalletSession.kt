package xyz.heylana.app.wallet

/**
 * A connected wallet, as far as the phone remembers it: the address, shown
 * shortened, and the session the worker sealed for it. No key, no seed, nothing
 * that can sign — that all stays in Seed Vault.
 */
data class WalletSession(val pubkey: String, val token: String) {

    /** "9WzD…AWWM" — enough to recognise, too short to mistake for a full address. */
    val shortAddress: String
        get() = if (pubkey.length <= SHORT_KEEP * 2 + 1) pubkey else "${pubkey.take(SHORT_KEEP)}…${pubkey.takeLast(SHORT_KEEP)}"

    private companion object {
        const val SHORT_KEEP = 4
    }
}

/**
 * Keeps the session between runs. The store is passed in as two functions so
 * this is testable without Android; on the phone it is EncryptedSharedPreferences.
 */
class WalletSessionStore(
    private val read: (String) -> String?,
    private val write: (String, String?) -> Unit
) {

    fun load(): WalletSession? {
        val pubkey = read(KEY_PUBKEY)?.takeIf { it.isNotBlank() } ?: return null
        val token = read(KEY_TOKEN)?.takeIf { it.isNotBlank() } ?: return null
        return WalletSession(pubkey, token)
    }

    fun save(session: WalletSession) {
        write(KEY_PUBKEY, session.pubkey)
        write(KEY_TOKEN, session.token)
    }

    /** Disconnect: both halves go, so a stale token can never ride along. */
    fun clear() {
        write(KEY_PUBKEY, null)
        write(KEY_TOKEN, null)
    }

    companion object {
        const val KEY_PUBKEY = "wallet_pubkey"
        const val KEY_TOKEN = "wallet_session"
    }
}

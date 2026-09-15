package xyz.heylana.app.wallet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** A connected wallet survives a restart, and a disconnect leaves nothing behind. */
class WalletSessionTest {

    private val values = mutableMapOf<String, String>()
    private val store = WalletSessionStore(
        read = { values[it] },
        write = { key, value -> if (value == null) values.remove(key) else values[key] = value }
    )

    @Test
    fun `a saved session reads back after a restart`() {
        store.save(WalletSession("9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM", "payload.mac"))
        val again = WalletSessionStore(read = { values[it] }, write = { _, _ -> })
        assertEquals(WalletSession("9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM", "payload.mac"), again.load())
    }

    @Test
    fun `nothing saved means no session`() {
        assertNull(store.load())
    }

    @Test
    fun `disconnect clears both the address and the session`() {
        store.save(WalletSession("9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM", "payload.mac"))
        store.clear()
        assertNull(store.load())
        assertEquals(emptyMap<String, String>(), values)
    }

    @Test
    fun `half a session is no session`() {
        values[WalletSessionStore.KEY_PUBKEY] = "9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM"
        assertNull(store.load())
    }

    @Test
    fun `the short address keeps four characters each side`() {
        assertEquals("9WzD…AWWM", WalletSession("9WzDXwBbmkg8ZTbNMqUxvQRAyrZzDsGYdLVL9zYtAWWM", "t").shortAddress)
    }
}

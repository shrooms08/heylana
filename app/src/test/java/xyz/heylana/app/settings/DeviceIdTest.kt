package xyz.heylana.app.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The id the proxy counts a phone's day by: made once, kept for good, and never
 * anything but a plain uuid.
 */
class DeviceIdTest {

    private class Store(var value: String? = null) {
        var writes = 0
        fun read(): String? = value
        fun write(next: String) {
            value = next
            writes++
        }
    }

    @Test
    fun `the first ask makes one and keeps it`() {
        val store = Store()
        val made = DeviceId.readOrCreate(store::read, store::write)
        assertTrue(DeviceId.looksLikeOne(made))
        assertEquals(made, store.value)
        assertEquals(1, store.writes)
    }

    @Test
    fun `every ask after that gives the same one back`() {
        val store = Store()
        val first = DeviceId.readOrCreate(store::read, store::write)
        val second = DeviceId.readOrCreate(store::read, store::write)
        val third = DeviceId.readOrCreate(store::read, store::write)
        assertEquals(first, second)
        assertEquals(first, third)
        assertEquals(1, store.writes)
    }

    @Test
    fun `two installs are not the same phone`() {
        val one = DeviceId.readOrCreate({ null }, {})
        val other = DeviceId.readOrCreate({ null }, {})
        assertNotEquals(one, other)
    }

    @Test
    fun `a stored id that is not one is replaced`() {
        val store = Store("not a device id")
        val made = DeviceId.readOrCreate(store::read, store::write)
        assertTrue(DeviceId.looksLikeOne(made))
        assertEquals(made, store.value)
    }

    @Test
    fun `an empty stored id is replaced`() {
        val store = Store("   ")
        val made = DeviceId.readOrCreate(store::read, store::write)
        assertTrue(DeviceId.looksLikeOne(made))
    }

    @Test
    fun `only the shape the worker accepts counts as one`() {
        assertTrue(DeviceId.looksLikeOne("3f0b6a2e-91cd-4a5e-9a7c-7b2f8c1d4e55"))
        assertFalse(DeviceId.looksLikeOne("short"))
        assertFalse(DeviceId.looksLikeOne("3f0b6a2e 91cd 4a5e 9a7c 7b2f8c1d4e55"))
        assertFalse(DeviceId.looksLikeOne("zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz"))
    }
}

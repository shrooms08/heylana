package xyz.heylana.app.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The crash of 9 October 2026: a window came up whose text list held a null, and the whole
 * of screen reading went down with the event. These hold both halves of the fix — the event
 * is read without assuming any field is there, and one that still cannot be read costs that
 * event alone.
 */
class ScreenEventTest {

    @Test
    fun `an event with nothing on it at all is still read`() {
        val event = ScreenEvent.windowOf(packageName = null, className = null, texts = null, at = 42L)
        assertEquals(null, event.packageName)
        assertEquals(null, event.className)
        assertEquals("", event.title)
        assertEquals(42L, event.at)
        // And the one thing built from the other two does not fall over either.
        assertEquals("", event.describedAs)
    }

    @Test
    fun `a null in the text list is skipped, not read`() {
        // This is the crash, exactly: the list arrives with a hole in it.
        val texts: List<CharSequence?> = listOf("Approve", null, "0.01 USDC")
        assertEquals("Approve 0.01 USDC", ScreenEvent.titleOf(texts))
        val event = ScreenEvent.windowOf("com.solanamobile.seedvault", "FrameLayout", texts, at = 1L)
        assertEquals("FrameLayout Approve 0.01 USDC", event.describedAs)
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `the way it used to be built is what threw`() {
        val texts: List<CharSequence?> = listOf(null)
        // The Android API hands the list over as a list of non-null text, so the old title —
        // texts.joinToString(" ") { it.toString() } — made that call with no check in front
        // of it. This is that same expression, on that same shape, and it throws.
        val asTheApiDeclaresIt = texts as List<CharSequence>
        assertThrows(NullPointerException::class.java) {
            asTheApiDeclaresIt.joinToString(" ") { it.toString() }
        }
        // Now the same list is simply an empty title.
        assertEquals("", ScreenEvent.titleOf(texts))
    }

    @Test
    fun `odd text is tidied rather than refused`() {
        assertEquals("Dialog", ScreenEvent.titleOf(listOf("  Dialog  ", "", "   ", null)))
        assertEquals("", ScreenEvent.titleOf(emptyList()))
        // A CharSequence that is not a String is read the same way.
        assertEquals("Sign in", ScreenEvent.titleOf(listOf(StringBuilder("Sign in"))))
    }

    @Test
    fun `one bad event costs one event, and the service stays up`() {
        val reported = mutableListOf<Throwable>()
        val read = mutableListOf<Int>()
        val boom = IllegalStateException("whatever the window did")

        // Five events arrive; the third is unreadable.
        (1..5).forEach { n ->
            ScreenEvent.surviving(onError = { reported += it }) {
                if (n == 3) throw boom
                read += n
            }
        }

        // The other four were read, so the service never stopped hearing them.
        assertEquals(listOf(1, 2, 4, 5), read)
        // And the one failure was handed over rather than swallowed.
        assertEquals(1, reported.size)
        assertSame(boom, reported.first())
    }

    @Test
    fun `nothing it throws gets through, not even an Error`() {
        val reported = mutableListOf<Throwable>()
        ScreenEvent.surviving(onError = { reported += it }) { throw NullPointerException("null toString") }
        ScreenEvent.surviving(onError = { reported += it }) { throw StackOverflowError() }
        assertEquals(2, reported.size)
        assertTrue(reported[0] is NullPointerException)
        assertTrue(reported[1] is StackOverflowError)
    }
}

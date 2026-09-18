package xyz.heylana.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceQueueTest {

    @Test
    fun `a new line waits behind the one being said, in order`() {
        val queue = VoiceQueue<String>()
        assertEquals(1, queue.add("step one"))
        assertEquals(2, queue.add("step two"))
        assertEquals("step one", queue.next()?.second)
        assertEquals(1, queue.pending())
        assertEquals("step two", queue.next()?.second)
        assertNull(queue.next())
    }

    @Test
    fun `only a stop drops what is queued and ends the line in the air`() {
        val queue = VoiceQueue<String>()
        queue.add("playing")
        val (gen, _) = queue.next()!!
        queue.add("waiting")
        assertTrue(queue.isCurrent(gen))
        assertEquals(1, queue.stop())
        // The writer still finishing "playing" sees it is over.
        assertFalse(queue.isCurrent(gen))
        assertNull(queue.next())
        // A line after the stop plays.
        queue.add("new question")
        assertEquals("new question", queue.next()?.second)
    }
}

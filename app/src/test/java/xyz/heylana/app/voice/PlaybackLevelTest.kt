package xyz.heylana.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackLevelTest {

    private fun pcm(vararg samples: Int): ByteArray = ByteArray(samples.size * 2).also { out ->
        samples.forEachIndexed { i, s -> out[2 * i] = (s and 0xFF).toByte(); out[2 * i + 1] = (s shr 8).toByte() }
    }

    @Test
    fun `silence is nothing, loud speech is near the top, and it never passes 1`() {
        assertEquals(0f, PlaybackLevel.of(pcm(0, 0, 0, 0), 8), 0f)
        val quarter = PlaybackLevel.of(pcm(8192, -8192, 8192, -8192), 8)
        assertEquals(1f, quarter, 0.01f)
        val quiet = PlaybackLevel.of(pcm(1638, -1638, 1638, -1638), 8)
        assertEquals(0.2f, quiet, 0.01f)
        assertTrue(PlaybackLevel.of(pcm(32767, -32768), 4) <= 1f)
        assertEquals(0f, PlaybackLevel.of(ByteArray(1), 1), 0f)
    }
}

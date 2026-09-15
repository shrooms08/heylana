package xyz.heylana.app.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuotaMessageTest {

    @Test
    fun `the last free talk points at Settings`() {
        assertEquals(
            "That was your last free talk this month. Go Pro in Settings for unlimited.",
            QuotaMessage.forReason("talks_cap")
        )
    }

    @Test
    fun `the daily budget and an ended session have their own lines`() {
        assertEquals(QuotaMessage.DAILY_CAP, QuotaMessage.forReason("daily_cap"))
        assertEquals(QuotaMessage.SESSION_ENDED, QuotaMessage.forReason("bad_session"))
    }

    @Test
    fun `anything else is left to the ordinary error`() {
        assertNull(QuotaMessage.forReason("upstream"))
        assertNull(QuotaMessage.forReason(""))
    }
}

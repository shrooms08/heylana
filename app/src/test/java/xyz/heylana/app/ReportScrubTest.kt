package xyz.heylana.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.heylana.app.ops.CrashReports
import xyz.heylana.app.ops.ReportScrub

class ReportScrubTest {

    @Test
    fun `addresses, signatures and keys are taken out of report text`() {
        val address = "7c2y8xXRFYVamzNJ11hX3sicHexPHNuDwpiJ6sEnSxSv"
        val signature = "5VERv8NMvzbJMEkV8xnrLkEaWRtSz9CosKDYjCJjBRnbJLgp8uirBgmQpjKhoR4tjF3ZpRzrFmBV6UjKdiSZkQUW"
        assertEquals(
            "send to [address] failed, sig [address], key [key], auth [key]",
            ReportScrub.text("send to $address failed, sig $signature, key sk-ant-api03-abc_DEF, auth Bearer eyJhbGci.x.y")
        )
        assertEquals("device 3f0b6a2e-91cd-4a5e-9a7c-7b2f8c1d4e55 at SendFlow.kt:42", ReportScrub.text("device 3f0b6a2e-91cd-4a5e-9a7c-7b2f8c1d4e55 at SendFlow.kt:42"))
        assertNull(ReportScrub.text(null))
    }

    @Test
    fun `reports are off without a DSN, and off in debug unless asked for`() {
        assertFalse(CrashReports.enabled("", debugBuild = false, inDebug = false))
        assertTrue(CrashReports.enabled("https://k@o1.ingest.sentry.io/1", debugBuild = false, inDebug = false))
        assertFalse(CrashReports.enabled("https://k@o1.ingest.sentry.io/1", debugBuild = true, inDebug = false))
        assertTrue(CrashReports.enabled("https://k@o1.ingest.sentry.io/1", debugBuild = true, inDebug = true))
    }
}

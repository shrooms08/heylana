package xyz.heylana.app.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PageExtentTest {

    @Test
    fun `a big scrolling area that can still go down means more below, a carousel does not`() {
        assertTrue(PageExtent.scrollsDown(scrollable = true, canScrollDown = true, height = 2000, windowHeight = 2670))
        assertFalse(PageExtent.scrollsDown(scrollable = true, canScrollDown = false, height = 2000, windowHeight = 2670))
        assertFalse(PageExtent.scrollsDown(scrollable = true, canScrollDown = true, height = 300, windowHeight = 2670))
        assertFalse(PageExtent.scrollsDown(scrollable = false, canScrollDown = true, height = 2000, windowHeight = 2670))
    }

    @Test
    fun `an element the tree places below the window is more below, one on screen is not`() {
        assertTrue(PageExtent.hiddenBelow(visible = false, top = 2700, windowBottom = 2670))
        assertFalse(PageExtent.hiddenBelow(visible = true, top = 2700, windowBottom = 2670))
        assertFalse(PageExtent.hiddenBelow(visible = false, top = 100, windowBottom = 2670))
    }

    @Test
    fun `the line is added once, and never when the answer isn't on screen`() {
        assertEquals("It's about rent. ${PageExtent.LINE}", PageExtent.withLine("It's about rent.", moreBelow = true, unseen = false))
        assertEquals("I can only see what's on screen.", PageExtent.withLine("I can only see what's on screen.", moreBelow = true, unseen = true))
        assertEquals("Short page.", PageExtent.withLine("Short page.", moreBelow = false, unseen = false))
        val once = PageExtent.withLine("It's about rent.", true, false)
        assertEquals(once, PageExtent.withLine(once, true, false))
        assertFalse(PageExtent.LINE.contains("whole page"))
    }

    @Test
    fun `anything over a browser is about the page, elsewhere only words about reading one`() {
        assertTrue(PageExtent.aboutThePage("what's the accepted fix", "com.android.chrome"))
        assertTrue(PageExtent.aboutThePage("summarise this thread", "com.twitter.android"))
        assertFalse(PageExtent.aboutThePage("what does this button do", "com.solanamobile.wallet"))
    }
}

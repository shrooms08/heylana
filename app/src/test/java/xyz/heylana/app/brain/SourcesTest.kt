package xyz.heylana.app.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SourcesTest {

    @Test
    fun `a chip title is the source and the page, at most 40 characters, cut at a word`() {
        assertEquals("Solana Cookbook: priority fees", Sources.chipTitle("Solana Cookbook", "priority fees"))
        val long = Sources.chipTitle("Solana Cookbook", "How to Add Priority Fees to a Transaction")
        assertTrue(long, long.length <= Sources.MAX_TITLE)
        assertTrue(long, long.startsWith("Solana Cookbook: How to") && long.endsWith("…"))
        assertEquals("Stack Exchange: What is rent?", Sources.chipTitle("Solana Stack Exchange", "What is rent?"))
        assertEquals("Solana docs", Sources.chipTitle("solana.com docs", ""))
    }

    @Test
    fun `at most two chips, https only, each page once`() {
        val chips = Sources.chips(
            listOf(
                Source("A", "https://a.test/1"), Source("A again", "https://a.test/1"),
                Source("Plain", "http://b.test"), Source("B", "https://b.test/2"), Source("C", "https://c.test/3"),
            )
        )
        assertEquals(listOf("https://a.test/1", "https://b.test/2"), chips.map { it.url })
    }

    @Test
    fun `nothing spoken ever carries a web address`() {
        assertEquals(
            "It's the Anchor docs' error list.",
            Sources.spoken("It's the Anchor docs' error list. More: https://www.anchor-lang.com/docs/features/errors")
        )
        assertEquals("See the Cookbook for the full example.", Sources.spoken("See the Cookbook https://solanacookbook.com/x for the full example."))
        assertFalse(Sources.spoken("go to www.example.com now").contains("www"))
        assertEquals("Plain words stay.", Sources.spoken("Plain words stay."))
    }

    @Test
    fun `a stray docs link in the words becomes a chip, any other link is only dropped`() {
        val found = Sources.inText("Read https://solana.com/docs/core/fees. Or https://evil.test/x")
        assertEquals(listOf(Source("Solana docs: fees", "https://solana.com/docs/core/fees")), found)
    }

    @Test
    fun `the page in front becomes a chip, nonsense does not`() {
        assertEquals(
            "https://solana.stackexchange.com/questions/1/why",
            Sources.forPage("solana.stackexchange.com/questions/1/why")?.url
        )
        assertTrue(Sources.forPage("solana.stackexchange.com/questions/1/why")!!.title.startsWith("Page: solana.stackexchange.com"))
        assertNull(Sources.forPage("Search or type web address"))
        assertNull(Sources.forPage(""))
    }

    @Test
    fun `spoken names for the docs behind a chip`() {
        assertEquals("the Anchor docs", Sources.spokenName("https://www.anchor-lang.com/docs/features/errors"))
        assertEquals("the Solana docs", Sources.spokenName("https://solana.com/docs/core/fees"))
        assertEquals("the Solana Mobile docs", Sources.spokenName("https://docs.solanamobile.com/developers/seed-vault"))
    }
}

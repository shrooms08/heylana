package xyz.heylana.app.actions

import org.junit.Assert.assertEquals
import org.junit.Test

class ContactMatcherTest {

    private val book = listOf(
        Contact("Ada Obi", "0800 123 4567"),
        Contact("Ada Obi", "0801 000 0000"),
        Contact("Tunde Bakare", "0802 222 2222"),
        Contact("Mum", "0803 333 3333")
    )

    @Test
    fun `a first name finds the person, whatever else their name holds`() {
        assertEquals(ContactMatcher.Match.Found(Contact("Ada Obi", "0800 123 4567")), ContactMatcher.best("Ada", book))
        assertEquals("Mum", (ContactMatcher.best("mum", book) as ContactMatcher.Match.Found).contact.name)
    }

    @Test
    fun `two people who fit equally is a question, one person with two numbers is not`() {
        val two = book + Contact("Ada Lovelace", "0804 444 4444")
        assertEquals(ContactMatcher.Match.Ambiguous("Ada Obi", "Ada Lovelace"), ContactMatcher.best("Ada", two))
        // The exact full name settles it.
        assertEquals("Ada Lovelace", (ContactMatcher.best("Ada Lovelace", two) as ContactMatcher.Match.Found).contact.name)
    }

    @Test
    fun `nobody by that name is none`() {
        assertEquals(ContactMatcher.Match.None, ContactMatcher.best("Zainab", book))
        assertEquals(ContactMatcher.Match.None, ContactMatcher.best("", book))
    }

    @Test
    fun `the line names the contact and never reads the number`() {
        assertEquals("Your message to Ada Obi is ready. Check it and tap send.", QuickText.messageTo("Ada Obi"))
    }
}

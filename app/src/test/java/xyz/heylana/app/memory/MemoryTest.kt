package xyz.heylana.app.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.heylana.app.wallet.Answer

class MemoryTest {

    @Test
    fun `remember that is a record in the user's own words`() {
        assertEquals("I'm new to solana", MemoryWords.explicit("remember that I'm new to solana"))
        assertEquals("I prefer examples", MemoryWords.explicit("Heylana, please remember I prefer examples."))
        assertEquals("my name is Ada", MemoryWords.explicit("can you remember: my name is Ada"))
        assertNull(MemoryWords.explicit("do you remember what I said"))
        assertNull(MemoryWords.explicit("remember when we swapped"))
        assertNull(MemoryWords.explicit("remember to call mum at 6"))
        assertNull(MemoryWords.explicit("what is a PDA"))
    }

    @Test
    fun `a short remark can be a preference, a long question is not`() {
        assertEquals(MemoryWords.SHORTER, MemoryWords.inferred("shorter answers please"))
        assertEquals(MemoryWords.SLOWER, MemoryWords.inferred("slow down"))
        assertEquals(MemoryWords.NO_EXPLANATIONS, MemoryWords.inferred("stop explaining"))
        assertNull(MemoryWords.inferred("why is the fee shorter on devnet than it is on mainnet today"))
        assertNull(MemoryWords.inferred("what is rent"))
    }

    @Test
    fun `the saved line is four words`() {
        assertEquals(4, MemoryWords.SAVED.split(" ").size)
    }

    private class Harness(var on: Boolean = true, var wallet: Boolean = true, var reply: Answer<String?> = Answer.Ok("r1")) {
        val saved = mutableListOf<List<String?>>()
        val forgotten = mutableListOf<String>()
        val offered = mutableSetOf<String>()
        var clock = 0L
        val desk = MemoryDesk(
            memoryOn = { on }, hasWallet = { wallet },
            offeredBefore = { it in offered }, markOffered = { offered += it },
            save = { category, content, consent, said -> saved += listOf(category, content, consent, said); reply },
            forget = { id -> forgotten += id; Answer.Ok(Unit) },
            now = { clock }, log = {}
        )
        suspend fun say(text: String): String? = if (desk.claims(text)) desk.handle(text) else null
    }

    @Test
    fun `an explicit record is written at once with the user's words as proof`() = runBlocking {
        val h = Harness()
        assertEquals(MemoryWords.SAVED, h.say("remember that I'm new to solana"))
        assertEquals(listOf(listOf("fact", "I'm new to solana", "explicit", "remember that I'm new to solana")), h.saved)
    }

    @Test
    fun `off, no wallet, or refused by the worker is said plainly and nothing is kept`() = runBlocking {
        assertEquals(MemoryWords.OFF, Harness(on = false).say("remember that I like examples"))
        assertEquals(MemoryWords.NO_WALLET, Harness(wallet = false).say("remember that I like examples"))
        val refused = Harness(reply = Answer.Refused(422, "has_address"))
        assertEquals(MemoryWords.REFUSED, refused.say("remember that my friend is 7c2y8xXRFYVamzNJ11hX3sicHexPHNuDwpiJ6sEnSxSv"))
    }

    @Test
    fun `a preference is offered once and kept only on yes`() = runBlocking {
        val h = Harness()
        assertEquals(MemoryWords.OFFER, h.say("shorter answers please"))
        assertTrue(h.saved.isEmpty())
        assertEquals(MemoryWords.SAVED, h.say("yes"))
        assertEquals(listOf(listOf("preference", MemoryWords.SHORTER, "inferred", null)), h.saved)
        // Never offered again on this phone.
        assertNull(h.say("shorter answers"))
    }

    @Test
    fun `no declines, and anything else lets the offer lapse`() = runBlocking {
        val h = Harness()
        h.say("slow down")
        assertEquals(MemoryWords.DECLINED, h.say("no"))
        val other = Harness()
        other.say("stop explaining")
        assertFalse(other.desk.claims("what is a validator"))
        assertNull(other.say("yes"))
        assertTrue(other.saved.isEmpty())
        val late = Harness()
        late.say("slow down")
        late.clock = MemoryDesk.PENDING_MS + 1
        assertNull(late.say("yes"))
    }

    @Test
    fun `memory off never offers a preference`() = runBlocking {
        assertNull(Harness(on = false).say("shorter answers please"))
    }

    // ------------------------------------------------------------ kept without being asked

    @Test
    fun `a fact the user states about themselves is found in their own words`() {
        assertEquals("I'm new to Solana", MemoryWords.aboutUser("I'm new to Solana"))
        assertEquals("I use Jupiter for swaps", MemoryWords.aboutUser("I use Jupiter for swaps."))
        assertEquals("call me Minos", MemoryWords.aboutUser("call me Minos"))
        assertEquals("I'm new to Solana", MemoryWords.aboutUser("I'm new to Solana, what's a PDA?"))
        assertEquals("I am a developer", MemoryWords.aboutUser("I am a developer"))
        assertEquals("my favourite wallet is Seed Vault", MemoryWords.aboutUser("my favourite wallet is Seed Vault"))
        assertEquals("I live in Lagos", MemoryWords.aboutUser("hi. I live in Lagos"))
        for (not in listOf(
            "I'm bored", "I'm a bit lost", "I love it", "I like this", "I use it", "I'm on my way",
            "what do I use for swaps?", "do I use Jupiter?", "I have a question", "remember that I'm new",
            "shorter answers please", "forget that", "I'm outside", "I'm new to this",
            "I use 7c2y8xXRFYVamzNJ11hX3sicHexPHNuDwpiJ6sEnSxSv for swaps", "I use 7c2y…SxSv", "I have 40 SOL",
            "I like having 5 USDC", "tell me a joke"
        )) {
            assertNull(not, MemoryWords.aboutUser(not))
        }
    }

    @Test
    fun `stated facts are kept while memory is on, never for an action, and forget that takes the last one back`() = runBlocking {
        MemoryDesk.clearLastSaved()
        val h = Harness()
        assertFalse(h.desk.claims("I use Jupiter for swaps"))
        assertTrue(h.desk.autoSave("I use Jupiter for swaps"))
        assertEquals(listOf("fact", "I use Jupiter for swaps", "explicit", "I use Jupiter for swaps"), h.saved.single())
        assertFalse(h.desk.autoSave("what's the weather"))
        assertFalse(h.desk.autoSave("text Ada I'm on my way"))
        assertFalse(h.desk.autoSave("set a timer for 5 minutes, I'm a slow cook"))
        assertEquals(1, h.saved.size)

        assertEquals(MemoryWords.FORGOTTEN, h.say("forget that"))
        assertEquals(listOf("r1"), h.forgotten)
        assertEquals(MemoryWords.NOTHING_TO_FORGET, h.say("forget that"))

        h.on = false
        assertFalse(h.desk.autoSave("I'm new to Solana"))
        h.on = true
        h.wallet = false
        assertFalse(h.desk.autoSave("I'm new to Solana"))
        assertEquals(1, h.saved.size)
    }

    @Test
    fun `a refused auto-save shows nothing, and forget that is only for the last ten minutes`() = runBlocking {
        MemoryDesk.clearLastSaved()
        val h = Harness(reply = Answer.Refused(422, "has_money"))
        assertFalse(h.desk.autoSave("I'm new to Solana"))
        h.reply = Answer.Ok("r2")
        assertEquals(MemoryWords.SAVED, h.say("remember that I like examples"))
        h.clock += MemoryDesk.FORGET_WINDOW_MS
        assertEquals(MemoryWords.NOTHING_TO_FORGET, h.say("forget that"))
        assertTrue(h.forgotten.isEmpty())
    }

    @Test
    fun `call me Minos is a name to keep, never a phone call`() {
        assertFalse(xyz.heylana.app.actions.QuickActions.isQuickAction("call me Minos"))
        assertFalse(xyz.heylana.app.actions.QuickActions.isQuickAction("please call me Ada from now on"))
        assertTrue(xyz.heylana.app.actions.QuickActions.isQuickAction("call Mum"))
        assertTrue(xyz.heylana.app.actions.QuickActions.isQuickAction("call my brother"))
        assertEquals("call me Minos", MemoryWords.aboutUser("call me Minos"))
    }
}

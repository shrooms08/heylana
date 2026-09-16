package xyz.heylana.app.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.heylana.app.skills.SkillCap.State

fun skill(id: String, pkg: String = "com.example.$id", triggers: List<String> = emptyList()) = Skill(
    id = id, name = id, packageName = pkg, version = "1", author = "t", summary = "s", privacy = "p",
    triggers = triggers, body = "notes for $id", builtIn = true
)

class SkillCapTest {

    private val five = listOf("a", "b", "c", "d", "e").map { skill(it) }
    private val allOn = five.map { it.id }.toSet()

    @Test
    fun `free keeps the first three on and greys the other two`() {
        val rows = SkillCap.arrange(five, allOn, SkillCap.FREE)
        assertEquals(listOf(State.ACTIVE, State.ACTIVE, State.ACTIVE, State.OVER_CAP, State.OVER_CAP), rows.map { it.state })
        assertEquals("3 of 3 active", SkillCap.headline(rows, SkillCap.FREE))
        assertEquals(2, rows.count { it.greyed })
    }

    @Test
    fun `pro has room for all five`() {
        val rows = SkillCap.arrange(five, allOn, SkillCap.PRO)
        assertTrue(rows.all { it.state == State.ACTIVE })
        assertEquals("5 of 10 active", SkillCap.headline(rows, SkillCap.PRO))
    }

    @Test
    fun `switching off an active skill lets the next one in`() {
        val rows = SkillCap.arrange(five, allOn - "b", SkillCap.FREE)
        assertEquals(listOf(State.ACTIVE, State.OFF_FULL, State.ACTIVE, State.ACTIVE, State.OVER_CAP), rows.map { it.state })
        assertEquals(listOf("a", "c", "d"), SkillCap.active(five, allOn - "b", SkillCap.FREE).map { it.id })
    }

    @Test
    fun `a skill that is off cannot come on while every place is taken`() {
        val rows = SkillCap.arrange(five, setOf("a", "b", "c"), SkillCap.FREE)
        assertEquals(listOf(State.ACTIVE, State.ACTIVE, State.ACTIVE, State.OFF_FULL, State.OFF_FULL), rows.map { it.state })
        val roomy = SkillCap.arrange(five, setOf("a"), SkillCap.FREE)
        assertEquals(State.OFF, roomy[1].state)
    }

    @Test
    fun `the cap is the plan's, Free when unknown, and Free when simulated`() {
        assertEquals(10, SkillCap.capFor(10, simulateFree = false))
        assertEquals(3, SkillCap.capFor(null, simulateFree = false))
        assertEquals(3, SkillCap.capFor(10, simulateFree = true))
    }
}

class SkillLoaderTest {

    private val wallet = skill("seed-vault-wallet", "com.solanamobile.wallet")
    private val kamino = skill("kamino", "com.solanamobile.wallet", triggers = listOf("kamino", "earn"))
    private val store = skill("dapp-store", "com.solanamobile.dappstore")

    @Test
    fun `the skill for the app in front is picked, and only one`() {
        assertEquals("dapp-store", SkillLoader.pick("com.solanamobile.dappstore", "how do I install an app", listOf(wallet, store))?.id)
    }

    @Test
    fun `no skill for another app, no app, or a skill that is not active`() {
        assertNull(SkillLoader.pick("com.android.chrome", "what is this", listOf(wallet, store)))
        assertNull(SkillLoader.pick(null, "what is this", listOf(wallet, store)))
        assertNull(SkillLoader.pick("com.solanamobile.dappstore", "install", listOf(wallet)))
    }

    @Test
    fun `two skills for one app - trigger words win, else the main skill`() {
        val active = listOf(kamino, wallet)
        assertEquals("kamino", SkillLoader.pick("com.solanamobile.wallet", "what does the Kamino earn thing do", active)?.id)
        assertEquals("seed-vault-wallet", SkillLoader.pick("com.solanamobile.wallet", "how do I receive", active)?.id)
        // "earning" is not the word "earn".
        assertEquals("seed-vault-wallet", SkillLoader.pick("com.solanamobile.wallet", "am I earning", active)?.id)
    }

    @Test
    fun `picking from the capped list never loads a greyed skill`() {
        val skills = listOf(skill("a"), skill("b"), skill("c"), store)
        val active = SkillCap.active(skills, skills.map { it.id }.toSet(), SkillCap.FREE)
        assertNull(SkillLoader.pick("com.solanamobile.dappstore", "install", active))
    }
}

package xyz.heylana.app.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.heylana.app.brain.SolanaApps
import java.io.File

/**
 * The five skills that ship in the APK, read from skills/ exactly as the build
 * packs them.
 */
class BuiltInSkillsTest {

    private val dir = listOf(File("../skills"), File("skills")).first { it.isDirectory }

    private val parsed = dir.listFiles { f -> f.name.endsWith(".md") }!!.sortedBy { it.name }.associate { file ->
        file.name to SkillFile.parse(file.readText(), builtIn = true)
    }

    private val skills = parsed.values.map { (it as SkillFile.Parsed.Ok).skill }

    @Test
    fun `all five are there, each named after its file`() {
        assertEquals(SkillStore.BUILT_IN_ORDER.sorted(), skills.map { it.id }.sorted())
        parsed.forEach { (name, result) ->
            assertTrue("$name did not parse: $result", result is SkillFile.Parsed.Ok)
            assertEquals(name.removeSuffix(".md"), (result as SkillFile.Parsed.Ok).skill.id)
        }
    }

    @Test
    fun `nothing in a built-in trips the sanitiser, and each stays under 400 tokens`() {
        parsed.forEach { (name, result) ->
            val ok = result as SkillFile.Parsed.Ok
            assertEquals("$name had lines stripped", emptyList<String>(), ok.stripped)
            assertTrue("$name is ${ok.skill.tokens} tokens", ok.skill.tokens < SkillFile.MAX_BODY_TOKENS)
        }
    }

    @Test
    fun `packages are the ones checked on the Seeker`() {
        val byId = skills.associateBy { it.id }
        assertEquals("com.solanamobile.wallet", byId.getValue("seed-vault-wallet").packageName)
        assertEquals("com.solanamobile.wallet", byId.getValue("kamino").packageName)
        assertEquals("com.solanamobile.seedvaultimpl", byId.getValue("seed-vault-signing").packageName)
        assertEquals("com.solanamobile.dappstore", byId.getValue("dapp-store").packageName)
        assertEquals("ag.jup.jupiter.android", byId.getValue("jupiter").packageName)
        skills.forEach { assertTrue("${it.id} is for an app Heylana does not know", SolanaApps.of(it.packageName) != null) }
    }

    @Test
    fun `in the Wallet, Kamino words pick Kamino and everything else the wallet skill`() {
        val active = SkillCap.active(skills, skills.map { it.id }.toSet(), SkillCap.PRO)
        assertEquals("kamino", SkillLoader.pick("com.solanamobile.wallet", "what does the Kamino earn thing do", active)?.id)
        assertEquals("seed-vault-wallet", SkillLoader.pick("com.solanamobile.wallet", "how do I receive USDC", active)?.id)
        assertEquals("dapp-store", SkillLoader.pick("com.solanamobile.dappstore", "how do I install an app", active)?.id)
    }
}

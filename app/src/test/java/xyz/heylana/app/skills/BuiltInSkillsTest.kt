package xyz.heylana.app.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.heylana.app.brain.SolanaApps
import java.io.File

/**
 * The eight skills that ship in the APK, read from skills/ exactly as the build
 * packs them.
 */
class BuiltInSkillsTest {

    private val dir = listOf(File("../skills"), File("skills")).first { it.isDirectory }

    private val parsed = dir.listFiles { f -> f.name.endsWith(".md") }!!.sortedBy { it.name }.associate { file ->
        file.name to SkillFile.parse(file.readText(), builtIn = true)
    }

    private val skills = parsed.values.map { (it as SkillFile.Parsed.Ok).skill }

    @Test
    fun `all eight are there, each named after its file`() {
        assertEquals(8, skills.size)
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
        assertEquals("com.solanamobile.wallet", byId.getValue("wallet-earn").packageName)
        assertEquals("com.solanamobile.seedvaultimpl", byId.getValue("seed-vault-signing").packageName)
        assertEquals("com.solanamobile.dappstore", byId.getValue("dapp-store").packageName)
        assertEquals("ag.jup.jupiter.android", byId.getValue("jupiter").packageName)
        assertEquals("com.google.android.youtube", byId.getValue("youtube").packageName)
        assertEquals("com.spotify.music", byId.getValue("spotify").packageName)
        assertTrue(byId.getValue("x402").forAnyApp)
        val solana = listOf("seed-vault-wallet", "wallet-earn", "seed-vault-signing", "dapp-store", "jupiter")
        solana.forEach { assertTrue("$it is for an app Heylana does not know", SolanaApps.of(byId.getValue(it).packageName) != null) }
    }

    @Test
    fun `in the Wallet, Earn words pick Wallet Earn and everything else the wallet skill`() {
        val active = SkillCap.active(skills, skills.map { it.id }.toSet(), SkillCap.PRO)
        assertEquals("wallet-earn", SkillLoader.pick("com.solanamobile.wallet", "what does the Kamino earn thing do", active)?.id)
        assertEquals("seed-vault-wallet", SkillLoader.pick("com.solanamobile.wallet", "how do I receive USDC", active)?.id)
        assertEquals("dapp-store", SkillLoader.pick("com.solanamobile.dappstore", "how do I install an app", active)?.id)
    }

    @Test
    fun `x402 loads in any app, only when its words are asked about`() {
        val active = SkillCap.active(skills, skills.map { it.id }.toSet(), SkillCap.PRO)
        assertEquals("x402", SkillLoader.pick("com.android.chrome", "what is this x402 payment request", active)?.id)
        assertEquals("x402", SkillLoader.pick("com.solanamobile.seedvaultimpl", "is this 402 payment required safe", active)?.id)
        assertEquals(null, SkillLoader.pick("com.android.chrome", "what does this page say", active))
        // In the Wallet, Earn's own words still pick Wallet Earn first.
        assertEquals("wallet-earn", SkillLoader.pick("com.solanamobile.wallet", "kamino earn paywall", active)?.id)
    }

    @Test
    fun `youtube and spotify load in their own apps`() {
        val active = SkillCap.active(skills, skills.map { it.id }.toSet(), SkillCap.PRO)
        assertEquals("youtube", SkillLoader.pick("com.google.android.youtube", "how do I turn on captions", active)?.id)
        assertEquals("spotify", SkillLoader.pick("com.spotify.music", "how do I make a playlist", active)?.id)
    }

    @Test
    fun `a skill for any app needs trigger words`() {
        val text = "---\nid: t\nname: T\npackage: any\nversion: 1\nauthor: a\nsummary: s\nprivacy: p\n---\nNotes."
        assertEquals(SkillFile.Parsed.Bad("any_app_needs_triggers"), SkillFile.parse(text, builtIn = false))
    }

    // ------------------------------------------ rewritten from the Seeker's screens (2026-09-21)

    private val rewritten = mapOf(
        "jupiter" to ("ag.jup.jupiter.android" to "teach me to swap"),
        "wallet-earn" to ("com.solanamobile.wallet" to "how do I earn on my USDC"),
        "dapp-store" to ("com.solanamobile.dappstore" to "how do I install an app"),
    )

    @Test
    fun `each rewritten skill loads for its own app and the question asked of it`() {
        val active = SkillCap.active(skills, skills.map { it.id }.toSet(), SkillCap.PRO)
        rewritten.forEach { (id, pair) ->
            val (pkg, question) = pair
            assertEquals("$question in $pkg", id, SkillLoader.pick(pkg, question, active)?.id)
        }
        // And on Free's three places too: the Wallet, Wallet Earn and Seed Vault signing stay first.
        // In the order the app lists them (SkillStore sorts by BUILT_IN_ORDER), since Free takes the first three.
        val ordered = skills.sortedBy { SkillStore.BUILT_IN_ORDER.indexOf(it.id) }
        val free = SkillCap.active(ordered, ordered.map { it.id }.toSet(), SkillCap.FREE)
        assertEquals("wallet-earn", SkillLoader.pick("com.solanamobile.wallet", "how do I earn on my USDC", free)?.id)
    }

    @Test
    fun `each rewritten skill says Heylana stops at the review and never approves`() {
        val byId = skills.associateBy { it.id }
        rewritten.keys.forEach { id ->
            val body = byId.getValue(id).body
            assertTrue("$id: no stop at the review", body.contains("Heylana stops at"))
            assertTrue("$id: never approves", body.contains("never approves"))
            assertTrue("$id: the signing glance", body.contains("signing glance takes over"))
        }
    }

    @Test
    fun `the buttons a step points at are the labels the screens show`() {
        val byId = skills.associateBy { it.id }
        val jupiter = byId.getValue("jupiter").body
        for (label in listOf("Trade", "Swap", "Market", "Limit", "Recurring", "Enter Amount", "MAX", "Markets", "Account")) {
            assertTrue("jupiter: $label", jupiter.contains(label))
        }
        val earn = byId.getValue("wallet-earn").body
        for (label in listOf("\"Earn 4.09% on your USDC\"", "\"Start\"", "\"Next\"", "\"Continue\"", "\"Available\"", "\"Earn APY\"", "Powered by kamino")) {
            assertTrue("wallet-earn: $label", earn.contains(label))
        }
        val store = byId.getValue("dapp-store").body
        for (label in listOf("\"Install\"", "\"Open\"", "\"My dApps\"", "\"dApp Updates\"", "\"Show more\"", "Categories")) {
            assertTrue("dapp-store: $label", store.contains(label))
        }
        // The old skills' mistakes, gone: Jupiter has no Portfolio tab, and the store's Settings is not in the bottom bar.
        assertTrue(!jupiter.contains("Portfolio tab"))
        assertTrue(!store.contains("Settings (bottom bar)"))
    }

    @Test
    fun `a yield is an estimate with the day it was read`() {
        val earn = skills.associateBy { it.id }.getValue("wallet-earn").body
        assertTrue(earn.contains("estimate"))
        assertTrue(earn.contains("\"about\""))
        assertTrue(earn.contains("2026-09-21"))
    }
}

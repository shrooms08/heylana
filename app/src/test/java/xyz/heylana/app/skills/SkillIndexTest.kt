package xyz.heylana.app.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.URI

class SkillIndexTest {

    private val base = "https://raw.githubusercontent.com/someone/heylana-skills/main/index.json"

    private fun raw(id: String, name: String = id.uppercase(), url: String = "$id.md") = SkillIndex.Raw(id, name, "s", "1", url)

    @Test
    fun `relative urls resolve against the index, so the folder can move`() {
        val entries = SkillIndex.entries(listOf(raw("chrome", url = "skills/chrome.md")), base)
        assertEquals("https://raw.githubusercontent.com/someone/heylana-skills/main/skills/chrome.md", entries.single().url)
    }

    @Test
    fun `plain http, odd schemes, bad ids, blank names and repeats are dropped`() {
        val entries = SkillIndex.entries(
            listOf(
                raw("a", url = "http://example.com/a.md"),
                raw("b", url = "file:///data/b.md"),
                raw("../c"),
                raw("d", name = ""),
                raw("e", url = "https://example.com/e.md"),
                raw("e", name = "E again", url = "https://example.com/e2.md")
            ),
            base
        )
        assertEquals(listOf("https://example.com/e.md"), entries.map { it.url })
    }

    @Test
    fun `loopback over http only in debug builds`() {
        assertFalse(SkillIndex.fetchable("http://127.0.0.1:8799/index.json", allowLoopback = false))
        assertTrue(SkillIndex.fetchable("http://127.0.0.1:8799/index.json", allowLoopback = true))
        assertFalse(SkillIndex.fetchable("http://example.com/index.json", allowLoopback = true))
    }

    @Test
    fun `the repo's index points at the chrome test skill, and it installs cleanly`() {
        val root = listOf(File("../skills-index"), File("skills-index")).first { it.isDirectory }
        val index = File(root, "index.json").readText()
        assertTrue(index.contains("\"id\": \"chrome\""))
        assertTrue(index.contains("\"url\": \"skills/chrome.md\""))
        val resolved = URI("file://${root.canonicalPath}/index.json").resolve("skills/chrome.md")
        val parsed = SkillFile.parse(File(resolved).readText(), builtIn = false) as SkillFile.Parsed.Ok
        assertEquals("chrome", parsed.skill.id)
        assertEquals("com.android.chrome", parsed.skill.packageName)
        assertTrue(parsed.stripped.isEmpty())
    }

    @Test
    fun `greyed rows say why`() {
        val row = SkillCap.Row(skill("x"), SkillCap.State.OVER_CAP)
        assertEquals("Over your plan's 3 skills. Switch another off to use it.", SkillsText.note(row, 3))
        assertEquals("Built in", SkillsText.note(row.copy(state = SkillCap.State.ACTIVE), 3))
    }
}

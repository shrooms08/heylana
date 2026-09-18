package xyz.heylana.app.home

import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.heylana.app.memory.MemoryRecord

/** The topic list's and the Memory screen's words. */
class LearnMemoryScreensTest {

    @Test
    fun `a kept line says what kind it is and the day it was kept`() {
        val lesson = MemoryRecord("a1", "skill_progress", "knows PDAs, 2026-09-18", "inferred", "2026-09-18T12:00:00.000Z")
        assertEquals("Lesson · 2026-09-18", MemoryText.detail(lesson))
        assertEquals("You said", MemoryText.kind("fact"))
        assertEquals("Preference", MemoryText.kind("preference"))
        assertEquals("Note", MemoryText.kind("anything else"))
    }

    @Test
    fun `learn and memory go back to home, and can be opened from adb`() {
        assertEquals(Screen.HOME, AppRoute.back(Screen.LEARN))
        assertEquals(Screen.HOME, AppRoute.back(Screen.MEMORY))
        assertEquals(Screen.LEARN, Screen.entries.first { it.name.equals("learn", ignoreCase = true) })
    }
}

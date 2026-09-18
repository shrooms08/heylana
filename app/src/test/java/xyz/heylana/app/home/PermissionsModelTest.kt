package xyz.heylana.app.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionsModelTest {

    @Test
    fun `the rows follow the system, re-checked on every resume`() {
        var system = PermissionState(overlay = true, screenReading = false, notifications = true, microphone = false)
        val model = PermissionsModel { system }
        assertFalse(model.rows.first { it.target == PermissionTarget.SCREEN_READING }.on)
        assertFalse(model.required)

        // The user switched screen reading on in Settings and came back: the resume re-checks.
        system = system.copy(screenReading = true)
        assertFalse("nothing changes until the re-check", model.rows.first { it.target == PermissionTarget.SCREEN_READING }.on)
        model.refresh()
        assertTrue(model.rows.first { it.target == PermissionTarget.SCREEN_READING }.on)
        assertTrue(model.required)

        // And the other way: switched off behind the app's back.
        system = system.copy(overlay = false)
        model.refresh()
        assertFalse(model.rows.first { it.target == PermissionTarget.OVERLAY }.on)
        assertFalse(model.required)
    }

    @Test
    fun `four rows, each with its one-line why, and only the microphone optional`() {
        val rows = PermissionsModel.rowsFor(PermissionState())
        assertEquals(
            listOf(PermissionTarget.OVERLAY, PermissionTarget.SCREEN_READING, PermissionTarget.NOTIFICATIONS, PermissionTarget.MICROPHONE),
            rows.map { it.target }
        )
        assertTrue(rows.all { it.why.isNotBlank() && it.why.length <= 60 })
        assertEquals(listOf(PermissionTarget.MICROPHONE), rows.filter { it.optional }.map { it.target })
    }

    @Test
    fun `the microphone is not needed to go on`() {
        assertTrue(PermissionsModel { PermissionState(true, true, true, microphone = false) }.required)
    }
}

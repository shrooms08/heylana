package xyz.heylana.app.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppRouteTest {

    @Test
    fun `a first run starts at sign in, and a returning user lands on home`() {
        assertEquals(Screen.SIGN_IN, AppRoute.start(firstRunDone = false, named = false))
        assertEquals(Screen.HOME, AppRoute.start(firstRunDone = true, named = true))
        // Done once, always home — even if the name was later cleared.
        assertEquals(Screen.HOME, AppRoute.start(firstRunDone = true, named = false))
    }

    @Test
    fun `a first run that stopped after the name picks up at the permissions`() {
        assertEquals(Screen.PERMISSIONS, AppRoute.start(firstRunDone = false, named = true))
    }

    @Test
    fun `sign in, name, permissions, home`() {
        assertEquals(Screen.PERMISSIONS, AppRoute.afterName())
        assertEquals(Screen.HOME, AppRoute.afterPermissions())
    }

    @Test
    fun `back goes home from everything home opens, and the first run has no way back`() {
        for (screen in listOf(Screen.VOICE, Screen.SKILLS, Screen.ADVANCED, Screen.PRIVACY, Screen.SETTINGS)) {
            assertEquals(Screen.HOME, AppRoute.back(screen))
        }
        for (screen in listOf(Screen.HOME, Screen.SIGN_IN, Screen.PERMISSIONS)) assertNull(AppRoute.back(screen))
    }
}

package xyz.heylana.app.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppRouteTest {

    @Test
    fun `a first run starts at the welcome, and a returning user lands on home`() {
        assertEquals(Screen.ONBOARDING, AppRoute.start(firstRunDone = false))
        assertEquals(Screen.HOME, AppRoute.start(firstRunDone = true))
    }

    @Test
    fun `the welcome ends at home, however it ended`() {
        assertEquals(Screen.HOME, AppRoute.afterOnboarding())
        assertEquals(Screen.HOME, AppRoute.afterPermissions())
    }

    @Test
    fun `back goes home from everything home opens, and the first run has no way back`() {
        for (screen in listOf(Screen.VOICE, Screen.SKILLS, Screen.ADVANCED, Screen.PRIVACY, Screen.SETTINGS, Screen.LEARN, Screen.MEMORY)) {
            assertEquals(Screen.HOME, AppRoute.back(screen))
        }
        for (screen in listOf(Screen.HOME, Screen.SIGN_IN, Screen.ONBOARDING, Screen.PERMISSIONS)) assertNull(AppRoute.back(screen))
    }
}

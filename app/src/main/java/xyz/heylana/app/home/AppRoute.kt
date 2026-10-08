package xyz.heylana.app.home

import xyz.heylana.app.Features

/** Where the app is. One activity; this is its whole map. */
enum class Screen { ONBOARDING, SIGN_IN, PERMISSIONS, HOME, VOICE, SKILLS, ADVANCED, PRIVACY, SETTINGS, LEARN, MEMORY }

/**
 * First run versus returning, and where back goes. Kept free of Android so it is tested.
 *
 * First run is the spoken welcome ([OnboardingFlow]): she introduces herself, the
 * permissions, the wallet, the name, the signing promise, then Home. Once Home has been
 * reached, the app opens on Home and the welcome is only replayed from Settings.
 */
object AppRoute {

    /** A screen that can be shown now: the Skill market only while its flag is on. */
    fun reachable(screen: Screen): Boolean = screen != Screen.SKILLS || Features.SKILL_MARKET

    fun start(firstRunDone: Boolean): Screen = when {
        firstRunDone -> Screen.HOME
        // A cold install meets her, not a permission list: she speaks the whole way through.
        else -> Screen.ONBOARDING
    }

    /** After the welcome, however it ended — finished or skipped: Home. */
    fun afterOnboarding(): Screen = Screen.HOME

    /** After the permissions screen, reached from Settings after the first run. */
    fun afterPermissions(): Screen = Screen.HOME

    /**
     * Where back goes from [screen]; null leaves the app. The first run has no way back;
     * after it, Permissions is opened from Settings and goes back there.
     */
    fun back(screen: Screen, firstRunDone: Boolean = false): Screen? = when (screen) {
        // The welcome has no way back; replayed from Settings, it still ends at Home.
        Screen.HOME, Screen.SIGN_IN, Screen.ONBOARDING -> null
        Screen.PERMISSIONS -> if (firstRunDone) Screen.SETTINGS else null
        Screen.VOICE, Screen.SKILLS, Screen.ADVANCED, Screen.PRIVACY, Screen.SETTINGS, Screen.LEARN, Screen.MEMORY -> Screen.HOME
    }
}

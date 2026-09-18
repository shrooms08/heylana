package xyz.heylana.app.home

/** Where the app is. One activity; this is its whole map. */
enum class Screen { SIGN_IN, PERMISSIONS, HOME, VOICE, SKILLS, ADVANCED, PRIVACY, SETTINGS }

/**
 * First run versus returning, and where back goes. Kept free of Android so it is tested.
 *
 * First run: sign in with the wallet (or go on without one), say what to be called, then
 * the permissions, then Home. Once Home has been reached, the app opens on Home.
 */
object AppRoute {

    fun start(firstRunDone: Boolean, named: Boolean): Screen = when {
        firstRunDone -> Screen.HOME
        !named -> Screen.SIGN_IN
        else -> Screen.PERMISSIONS
    }

    /** After the name is saved on the sign-in screen. */
    fun afterName(): Screen = Screen.PERMISSIONS

    /** After the permissions: Home, and the first run is over. */
    fun afterPermissions(): Screen = Screen.HOME

    /** Where back goes from [screen]; null leaves the app. The first run has no way back. */
    fun back(screen: Screen): Screen? = when (screen) {
        Screen.HOME, Screen.SIGN_IN, Screen.PERMISSIONS -> null
        Screen.VOICE, Screen.SKILLS, Screen.ADVANCED, Screen.PRIVACY, Screen.SETTINGS -> Screen.HOME
    }
}

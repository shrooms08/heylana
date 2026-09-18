package xyz.heylana.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import xyz.heylana.app.home.HeylanaApp
import xyz.heylana.app.ui.app.OrbDebug
import xyz.heylana.app.ui.app.OrbMode
import xyz.heylana.app.wallet.SeedVault

/**
 * The one activity. It routes: a first run goes through sign in, the name and the
 * permissions; a returning user lands on Home ([xyz.heylana.app.home.AppRoute]).
 */
class MainActivity : ComponentActivity() {

    /** Created with the activity, as Mobile Wallet Adapter requires. */
    private lateinit var seedVault: SeedVault

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        seedVault = SeedVault(this)
        // Debug builds only: `-e screen home|sign_in|permissions|voice|skills|advanced|privacy|settings`
        // opens that screen, so each can be looked at without wiping the app's data.
        val forced = if (BuildConfig.DEBUG) intent.getStringExtra("screen") else null
        if (BuildConfig.DEBUG) {
            OrbDebug.frozenT = intent.getStringExtra("orb_t")?.toDoubleOrNull()
            OrbDebug.mono = intent.getBooleanExtra("orb_mono", false)
            OrbDebug.forcedMode = intent.getStringExtra("orb_mode")?.let { m -> OrbMode.entries.firstOrNull { it.name.equals(m, ignoreCase = true) } }
        }
        setContent { HeylanaApp(this, seedVault, forced) }
    }
}

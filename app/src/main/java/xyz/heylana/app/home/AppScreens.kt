package xyz.heylana.app.home

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import xyz.heylana.app.settings.HeylanaSettings
import xyz.heylana.app.ui.app.Backdrop
import xyz.heylana.app.ui.app.GlassPage
import xyz.heylana.app.ui.app.HomeOrb
import xyz.heylana.app.ui.app.OrbMode
import xyz.heylana.app.ui.theme.GlassMode
import xyz.heylana.app.ui.theme.HeylanaType
import xyz.heylana.app.ui.theme.LocalHeylana
import xyz.heylana.app.wallet.SeedVault

/** Home and everything it opens. */
@Composable
fun AppScreens(
    screen: Screen,
    backdrop: Backdrop,
    settings: HeylanaSettings,
    activity: ComponentActivity,
    seedVault: SeedVault,
    onScreen: (Screen) -> Unit,
    onGlassMode: (GlassMode) -> Unit
) {
    val palette = LocalHeylana.current
    GlassPage(backdrop, background = {
        HomeOrb(OrbMode.IDLE, Modifier.align(Alignment.TopCenter).padding(top = 170.dp))
    }) {
        Text("Hi, ${settings.callMe}. What do you need?", Modifier.align(Alignment.Center), style = HeylanaType.body, color = palette.ink)
    }
}

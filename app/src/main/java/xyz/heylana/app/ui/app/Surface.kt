package xyz.heylana.app.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import xyz.heylana.app.ui.theme.LocalHeylana

/**
 * The app's one material: a flat surface. White 6% on black by default, 16dp corners,
 * and nothing else — no border, blur, rim, shadow, streak or beam. The overlay keeps its
 * liquid glass; the app does not use it.
 */
@Composable
fun FlatSurface(
    modifier: Modifier = Modifier,
    radius: Dp = SURFACE_RADIUS,
    fill: Color? = null,
    content: @Composable BoxScope.() -> Unit
) {
    val palette = LocalHeylana.current
    Box(modifier.clip(RoundedCornerShape(radius)).background(fill ?: palette.surface), content = content)
}

/** A page: the ground, anything drawn behind (the orb, the voice glow), and the content. */
@Composable
fun FlatPage(background: @Composable BoxScope.() -> Unit = {}, content: @Composable BoxScope.() -> Unit) {
    val palette = LocalHeylana.current
    Box(Modifier.fillMaxSize().background(palette.ground)) {
        background()
        content()
    }
}

/** Cards, rows and fields. */
val SURFACE_RADIUS = 16.dp

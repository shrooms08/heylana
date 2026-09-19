package xyz.heylana.app.ui.app

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Text
import xyz.heylana.app.R
import xyz.heylana.app.ui.theme.HeylanaType
import xyz.heylana.app.ui.theme.monoNumbers
import xyz.heylana.app.ui.theme.LocalHeylana

/** A tap with no ripple. */
@Composable
fun Modifier.tap(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled, onClick = onClick)

/** A top-bar button: a 24dp icon on nothing, with a 48dp place to tap. */
@Composable
fun BarButton(glyph: Glyph, description: String, onClick: () -> Unit) {
    val palette = LocalHeylana.current
    Box(Modifier.size(48.dp).clip(CircleShape).tap(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(glyph, palette.ink, size = 24.dp)
        Text(description, Modifier.size(0.dp))
    }
}

/** The name at the top of home: the mark, "Heylana", and a line under it. Flat, no pill. */
@Composable
fun MarkPill(subtitle: String, subtitleAccent: Boolean = false) {
    val palette = LocalHeylana.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_heylana_mark), null, Modifier.size(22.dp), colorFilter = ColorFilter.tint(palette.ink))
        Spacer(Modifier.width(10.dp))
        Column {
            Text("Heylana", style = HeylanaType.label, color = palette.ink)
            Text(subtitle, style = HeylanaType.tiny, color = if (subtitleAccent) palette.accentText else palette.inkSecondary)
        }
    }
}

/** A suggestion chip: a flat pill in the chip fill with a solid 1dp edge, white words. */
@Composable
fun FlatChip(glyph: Glyph, label: String, onClick: () -> Unit) {
    val palette = LocalHeylana.current
    FlatSurface(
        Modifier.height(44.dp).border(1.dp, palette.borderSolid, RoundedCornerShape(22.dp)).tap(onClick = onClick),
        radius = 22.dp, fill = palette.surfaceHigh
    ) {
        Row(Modifier.padding(horizontal = 16.dp).align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            Icon(glyph, palette.ink, size = 18.dp)
            Spacer(Modifier.width(8.dp))
            Text(label, style = HeylanaType.label, color = palette.ink, maxLines = 1)
        }
    }
}

/** The accent button: "Go Pro", "Save key", "Continue". A solid accent pill with dark words. */
@Composable
fun AccentButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, height: Dp = 48.dp) {
    val palette = LocalHeylana.current
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(height / 2))
            .background(palette.accent)
            .tap(enabled, onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = HeylanaType.bodyMedium, color = palette.onAccent)
    }
}

/**
 * A row: a 24dp icon (or a letter), a 16sp title and a line under it, and whatever sits at
 * the end. On a flat surface when [card] (the default), or bare in a list (the menu).
 * [selected] tints it with the accent.
 */
@Composable
fun FlatRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    glyph: Glyph? = null,
    letter: String? = null,
    selected: Boolean = false,
    enabled: Boolean = true,
    card: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    val palette = LocalHeylana.current
    val fill = when {
        selected -> palette.accentSoft
        card -> palette.surface
        else -> palette.ground.copy(alpha = 0f)
    }
    FlatSurface(
        modifier.fillMaxWidth().heightIn(min = 56.dp).then(if (onClick != null) Modifier.tap(enabled, onClick) else Modifier),
        fill = fill
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp).alpha(if (enabled) 1f else 0.5f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (glyph != null) Icon(glyph, palette.ink, size = 24.dp)
            else if (letter != null) Box(
                Modifier.size(28.dp).clip(CircleShape).background(palette.surfaceHigh),
                contentAlignment = Alignment.Center
            ) { Text(letter, style = HeylanaType.label, color = palette.ink) }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = HeylanaType.body, color = palette.ink)
                if (subtitle != null) Text(monoNumbers(subtitle), style = HeylanaType.small, color = palette.inkSecondary)
            }
            trailing()
        }
    }
}


/** A switch: 46 × 27, accent when on. */
@Composable
fun FlatSwitch(on: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val palette = LocalHeylana.current
    Box(
        Modifier.size(46.dp, 27.dp).clip(CircleShape).background(if (on) palette.accent else palette.switchOff)
            .alpha(if (enabled) 1f else 0.5f).tap(enabled) { onChange(!on) }
    ) {
        Box(
            Modifier.align(Alignment.CenterStart).offset(x = if (on) 21.dp else 2.dp).size(23.dp).clip(CircleShape).background(palette.onAccent)
        )
    }
}

/** A small heading over a section: "Buddy", "Account", "Provider". */
@Composable
fun SectionHead(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = HeylanaType.label, color = LocalHeylana.current.inkSecondary)
}

/** A field: 52dp, 16dp corners, white 8%, no border. */
@Composable
fun FlatField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    style: TextStyle = HeylanaType.body,
    secret: Boolean = false,
    imeAction: ImeAction = ImeAction.Done,
    onAction: () -> Unit = {}
) {
    val palette = LocalHeylana.current
    Box(
        modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(SURFACE_RADIUS)).background(palette.surfaceHigh)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        if (value.isEmpty()) Text(placeholder, style = style, color = palette.inkTertiary)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = style.copy(color = palette.ink),
            cursorBrush = SolidColor(palette.accent),
            visualTransformation = if (secret) SecretTransformation else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(imeAction = imeAction),
            keyboardActions = KeyboardActions(onAny = { onAction() }),
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** A key shown as its start and its last four, the way the export shows it. */
private val SecretTransformation = VisualTransformation { text ->
    val raw = text.text
    val shown = if (raw.length <= 10) "•".repeat(raw.length) else raw.take(7) + "•".repeat(raw.length - 11) + raw.takeLast(4)
    androidx.compose.ui.text.input.TransformedText(
        androidx.compose.ui.text.AnnotatedString(shown), androidx.compose.ui.text.input.OffsetMapping.Identity
    )
}

/** The top bar of an inner screen: back on the left, something on the right. */
@Composable
fun InnerTopBar(onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        BarButton(Glyph.BACK, "Back", onBack)
        Spacer(Modifier.weight(1f))
        Box(Modifier.padding(end = 12.dp)) { trailing() }
    }
}

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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
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
import xyz.heylana.app.ui.theme.LocalHeylana

/** A tap with no ripple: the glass itself is the feedback. */
@Composable
fun Modifier.tap(enabled: Boolean = true, onClick: () -> Unit): Modifier =
    clickable(remember { MutableInteractionSource() }, indication = null, enabled = enabled, onClick = onClick)

/** The 46dp round glass button of the top bars: menu, speaker, back. */
@Composable
fun RoundGlassButton(backdrop: Backdrop?, glyph: Glyph, description: String, onClick: () -> Unit, size: Dp = 46.dp) {
    val palette = LocalHeylana.current
    GlassSurface(backdrop, Modifier.size(size).tap(onClick = onClick), radius = size / 2) {
        Icon(glyph, palette.ink, Modifier.align(Alignment.Center), size = 20.dp)
        Text(description, Modifier.size(0.dp))
    }
}

/** The pill at the top of home: the mark, "Heylana", and a line under it. */
@Composable
fun MarkPill(backdrop: Backdrop?, subtitle: String, subtitleAccent: Boolean = false) {
    val palette = LocalHeylana.current
    GlassSurface(backdrop, Modifier.height(46.dp), radius = 23.dp) {
        Row(Modifier.padding(start = 12.dp, end = 18.dp).align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
            Image(painterResource(R.drawable.ic_heylana_mark), null, Modifier.size(24.dp), colorFilter = ColorFilter.tint(palette.ink))
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Heylana", style = HeylanaType.label, color = palette.ink)
                Text(subtitle, style = HeylanaType.tiny, color = if (subtitleAccent) palette.accentSoft else palette.inkSecondary)
            }
        }
    }
}

/** A suggestion chip: 44dp, an icon in soft purple, a 13sp label. */
@Composable
fun GlassChip(backdrop: Backdrop?, glyph: Glyph, label: String, onClick: () -> Unit) {
    val palette = LocalHeylana.current
    GlassSurface(backdrop, Modifier.height(44.dp).tap(onClick = onClick), radius = 22.dp) {
        Row(Modifier.padding(horizontal = 18.dp).align(Alignment.CenterStart), verticalAlignment = Alignment.CenterVertically) {
            Icon(glyph, palette.accentSoft, size = 17.dp)
            Spacer(Modifier.width(9.dp))
            Text(label, style = HeylanaType.label, color = palette.ink, maxLines = 1)
        }
    }
}

/** The accent button: "Go Pro", "Save key", "Continue". A solid purple pill with a glow under it. */
@Composable
fun AccentButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, height: Dp = 48.dp) {
    val palette = LocalHeylana.current
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .alpha(if (enabled) 1f else 0.45f)
            .drawBehind {
                drawRoundRect(
                    Brush.radialGradient(listOf(palette.accent.copy(alpha = 0.45f), palette.accent.copy(alpha = 0f)), center, size.width * 0.6f),
                    topLeft = androidx.compose.ui.geometry.Offset(0f, 6.dp.toPx()), size = size,
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
                )
            }
            .clip(RoundedCornerShape(height / 2))
            .background(palette.accent)
            .tap(enabled, onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(text, style = HeylanaType.bodyMedium, color = palette.onAccent)
    }
}

/** A row of glass: an icon tile, a title and a line under it, and whatever sits at the end. */
@Composable
fun GlassRow(
    backdrop: Backdrop?,
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    glyph: Glyph? = null,
    letter: String? = null,
    lit: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {}
) {
    val palette = LocalHeylana.current
    GlassSurface(
        backdrop,
        modifier.fillMaxWidth().heightIn(min = 64.dp).then(if (onClick != null) Modifier.tap(enabled, onClick) else Modifier),
        radius = 24.dp,
        lit = lit
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 15.dp).alpha(if (enabled) 1f else 0.5f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            if (glyph != null || letter != null) {
                Box(
                    Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(palette.tileFill)
                        .border(1.dp, palette.tileHairline, RoundedCornerShape(14.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    if (glyph != null) Icon(glyph, palette.accentSoft, size = 20.dp)
                    else Text(letter ?: "", style = HeylanaType.bodyMedium, color = palette.ink)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = HeylanaType.body, color = palette.ink)
                if (subtitle != null) Text(subtitle, style = HeylanaType.small, color = palette.inkSecondary)
            }
            trailing()
        }
    }
}

/** The export's switch: 46 × 27, accent when on. */
@Composable
fun GlassSwitch(on: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
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

/** A small caps heading over a section: "PROVIDER", "API KEY". */
@Composable
fun SectionHead(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier, style = HeylanaType.caps, color = LocalHeylana.current.inkTertiary)
}

/** A field inside glass: 52dp, 16dp corners. */
@Composable
fun GlassField(
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
        modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp)).background(palette.fieldFill)
            .border(1.dp, palette.glassHairline, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp),
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
fun InnerTopBar(backdrop: Backdrop?, onBack: () -> Unit, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        RoundGlassButton(backdrop, Glyph.BACK, "Back", onBack)
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

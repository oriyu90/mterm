package dev.studiorizi.mterm.full.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.studiorizi.mterm.core.terminal_emulator.Cell
import dev.studiorizi.mterm.full.R

/**
 * App theme selector (persisted via [dev.studiorizi.mterm.core.data.MTermPrefs.theme]).
 * Unknown stored values fall back to SYSTEM so a corrupt pref never breaks launch.
 */
enum class AppTheme(val key: String) {
    SYSTEM("system"),
    LIGHT("light"),
    DARK("dark"),
    RETRO("retro"),
    ;

    companion object {
        fun of(key: String?): AppTheme = values().firstOrNull { it.key == key } ?: SYSTEM
    }
}

val LocalRetro = staticCompositionLocalOf { false }

/** Win98 surface colors. */
private val RetroGray = Color(0xFFC0C0C0)
private val RetroDarkGray = Color(0xFF808080)
private val RetroWhite = Color(0xFFFFFFFF)
private val RetroBlack = Color(0xFF000000)
private val RetroNavy = Color(0xFF000080)
private val RetroTeal = Color(0xFF008080)
private val RetroMaroon = Color(0xFF800000)

/** Material3 slot mapping for the retro look (teal desktop, gray panels, navy titles). */
fun retroColorScheme(): ColorScheme = lightColorScheme(
    primary = RetroNavy,
    onPrimary = RetroWhite,
    primaryContainer = RetroNavy,
    onPrimaryContainer = RetroWhite,
    secondary = RetroDarkGray,
    onSecondary = RetroWhite,
    tertiary = RetroTeal,
    onTertiary = RetroWhite,
    background = RetroTeal,
    onBackground = RetroWhite,
    surface = RetroGray,
    onSurface = RetroBlack,
    surfaceVariant = RetroGray,
    onSurfaceVariant = RetroBlack,
    surfaceContainerLowest = RetroWhite,
    surfaceContainerLow = RetroGray,
    surfaceContainer = RetroGray,
    surfaceContainerHigh = RetroGray,
    surfaceContainerHighest = RetroWhite,
    error = RetroMaroon,
    onError = RetroWhite,
    outline = RetroDarkGray,
    outlineVariant = RetroDarkGray,
)

fun retroShapes(): Shapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small = RoundedCornerShape(0.dp),
    medium = RoundedCornerShape(0.dp),
    large = RoundedCornerShape(0.dp),
    extraLarge = RoundedCornerShape(0.dp),
)

/**
 * Win98 bevel border: raised = white top/left + gray bottom/right inside a
 * black outer line; pressed swaps the inner pair (sunken). Drawn with plain
 * lines so it can never throw on odd sizes (unlike text layout).
 */
fun Modifier.retroBevel(pressed: Boolean = false, thickness: Dp = 2.dp): Modifier =
    this.drawBehind {
        val t = thickness.toPx().coerceAtLeast(2f)
        val w = size.width
        val h = size.height
        if (w <= 0f || h <= 0f) return@drawBehind
        val outer = RetroBlack
        val light = if (pressed) RetroDarkGray else RetroWhite
        val dark = if (pressed) RetroWhite else RetroDarkGray
        // Outer black frame.
        drawRect(outer, Offset.Zero, Size(w, 1f.coerceAtMost(h)))
        drawRect(outer, Offset(0f, h - 1f.coerceAtMost(h)), Size(w, 1f.coerceAtMost(h)))
        drawRect(outer, Offset.Zero, Size(1f.coerceAtMost(w), h))
        drawRect(outer, Offset(w - 1f.coerceAtMost(w), 0f), Size(1f.coerceAtMost(w), h))
        // Inner bevel (only when there is room).
        if (w > t * 2 && h > t * 2) {
            val b = t - 1f
            drawLine(light, Offset(1f, 1f), Offset(w - 1f, 1f), b)
            drawLine(light, Offset(1f, 1f), Offset(1f, h - 1f), b)
            drawLine(dark, Offset(1f, h - t), Offset(w - 1f, h - t), b)
            drawLine(dark, Offset(w - t, 1f), Offset(w - t, h - 1f), b)
        }
    }

/** Themed button: M3 Button normally, raised Win98 button in retro mode. */
@Composable
fun TButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    if (LocalRetro.current) {
        val source = remember { MutableInteractionSource() }
        val pressed by source.collectIsPressedAsState()
        val fg = if (enabled) RetroBlack else RetroDarkGray
        Box(
            modifier = modifier
                .defaultMinSize(minHeight = 48.dp)
                .retroBevel(pressed = pressed)
                .background(RetroGray)
                .clickable(
                    interactionSource = source,
                    indication = null,
                    role = Role.Button,
                    enabled = enabled,
                    onClick = onClick,
                )
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(
                LocalContentColor provides fg,
                content = { Row(content = content) },
            )
        }
    } else {
        Button(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
    }
}

/** Themed text button: M3 TextButton normally, small raised button in retro mode. */
@Composable
fun TTextButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    if (LocalRetro.current) {
        TButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
    } else {
        TextButton(onClick = onClick, modifier = modifier, enabled = enabled, content = content)
    }
}

/** Themed card: M3 Card normally, raised gray panel in retro mode. */
@Composable
fun TCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    if (LocalRetro.current) {
        Box(
            modifier = modifier
                .retroBevel()
                .background(RetroGray)
                .padding(2.dp),
        ) {
            content()
        }
    } else {
        Card(modifier = modifier, content = { content() })
    }
}

/** Themed top bar: M3 TopAppBar normally, navy Win98 title bar in retro mode. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TTopBar(
    title: String,
    onDiagnostics: () -> Unit,
    onSettings: () -> Unit,
) {
    if (LocalRetro.current) {
        Row(
            modifier = Modifier
                .background(RetroNavy)
                .statusBarsPadding()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                color = RetroWhite,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            RetroSquareButton(
                label = "?",
                desc = stringResource(R.string.diagnostics),
                onClick = onDiagnostics,
            )
            RetroSquareButton(
                label = "S",
                desc = stringResource(R.string.settings),
                onClick = onSettings,
            )
        }
    } else {
        TopAppBar(
            title = { Text(title) },
            actions = {
                IconButton(onClick = onDiagnostics) {
                    Icon(
                        Icons.Filled.Info,
                        contentDescription = stringResource(R.string.diagnostics),
                    )
                }
                IconButton(onClick = onSettings) {
                    Icon(
                        Icons.Filled.Settings,
                        contentDescription = stringResource(R.string.settings),
                    )
                }
            },
        )
    }
}

@Composable
private fun RetroSquareButton(label: String, desc: String, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    Box(
        modifier = Modifier
            .padding(start = 4.dp)
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .retroBevel(pressed = pressed)
            .background(RetroGray)
            .semantics { contentDescription = desc }
            .clickable(
                interactionSource = source,
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = RetroBlack, fontWeight = FontWeight.Bold)
    }
}

/** Slider colors: M3 defaults normally, navy-on-white Win98 style in retro mode. */
@Composable
fun retroAwareSliderColors(): androidx.compose.material3.SliderColors =
    if (LocalRetro.current) {
        SliderDefaults.colors(
            thumbColor = RetroNavy,
            activeTrackColor = RetroNavy,
            inactiveTrackColor = RetroWhite,
        )
    } else {
        SliderDefaults.colors()
    }

/** Themed switch: M3 Switch normally, Win98 checkbox-style toggle in retro mode. */
@Composable
fun TSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    label: @Composable (() -> Unit)? = null,
) {
    if (LocalRetro.current) {
        val source = remember { MutableInteractionSource() }
        Row(
            modifier = modifier
                .defaultMinSize(minHeight = 48.dp)
                .clickable(
                    interactionSource = source,
                    indication = null,
                    role = Role.Checkbox,
                    onClick = { onCheckedChange(!checked) },
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .defaultMinSize(minWidth = 28.dp, minHeight = 28.dp)
                    .retroBevel(pressed = checked)
                    .background(RetroWhite),
                contentAlignment = Alignment.Center,
            ) {
                if (checked) {
                    Text("✓", color = RetroBlack, fontWeight = FontWeight.Bold)
                }
            }
            label?.let {
                Box(modifier = Modifier.padding(start = 8.dp)) { it() }
            }
        }
    } else {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Switch(checked = checked, onCheckedChange = onCheckedChange)
            label?.let {
                Box(modifier = Modifier.padding(start = 4.dp)) { it() }
            }
        }
    }
}
/** Themed filter chip: M3 FilterChip normally, square navy-selected chip in retro mode. */
@Composable
fun TFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    label: @Composable () -> Unit,
) {
    if (LocalRetro.current) {
        val source = remember { MutableInteractionSource() }
        val pressed by source.collectIsPressedAsState()
        val bg = if (selected) RetroNavy else RetroGray
        val fg = if (!enabled) {
            RetroDarkGray
        } else if (selected) {
            RetroWhite
        } else {
            RetroBlack
        }
        Box(
            modifier = modifier
                .defaultMinSize(minHeight = 48.dp)
                .retroBevel(pressed = pressed || selected)
                .background(bg)
                .clickable(
                    interactionSource = source,
                    indication = null,
                    role = Role.Checkbox,
                    enabled = enabled,
                    onClick = onClick,
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            CompositionLocalProvider(
                LocalContentColor provides fg,
                content = { label() },
            )
        }
    } else {
        FilterChip(
            selected = selected,
            onClick = onClick,
            modifier = modifier,
            enabled = enabled,
            label = label,
        )
    }
}

// ---------------------------------------------------------------------------
// Terminal palettes (one per app theme; terminal keeps its own dark surface
// in light/retro modes like classic console windows).
// ---------------------------------------------------------------------------

/** 16-color terminal palette plus surface/cursor/search colors. */
data class TerminalPalette(
    val background: Color,
    val defaultFg: Color,
    val colors: List<Color>,
    val cursorBg: Color,
    val cursorFg: Color,
    val searchBg: Color,
    val searchCurrentBg: Color,
) {
    fun fg(index: Int): Color = colors[index.coerceIn(0, 15)]
    fun bg(index: Int): Color =
        if (index == Cell.DEFAULT_BG) Color.Transparent else colors[index.coerceIn(0, 15)]
}

private val DarkTermColors = listOf(
    Color(0xFF1A1B26), // 0 black (terminal bg)
    Color(0xFFF7768E), // 1 red
    Color(0xFF9ECE6A), // 2 green
    Color(0xFFE0AF68), // 3 yellow
    Color(0xFF7AA2F7), // 4 blue
    Color(0xFFBB9AF7), // 5 magenta
    Color(0xFF7DCFFF), // 6 cyan
    Color(0xFFC0CAF5), // 7 white (default fg)
    Color(0xFF414868), // 8 bright black
    Color(0xFFFF899D), // 9 bright red
    Color(0xFFB9F27C), // 10 bright green
    Color(0xFFFFC777), // 11 bright yellow
    Color(0xFF8DB0FB), // 12 bright blue
    Color(0xFFC7A9FA), // 13 bright magenta
    Color(0xFF93E1FF), // 14 bright cyan
    Color(0xFFD5D6DB), // 15 bright white
)

private val LightTermColors = listOf(
    Color(0xFFF5F5F0), // 0 paper (terminal bg)
    Color(0xFFB00020), // 1 red
    Color(0xFF1B7A2E), // 2 green
    Color(0xFF8A6D00), // 3 yellow
    Color(0xFF0B3D91), // 4 blue
    Color(0xFF7B1FA2), // 5 magenta
    Color(0xFF00677A), // 6 cyan
    Color(0xFF1A1B26), // 7 ink (default fg)
    Color(0xFF6B6B6B), // 8 bright black
    Color(0xFFD00036), // 9 bright red
    Color(0xFF2E9E44), // 10 bright green
    Color(0xFFB08D00), // 11 bright yellow
    Color(0xFF2B5FC7), // 12 bright blue
    Color(0xFF9C27B0), // 13 bright magenta
    Color(0xFF0086A0), // 14 bright cyan
    Color(0xFF000000), // 15 bright white -> black ink
)

private val RetroTermColors = listOf(
    Color(0xFF000000), // 0 black (DOS window)
    Color(0xFFAA0000), // 1 red
    Color(0xFF00AA00), // 2 green
    Color(0xFFAA5500), // 3 yellow/brown
    Color(0xFF0000AA), // 4 blue
    Color(0xFFAA00AA), // 5 magenta
    Color(0xFF00AAAA), // 6 cyan
    Color(0xFFC0C0C0), // 7 silver (default fg)
    Color(0xFF555555), // 8 bright black
    Color(0xFFFF5555), // 9 bright red
    Color(0xFF55FF55), // 10 bright green
    Color(0xFFFFFF55), // 11 bright yellow
    Color(0xFF5555FF), // 12 bright blue
    Color(0xFFFF55FF), // 13 bright magenta
    Color(0xFF55FFFF), // 14 bright cyan
    Color(0xFFFFFFFF), // 15 bright white
)

val DarkTerminalPalette = TerminalPalette(
    background = Color(0xFF1A1B26),
    defaultFg = Color(0xFFC0CAF5),
    colors = DarkTermColors,
    cursorBg = Color(0xFFC0CAF5),
    cursorFg = Color(0xFF1A1B26),
    searchBg = Color(0xFF665C1E),
    searchCurrentBg = Color(0xFFE0AF68),
)

val LightTerminalPalette = TerminalPalette(
    background = Color(0xFFF5F5F0),
    defaultFg = Color(0xFF1A1B26),
    colors = LightTermColors,
    cursorBg = Color(0xFF1A1B26),
    cursorFg = Color(0xFFF5F5F0),
    searchBg = Color(0xFFFFE082),
    searchCurrentBg = Color(0xFFFFB300),
)

val RetroTerminalPalette = TerminalPalette(
    background = Color(0xFF000000),
    defaultFg = Color(0xFFC0C0C0),
    colors = RetroTermColors,
    cursorBg = Color(0xFFC0C0C0),
    cursorFg = Color(0xFF000000),
    searchBg = Color(0xFF555500),
    searchCurrentBg = Color(0xFFFFFF55),
)

fun terminalPaletteFor(theme: AppTheme): TerminalPalette = when (theme) {
    AppTheme.LIGHT -> LightTerminalPalette
    AppTheme.RETRO -> RetroTerminalPalette
    AppTheme.DARK, AppTheme.SYSTEM -> DarkTerminalPalette
}

/** M3 color scheme for the non-retro themes (baseline schemes, contrast-proven). */
fun appColorScheme(theme: AppTheme, dark: Boolean): ColorScheme = when (theme) {
    AppTheme.LIGHT -> lightColorScheme()
    AppTheme.DARK -> darkColorScheme()
    AppTheme.RETRO -> retroColorScheme()
    AppTheme.SYSTEM -> if (dark) darkColorScheme() else lightColorScheme()
}

/** Square corners in retro mode, M3 defaults otherwise. */
@Composable
fun appShapes(theme: AppTheme): Shapes =
    if (theme == AppTheme.RETRO) retroShapes() else MaterialTheme.shapes

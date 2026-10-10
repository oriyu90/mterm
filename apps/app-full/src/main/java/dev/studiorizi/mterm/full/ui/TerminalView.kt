package dev.studiorizi.mterm.full.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.studiorizi.mterm.core.terminal_emulator.Cell
import dev.studiorizi.mterm.core.terminal_emulator.TerminalEmulator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Terminal surface bound to a [TerminalEmulator].
 *
 * Rows render as [Text] lines with per-cell colors (robust: no Canvas text
 * drawing, which crashes on zero-size first frames). The block cursor is a
 * reversed-color cell. [tick] drives recomposition whenever PTY output
 * arrives. Colors come from [palette] so the app theme (light/dark/retro)
 * re-skins the console without touching emulation.
 *
 * Text input arrives via the invisible [BasicTextField] (IME; its own cursor
 * is hidden) and hardware key events (control keys, arrows, enter,
 * backspace). Size changes debounce into [onResize] (rows/cols for TIOCSWINSZ).
 */
@Composable
fun TerminalView(
    emulator: TerminalEmulator?,
    tick: Long,
    fontSizeSp: Float,
    focused: Boolean,
    onInput: (ByteArray) -> Unit,
    onResize: (rows: Int, cols: Int) -> Unit,
    modifier: Modifier = Modifier,
    palette: TerminalPalette = DarkTerminalPalette,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    // Keyed on the session's emulator: switching sessions starts clean so
    // the prefix-diff never leaks one session's text into another.
    var imeBuffer by remember(emulator) { mutableStateOf("") }
    var lastSizePx by remember { mutableStateOf(0 to 0) }
    var resizeJob by remember { mutableStateOf<Job?>(null) }
    val scroll = rememberScrollState()

    // Snapshot the visible content. tick is read so recomposition follows output.
    @Suppress("UNUSED_EXPRESSION")
    tick.coerceAtLeast(0)
    val snapshot = remember(tick, emulator) {
        emulator?.let { emu ->
            val sbCount = emu.scrollbackSize()
            // Cap rendered scrollback for frame budget (newest 300 lines).
            val from = (sbCount - MAX_RENDER_SCROLLBACK).coerceAtLeast(0)
            val back = (from until sbCount).mapNotNull { emu.getScrollbackCells(it) }
            val rows = (0 until emu.rows).mapNotNull { emu.getRowCells(it) }
            Snapshot(
                back = back,
                rows = rows,
                cursorRow = emu.cursorRow,
                cursorCol = emu.cursorCol,
            )
        }
    }

    // Auto-scroll to the bottom on new output unless the user scrolled up.
    LaunchedEffect(tick) {
        if (snapshot != null && !scroll.isScrollInProgress &&
            scroll.value >= scroll.maxValue - SCROLL_STICK_PX
        ) {
            scroll.scrollTo(scroll.maxValue)
        }
    }

    LaunchedEffect(focused) {
        if (focused) {
            try {
                focusRequester.requestFocus()
            } catch (_: IllegalStateException) {
                // Not attached yet; the tap handler retries.
            }
        }
    }

    val lineHeight = (fontSizeSp * LINE_HEIGHT_FACTOR).sp
    // Monospace advance is ~0.6em; used only to estimate cols for TIOCSWINSZ.
    val cellWidthPx = with(density) { (fontSizeSp.sp.toPx() * CELL_ADVANCE_FACTOR) }

    Box(
        modifier = modifier
            .background(palette.background)
            .onSizeChanged { size ->
                lastSizePx = size.width to size.height
                resizeJob?.cancel()
                resizeJob = scope.launch {
                    delay(RESIZE_DEBOUNCE_MS)
                    val (w, h) = lastSizePx
                    if (w > 0 && h > 0) {
                        val fontPx = with(density) { fontSizeSp.sp.toPx() }
                        val cols = (w / (fontPx * CELL_ADVANCE_FACTOR)).toInt().coerceIn(2, 500)
                        val rows = (h / (fontPx * LINE_HEIGHT_FACTOR)).toInt().coerceIn(2, 500)
                        onResize(rows, cols)
                    }
                }
            },
    ) {
        if (snapshot != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scroll),
            ) {
                snapshot.back.forEach { cells ->
                    TerminalLine(cells, cursorCol = -1, showCursor = false, fontSizeSp = fontSizeSp, lineHeight = lineHeight, palette = palette)
                }
                snapshot.rows.forEachIndexed { index, cells ->
                    TerminalLine(
                        cells,
                        cursorCol = if (index == snapshot.cursorRow) snapshot.cursorCol else -1,
                        showCursor = hasFocus && index == snapshot.cursorRow,
                        fontSizeSp = fontSizeSp,
                        lineHeight = lineHeight,
                        palette = palette,
                    )
                }
            }
        }
        // Invisible IME input: captures software-keyboard text. Its own cursor
        // is hidden (transparent) so only the terminal block cursor shows.
        // The buffer MIRRORS the IME content (never cleared mid-stream):
        // clearing unilaterally races Gboard's multi-step commits, which
        // restart from scratch and corrupt pastes into duplicated fragments.
        // Instead we diff by longest common prefix and forward only the
        // delta (suffix bytes, DEL per removed char). The buffer resyncs
        // (clears) on submit, when the IME is idle. Cleared semantics keep
        // typed commands out of the accessibility tree (no secrets in
        // TalkBack).
        BasicTextField(
            value = imeBuffer,
            onValueChange = { next ->
                val common = imeBuffer.commonPrefixWith(next).length
                val removed = (imeBuffer.length - common).coerceAtLeast(0)
                repeat(removed) { onInput(byteArrayOf(0x7F)) }
                if (next.length > common) {
                    onInput(next.substring(common).toByteArray(Charsets.UTF_8))
                }
                imeBuffer = next
            },
            // Terminals need raw ASCII: Password forces Gboard-class IMEs into
            // half-width alphanumeric with no conversion or suggestions
            // (Ascii alone is ignored by the Japanese layout). No visual
            // transformation is applied, so text stays visible. Go submits
            // the command (singleLine would otherwise swallow soft Enter).
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Password,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(
                onGo = {
                    onInput(byteArrayOf(0x0D))
                    imeBuffer = ""
                },
            ),
            singleLine = true,
            modifier = Modifier
                .fillMaxSize()
                .clearAndSetSemantics {}
                .focusRequester(focusRequester)
                .onFocusChanged { hasFocus = it.isFocused }
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    val bytes: ByteArray? = when (event.key) {
                        Key.Enter, Key.NumPadEnter -> byteArrayOf(0x0D)
                        Key.Backspace -> byteArrayOf(0x7F)
                        Key.Tab -> byteArrayOf(0x09)
                        Key.Escape -> byteArrayOf(0x1B)
                        Key.DirectionUp -> byteArrayOf(0x1B, '['.code.toByte(), 'A'.code.toByte())
                        Key.DirectionDown -> byteArrayOf(0x1B, '['.code.toByte(), 'B'.code.toByte())
                        Key.DirectionRight -> byteArrayOf(0x1B, '['.code.toByte(), 'C'.code.toByte())
                        Key.DirectionLeft -> byteArrayOf(0x1B, '['.code.toByte(), 'D'.code.toByte())
                        else -> null
                    }
                    if (bytes != null) {
                        onInput(bytes)
                        true
                    } else {
                        false
                    }
                },
            textStyle = androidx.compose.ui.text.TextStyle(
                color = Color.Transparent,
                fontSize = 1.sp,
            ),
            cursorBrush = SolidColor(Color.Transparent),
        )
    }
}

private data class Snapshot(
    val back: List<Array<Cell>>,
    val rows: List<Array<Cell>>,
    val cursorRow: Int,
    val cursorCol: Int,
)

/** One terminal row as colored text; the cursor cell renders reversed. */
@Composable
private fun TerminalLine(
    cells: Array<Cell>,
    cursorCol: Int,
    showCursor: Boolean,
    fontSizeSp: Float,
    lineHeight: androidx.compose.ui.unit.TextUnit,
    palette: TerminalPalette,
) {
    val annotated = remember(cells, cursorCol, showCursor, palette) {
        buildAnnotatedString {
            var col = 0
            var rendered = 0
            while (col < cells.size) {
                val cell = cells[col]
                if (cell.isContinuation()) {
                    col++
                    continue
                }
                val isCursor = showCursor && col == cursorCol
                val fg = if (isCursor) palette.cursorFg else palette.fg(cell.fg)
                val bg = if (isCursor) {
                    palette.cursorBg
                } else {
                    palette.bg(cell.bg)
                }
                withStyle(
                    SpanStyle(
                        color = fg,
                        background = bg,
                        fontWeight = if (cell.bold) FontWeight.Bold else FontWeight.Normal,
                    ),
                ) {
                    append(if (cell.ch == '\u0000') ' ' else cell.ch)
                }
                rendered++
                col++
            }
            // Cursor past end of line.
            if (showCursor && cursorCol >= rendered) {
                withStyle(SpanStyle(color = palette.cursorFg, background = palette.cursorBg)) {
                    append(' ')
                }
            }
            // Keep the line height stable for empty rows.
            if (rendered == 0 && !(showCursor && cursorCol >= 0)) {
                append(' ')
            }
        }
    }
    Text(
        text = annotated,
        fontSize = fontSizeSp.sp,
        lineHeight = lineHeight,
        fontFamily = FontFamily.Monospace,
        softWrap = false,
        maxLines = 1,
    )
}

private const val LINE_HEIGHT_FACTOR = 1.25f
private const val CELL_ADVANCE_FACTOR = 0.6f
private const val RESIZE_DEBOUNCE_MS = 150L
private const val SCROLL_STICK_PX = 48
private const val MAX_RENDER_SCROLLBACK = 300

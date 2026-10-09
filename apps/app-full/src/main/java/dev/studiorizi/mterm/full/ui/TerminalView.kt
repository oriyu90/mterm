package dev.studiorizi.mterm.full.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
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
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.studiorizi.mterm.core.terminal_emulator.Cell
import dev.studiorizi.mterm.core.terminal_emulator.TerminalEmulator
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** xterm 16-color palette on a dark terminal background. */
private val PALETTE = listOf(
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

private fun fgColor(index: Int): Color = PALETTE[index.coerceIn(0, 15)]
private fun bgColor(index: Int): Color =
    if (index == Cell.DEFAULT_BG) Color.Transparent else PALETTE[index.coerceIn(0, 15)]

/**
 * Terminal surface bound to a [TerminalEmulator].
 *
 * Rows render as [Text] lines with per-cell colors (robust: no Canvas text
 * drawing, which crashes on zero-size first frames). The block cursor is a
 * reversed-color cell. [tick] drives recomposition whenever PTY output
 * arrives.
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
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    var hasFocus by remember { mutableStateOf(false) }
    var imeBuffer by remember { mutableStateOf("") }
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
            .background(Color(0xFF1A1B26))
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
                    TerminalLine(cells, cursorCol = -1, showCursor = false, fontSizeSp = fontSizeSp, lineHeight = lineHeight)
                }
                snapshot.rows.forEachIndexed { index, cells ->
                    TerminalLine(
                        cells,
                        cursorCol = if (index == snapshot.cursorRow) snapshot.cursorCol else -1,
                        showCursor = hasFocus && index == snapshot.cursorRow,
                        fontSizeSp = fontSizeSp,
                        lineHeight = lineHeight,
                    )
                }
            }
        }
        // Invisible IME input: captures software-keyboard text. Its own cursor
        // is hidden (transparent) so only the terminal block cursor shows.
        // The buffer resets after every change so composition text never
        // accumulates (and never leaks into accessibility services); bytes are
        // forwarded to the PTY immediately. Cleared semantics keep typed
        // commands out of the accessibility tree (no secrets in TalkBack).
        BasicTextField(
            value = imeBuffer,
            onValueChange = { next ->
                if (next.length > imeBuffer.length) {
                    val inserted = next.substring(imeBuffer.length)
                    onInput(inserted.toByteArray(Charsets.UTF_8))
                } else if (next.length < imeBuffer.length) {
                    // Backspace via IME delete.
                    onInput(byteArrayOf(0x7F))
                }
                imeBuffer = ""
            },
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
) {
    val annotated = remember(cells, cursorCol, showCursor) {
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
                val fg = if (isCursor) Color(0xFF1A1B26) else fgColor(cell.fg)
                val bg = if (isCursor) {
                    Color(0xFFC0CAF5)
                } else {
                    bgColor(cell.bg)
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
                withStyle(SpanStyle(color = Color(0xFF1A1B26), background = Color(0xFFC0CAF5))) {
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

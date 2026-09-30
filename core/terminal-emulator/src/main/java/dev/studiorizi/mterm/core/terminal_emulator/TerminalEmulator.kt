/*
 * Copyright 2026 StudioRizi.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Clean-room minimal terminal emulator written for mterm. It implements only
 * the ANSI subset mterm needs (printable text, common controls, a small CSI
 * set, SGR colors, OSC skipping, bracketed-paste tracking) and is NOT copied
 * from Termux (GPL) sources. The licensing approach follows the Termux
 * exception concept (see https://github.com/termux/termux-app/blob/master/LICENSE.md):
 * terminal-emulation glue stays under Apache-2.0, GPL app code is never mixed in.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dev.studiorizi.mterm.core.terminal_emulator

/**
 * One screen cell. Wide (width-2) characters occupy two adjacent cells; the
 * right half is marked with [WIDE_CONTINUATION] and skipped when rendering.
 */
data class Cell(
    var ch: Char = ' ',
    var bold: Boolean = false,
    var fg: Int = DEFAULT_FG,
    var bg: Int = DEFAULT_BG,
) {
    fun clear() {
        ch = ' '
        bold = false
        fg = DEFAULT_FG
        bg = DEFAULT_BG
    }

    fun isContinuation(): Boolean = ch == WIDE_CONTINUATION

    companion object {
        const val DEFAULT_FG = 7
        const val DEFAULT_BG = 0

        /** Marker for the right half of a wide cell. Never rendered directly. */
        const val WIDE_CONTINUATION = '\u0000'
    }
}

/**
 * Minimal clean-room terminal emulator (pure Kotlin, no Android dependencies).
 *
 * Supported input: printable code points, `\n` (treated as CR+LF), `\r`,
 * `\b`, `\t`, `ESC[A/B/C/D` (cursor moves), `ESC[H` (home/CUP), `ESC[2J`
 * (clear screen), `ESC[K` (clear line), SGR `ESC[<n>m` (reset/bold/fg/bg),
 * alternate screen `ESC[?1047/1049h/l`, bracketed paste `ESC[?2004h/l`.
 * OSC sequences (including hyperlink `OSC 8`) are consumed and ignored.
 *
 * Malformed escapes, out-of-range parameters and invalid UTF-8 are ignored
 * safely: [write] never throws for bad content (bounds violations in
 * `off`/`len` are likewise ignored, not thrown).
 *
 * Threading: all public methods are synchronized; the PTY reader coroutine
 * may call [write]/[resize] while the UI thread calls [getLine]/[getCell].
 */
class TerminalEmulator(
    rows: Int,
    cols: Int,
    val scrollbackMax: Int = DEFAULT_SCROLLBACK_MAX,
) {
    var rows: Int = rows.coerceIn(MIN_ROWS, MAX_ROWS)
        private set
    var cols: Int = cols.coerceIn(MIN_COLS, MAX_COLS)
        private set

    var cursorRow: Int = 0
        private set
    var cursorCol: Int = 0
        private set

    /** True while the alternate screen buffer is active. */
    var isAlternateScreen: Boolean = false
        private set

    /** Bracketed-paste mode requested by the application (`ESC[?2004h/l`). */
    var bracketedPasteMode: Boolean = false
        private set

    private var screen: Array<Array<Cell>> = freshBuffer(this.rows, this.cols)
    private var mainScreen: Array<Array<Cell>> = screen
    private var altScreen: Array<Array<Cell>> = freshBuffer(this.rows, this.cols)
    private val scrollback: ArrayDeque<Array<Cell>> = ArrayDeque()

    // Active SGR attributes applied to newly printed cells.
    private var curBold: Boolean = false
    private var curFg: Int = Cell.DEFAULT_FG
    private var curBg: Int = Cell.DEFAULT_BG

    // Saved cursor (ESC 7 / ESC 8 / CSI s / CSI u / alt-screen switches).
    private var savedRow: Int = 0
    private var savedCol: Int = 0
    private var altSavedRow: Int = 0
    private var altSavedCol: Int = 0

    // Pending-wrap flag: the cursor sits on the last column of a full line
    // and wraps only when the next printable character arrives.
    private var wrapPending: Boolean = false

    // Incremental parser state (PTY output may split sequences across writes).
    private var state: Int = STATE_GROUND
    private val csiBuf: StringBuilder = StringBuilder()
    private var csiOverflow: Boolean = false
    private var pendingBytes: ByteArray = ByteArray(0)

    companion object {
        const val MIN_ROWS = 2
        const val MAX_ROWS = 500
        const val MIN_COLS = 2
        const val MAX_COLS = 500
        const val DEFAULT_SCROLLBACK_MAX = 10_000

        private const val STATE_GROUND = 0
        private const val STATE_ESC = 1
        private const val STATE_ESC_SKIP = 2 // charset introducer; consume one byte
        private const val STATE_CSI = 3
        private const val STATE_OSC = 4
        private const val STATE_OSC_ESC = 5 // ESC seen inside OSC (ST terminator?)

        private const val MAX_CSI_LEN = 64
        private const val REPLACEMENT = '\uFFFD'
    }

    // ------------------------------------------------------------------ I/O

    /**
     * Feed PTY output bytes into the emulator. Bounds violations in [off]/[len]
     * are ignored silently (no throw), as is any malformed escape content.
     */
    @Synchronized
    fun write(data: ByteArray, off: Int = 0, len: Int = data.size - off) {
        if (off < 0 || len < 0 || off > data.size || data.size - off < len) return
        if (len == 0 && pendingBytes.isEmpty()) return
        val buf: ByteArray
        val total: Int
        if (pendingBytes.isEmpty()) {
            buf = data
            total = off + len
            var i = off
            val end = off + len
            i = decodeLoop(buf, i, end)
            if (i < end) {
                pendingBytes = buf.copyOfRange(i, end)
            }
        } else {
            val combined = ByteArray(pendingBytes.size + len)
            pendingBytes.copyInto(combined)
            data.copyInto(combined, pendingBytes.size, off, off + len)
            pendingBytes = ByteArray(0)
            total = combined.size
            val i = decodeLoop(combined, 0, combined.size)
            if (i < total) {
                pendingBytes = combined.copyOfRange(i, total)
            }
        }
    }

    /**
     * Decode UTF-8 in `buf[i..end)` feeding complete code points to the parser.
     * Returns the index of the first unconsumed byte (start of a trailing
     * incomplete sequence, or `end`). Invalid bytes yield U+FFFD and resync.
     */
    private fun decodeLoop(buf: ByteArray, start: Int, end: Int): Int {
        var i = start
        while (i < end) {
            val b0 = buf[i].toInt() and 0xFF
            if (b0 < 0x80) {
                processCodePoint(b0)
                i++
                continue
            }
            val size: Int = when {
                b0 in 0xC2..0xDF -> 2
                b0 in 0xE0..0xEF -> 3
                b0 in 0xF0..0xF4 -> 4
                else -> {
                    // Stray continuation (0x80-0xBF), overlong leaders
                    // (0xC0/0xC1) or out-of-range leaders (0xF5-0xFF).
                    processCodePoint(REPLACEMENT.code)
                    i++
                    continue
                }
            }
            if (i + size > end) break // incomplete: keep for next write
            var valid = true
            for (k in 1 until size) {
                val b = buf[i + k].toInt() and 0xFF
                if (b !in 0x80..0xBF) {
                    valid = false
                    break
                }
            }
            if (!valid) {
                processCodePoint(REPLACEMENT.code)
                i++
                continue
            }
            val cp: Int = when (size) {
                2 -> ((b0 and 0x1F) shl 6) or (buf[i + 1].toInt() and 0x3F)
                3 -> ((b0 and 0x0F) shl 12) or
                    ((buf[i + 1].toInt() and 0x3F) shl 6) or
                    (buf[i + 2].toInt() and 0x3F)
                else -> ((b0 and 0x07) shl 18) or
                    ((buf[i + 1].toInt() and 0x3F) shl 12) or
                    ((buf[i + 2].toInt() and 0x3F) shl 6) or
                    (buf[i + 3].toInt() and 0x3F)
            }
            // Reject overlongs, surrogates and out-of-range values.
            val ok = when (size) {
                2 -> cp >= 0x80
                3 -> cp >= 0x800 && cp !in 0xD800..0xDFFF
                else -> cp in 0x10000..0x10FFFF
            }
            processCodePoint(if (ok) cp else REPLACEMENT.code)
            i += size
        }
        return i
    }

    // --------------------------------------------------------------- parser

    private fun processCodePoint(cp: Int) {
        when (state) {
            STATE_GROUND -> processGround(cp)
            STATE_ESC -> processEsc(cp)
            STATE_ESC_SKIP -> state = STATE_GROUND // swallow one charset byte
            STATE_CSI -> processCsi(cp)
            STATE_OSC -> {
                when (cp) {
                    0x07 -> state = STATE_GROUND // BEL terminates OSC
                    0x1B -> state = STATE_OSC_ESC
                    else -> { /* consume OSC content */ }
                }
            }
            STATE_OSC_ESC -> {
                if (cp == 0x5C) { // backslash: ST terminator
                    state = STATE_GROUND
                } else {
                    // Not ST: abort OSC and reinterpret as a fresh ESC sequence.
                    state = STATE_ESC
                    processEsc(cp)
                }
            }
            else -> state = STATE_GROUND
        }
    }

    private fun processGround(cp: Int) {
        when (cp) {
            0x1B -> state = STATE_ESC
            0x0A -> { // LF: CR+LF semantics used by PTY line discipline output
                wrapPending = false
                cursorCol = 0
                lineFeed()
            }
            0x0D -> { // CR
                wrapPending = false
                cursorCol = 0
            }
            0x08 -> { // BS
                wrapPending = false
                if (cursorCol > 0) cursorCol--
            }
            0x09 -> { // HT: next tab stop every 8 columns, no wrap
                wrapPending = false
                cursorCol = ((cursorCol / 8) + 1) * 8
                if (cursorCol >= cols) cursorCol = cols - 1
            }
            0x07 -> { /* BEL: no audible bell in minimal core */ }
            else -> {
                if (cp < 0x20 || (cp in 0x7F..0x9F)) return // other C0/C1: ignore
                putChar(cp)
            }
        }
    }

    private fun processEsc(cp: Int) {
        when (cp) {
            0x5B -> { // '[' CSI
                csiBuf.clear()
                csiOverflow = false
                state = STATE_CSI
            }
            0x5D -> state = STATE_OSC // ']' OSC
            0x28, 0x29, 0x2A, 0x2B, // charset selection: swallow next byte
            0x23, 0x25, 0x22 -> state = STATE_ESC_SKIP
            0x4D -> { // 'M' reverse index
                wrapPending = false
                if (cursorRow > 0) {
                    cursorRow--
                } else {
                    for (r in rows - 1 downTo 1) screen[r] = screen[r - 1]
                    screen[0] = freshLine(cols)
                }
                state = STATE_GROUND
            }
            0x44 -> { // 'D' index
                wrapPending = false
                lineFeed()
                state = STATE_GROUND
            }
            0x45 -> { // 'E' next line
                wrapPending = false
                cursorCol = 0
                lineFeed()
                state = STATE_GROUND
            }
            0x37 -> { // '7' save cursor
                savedRow = cursorRow
                savedCol = cursorCol
                state = STATE_GROUND
            }
            0x38 -> { // '8' restore cursor
                cursorRow = savedRow.coerceIn(0, rows - 1)
                cursorCol = savedCol.coerceIn(0, cols - 1)
                wrapPending = false
                state = STATE_GROUND
            }
            0x63 -> { // 'c' full reset
                reset()
                state = STATE_GROUND
            }
            0x3D, 0x3E, // keypad mode switches
            0x48, // 'H' tab set (we use fixed stops; ignore)
            0x5A, 0x6E, 0x6F -> state = STATE_GROUND // ignored single-byte sequences
            else -> state = STATE_GROUND // unknown: ignore safely
        }
    }

    private fun processCsi(cp: Int) {
        if (cp in 0x40..0x7E) { // final byte
            if (!csiOverflow) dispatchCsi(cp.toChar())
            csiBuf.clear()
            csiOverflow = false
            state = STATE_GROUND
            return
        }
        if (!csiOverflow) {
            if (csiBuf.length < MAX_CSI_LEN) {
                csiBuf.append(cp.toChar())
            } else {
                csiOverflow = true // overlong: consume until final, then drop
            }
        }
    }

    /** Split the CSI body into integer params, using [default] for missing parts. */
    private fun csiParams(body: String, default: Int): List<Int> {
        if (body.isEmpty()) return listOf(default)
        return body.split(';').map { part ->
            part.takeWhile { it.isDigit() }.toIntOrNull() ?: default
        }
    }

    private fun dispatchCsi(final: Char) {
        val raw = csiBuf.toString()
        val isPrivate = raw.startsWith("?")
        val body = if (isPrivate) raw.drop(1) else raw
        when (final) {
            'A' -> { // CUU
                val n = csiParams(body, 1).firstOrNull() ?: 1
                wrapPending = false
                cursorRow = (cursorRow - n.coerceAtLeast(0)).coerceAtLeast(0)
            }
            'B', 'e' -> { // CUD / VPR
                val n = csiParams(body, 1).firstOrNull() ?: 1
                wrapPending = false
                cursorRow = (cursorRow + n.coerceAtLeast(0)).coerceAtMost(rows - 1)
            }
            'C', 'a' -> { // CUF / HPR
                val n = csiParams(body, 1).firstOrNull() ?: 1
                wrapPending = false
                cursorCol = (cursorCol + n.coerceAtLeast(0)).coerceAtMost(cols - 1)
            }
            'D' -> { // CUB
                val n = csiParams(body, 1).firstOrNull() ?: 1
                wrapPending = false
                cursorCol = (cursorCol - n.coerceAtLeast(0)).coerceAtLeast(0)
            }
            'E' -> { // CNL
                val n = csiParams(body, 1).firstOrNull() ?: 1
                wrapPending = false
                cursorRow = (cursorRow + n.coerceAtLeast(0)).coerceAtMost(rows - 1)
                cursorCol = 0
            }
            'F' -> { // CPL
                val n = csiParams(body, 1).firstOrNull() ?: 1
                wrapPending = false
                cursorRow = (cursorRow - n.coerceAtLeast(0)).coerceAtLeast(0)
                cursorCol = 0
            }
            'G', '`' -> { // CHA / HPA
                val n = csiParams(body, 1).firstOrNull() ?: 1
                wrapPending = false
                cursorCol = (n - 1).coerceIn(0, cols - 1)
            }
            'd' -> { // VPA
                val n = csiParams(body, 1).firstOrNull() ?: 1
                wrapPending = false
                cursorRow = (n - 1).coerceIn(0, rows - 1)
            }
            'H', 'f' -> { // CUP / HVP
                val p = csiParams(body, 1)
                val r = p.getOrElse(0) { 1 }
                val c = p.getOrElse(1) { 1 }
                wrapPending = false
                cursorRow = (r - 1).coerceIn(0, rows - 1)
                cursorCol = (c - 1).coerceIn(0, cols - 1)
            }
            'J' -> { // ED
                val n = csiParams(body, 0).firstOrNull() ?: 0
                wrapPending = false
                when (n) {
                    0 -> { // cursor to end of screen
                        clearLineFrom(cursorCol)
                        for (r in cursorRow + 1 until rows) clearLine(r)
                    }
                    1 -> { // start of screen to cursor
                        for (r in 0 until cursorRow) clearLine(r)
                        clearLineTo(cursorCol)
                    }
                    2 -> for (r in 0 until rows) clearLine(r)
                    3 -> {
                        for (r in 0 until rows) clearLine(r)
                        if (!isAlternateScreen) scrollback.clear()
                        cursorRow = 0
                        cursorCol = 0
                    }
                    else -> { /* ignore */ }
                }
            }
            'K' -> { // EL
                val n = csiParams(body, 0).firstOrNull() ?: 0
                wrapPending = false
                when (n) {
                    0 -> clearLineFrom(cursorCol)
                    1 -> clearLineTo(cursorCol)
                    2 -> clearLine(cursorRow)
                    else -> { /* ignore */ }
                }
            }
            'm' -> applySgr(csiParams(body, 0))
            'h', 'l' -> {
                if (isPrivate) {
                    val set = final == 'h'
                    for (p in csiParams(body, -1)) {
                        when (p) {
                            2004 -> bracketedPasteMode = set
                            1047, 1049 -> setAlternate(set)
                            else -> { /* ignore other private modes */ }
                        }
                    }
                }
                // non-private h/l (IRM etc.) are ignored.
            }
            's' -> { // save cursor
                savedRow = cursorRow
                savedCol = cursorCol
            }
            'u' -> { // restore cursor
                cursorRow = savedRow.coerceIn(0, rows - 1)
                cursorCol = savedCol.coerceIn(0, cols - 1)
                wrapPending = false
            }
            else -> { /* S/T/X/L/M/P/@/Z/q/t/r and the rest: ignored safely */ }
        }
    }

    private fun applySgr(params: List<Int>) {
        if (params.isEmpty()) {
            resetAttrs()
            return
        }
        for (p in params) {
            when (p) {
                0 -> resetAttrs()
                1 -> curBold = true
                2, 22 -> curBold = false // faint not tracked; clears bold
                30, 31, 32, 33, 34, 35, 36, 37 -> curFg = p - 30
                39 -> curFg = Cell.DEFAULT_FG
                90, 91, 92, 93, 94, 95, 96, 97 -> curFg = (p - 90) + 8
                40, 41, 42, 43, 44, 45, 46, 47 -> curBg = p - 40
                49 -> curBg = Cell.DEFAULT_BG
                100, 101, 102, 103, 104, 105, 106, 107 -> curBg = (p - 100) + 8
                // 38/48 extended colors arrive as split params (e.g. 38;5;196);
                // the color triplets are not tracked by this minimal core.
                else -> { /* underline/italic/blink/etc.: ignored safely */ }
            }
        }
    }

    // ---------------------------------------------------------- screen ops

    private fun putChar(cp: Int) {
        var w = UnicodeWidth.width(cp)
        if (w <= 0) return // combining/zero-width: merges into previous cell
        if (w > 2) w = 1
        // Cells hold a UTF-16 unit; supplementary chars use the replacement char.
        val ch: Char = if (cp <= 0xFFFF) cp.toChar() else REPLACEMENT
        if (wrapPending) {
            lineFeed()
            cursorCol = 0
            wrapPending = false
        }
        if (w == 2 && cursorCol == cols - 1) {
            // No room for a wide char on this line: wrap first.
            lineFeed()
            cursorCol = 0
        }
        if (cursorCol >= cols) {
            lineFeed()
            cursorCol = 0
        }
        val line = screen[cursorRow]
        // Cursor on the trailing half of a wide char: clear its orphaned base.
        if (line[cursorCol].isContinuation() && cursorCol > 0) {
            line[cursorCol - 1].clear()
        }
        line[cursorCol].ch = ch
        line[cursorCol].bold = curBold
        line[cursorCol].fg = curFg
        line[cursorCol].bg = curBg
        if (w == 2) {
            line[cursorCol + 1].ch = Cell.WIDE_CONTINUATION
            line[cursorCol + 1].bold = curBold
            line[cursorCol + 1].fg = curFg
            line[cursorCol + 1].bg = curBg
        }
        // A destroyed wide base orphans its trailing half; clear it.
        val after = cursorCol + w
        if (after < cols && line[after].isContinuation()) {
            line[after].clear()
        }
        cursorCol += w
        if (cursorCol >= cols) {
            cursorCol = cols - 1
            wrapPending = true
        }
    }

    private fun lineFeed() {
        if (cursorRow + 1 < rows) {
            cursorRow++
        } else {
            scrollUp()
        }
    }

    private fun scrollUp() {
        val top = screen[0]
        if (!isAlternateScreen && scrollbackMax > 0) {
            scrollback.addLast(top)
            while (scrollback.size > scrollbackMax) scrollback.removeFirst()
        }
        for (r in 0 until rows - 1) screen[r] = screen[r + 1]
        screen[rows - 1] = freshLine(cols)
    }

    private fun clearLine(r: Int) {
        if (r !in 0 until rows) return
        for (c in 0 until cols) screen[r][c].clear()
    }

    private fun clearLineFrom(col: Int) {
        if (cursorRow !in 0 until rows) return
        for (c in col.coerceAtLeast(0) until cols) screen[cursorRow][c].clear()
    }

    private fun clearLineTo(col: Int) {
        if (cursorRow !in 0 until rows) return
        for (c in 0..col.coerceAtMost(cols - 1)) screen[cursorRow][c].clear()
    }

    private fun setAlternate(active: Boolean) {
        if (active == isAlternateScreen) return
        if (active) {
            mainScreen = screen
            screen = altScreen
            // Remember the main-screen cursor for the switch back.
            altSavedRow = cursorRow
            altSavedCol = cursorCol
            cursorRow = 0
            cursorCol = 0
        } else {
            altScreen = screen
            screen = mainScreen
            cursorRow = altSavedRow.coerceIn(0, rows - 1)
            cursorCol = altSavedCol.coerceIn(0, cols - 1)
        }
        isAlternateScreen = active
        wrapPending = false
    }

    private fun resetAttrs() {
        curBold = false
        curFg = Cell.DEFAULT_FG
        curBg = Cell.DEFAULT_BG
    }

    private fun reset() {
        isAlternateScreen = false
        screen = mainScreen
        for (r in 0 until rows) clearLine(r)
        // Full reset (ESC c) leaves scrollback alone; ED 3 clears it.
        cursorRow = 0
        cursorCol = 0
        wrapPending = false
        resetAttrs()
        bracketedPasteMode = false
        state = STATE_GROUND
        csiBuf.clear()
        csiOverflow = false
        pendingBytes = ByteArray(0)
    }

    // ------------------------------------------------------------------ API

    /**
     * Resize the screen, preserving the top-left overlapping content.
     * Dimensions are clamped to 2..500. Debouncing is the caller's job.
     */
    @Synchronized
    fun resize(newRows: Int, newCols: Int) {
        val r = newRows.coerceIn(MIN_ROWS, MAX_ROWS)
        val c = newCols.coerceIn(MIN_COLS, MAX_COLS)
        if (r == rows && c == cols) return
        mainScreen = copyBuffer(mainScreen, rows, cols, r, c)
        altScreen = copyBuffer(altScreen, rows, cols, r, c)
        screen = if (isAlternateScreen) altScreen else mainScreen
        rows = r
        cols = c
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, cols - 1)
        savedRow = savedRow.coerceIn(0, rows - 1)
        savedCol = savedCol.coerceIn(0, cols - 1)
        altSavedRow = altSavedRow.coerceIn(0, rows - 1)
        altSavedCol = altSavedCol.coerceIn(0, cols - 1)
        wrapPending = false
    }

    /**
     * Visible line [r] as a string (wide-continuation markers skipped,
     * trailing spaces trimmed). Out of range returns "" (no throw).
     */
    @Synchronized
    fun getLine(r: Int): String {
        if (r !in 0 until rows) return ""
        return renderLine(screen[r])
    }

    /** Scrollback line by age index (0 = oldest). Out of range returns "". */
    @Synchronized
    fun getScrollbackLine(index: Int): String {
        if (index !in 0 until scrollback.size) return ""
        return renderLine(scrollback[index])
    }

    /** Number of lines currently held in scrollback. */
    @Synchronized
    fun scrollbackSize(): Int = scrollback.size

    /** Copy of the cell at ([row], [col]), or null when out of range. */
    @Synchronized
    fun getCell(row: Int, col: Int): Cell? {
        if (row !in 0 until rows || col !in 0 until cols) return null
        return screen[row][col].copy()
    }

    private fun renderLine(line: Array<Cell>): String {
        val sb = StringBuilder(line.size)
        for (cell in line) {
            if (!cell.isContinuation()) sb.append(cell.ch)
        }
        var end = sb.length
        while (end > 0 && sb[end - 1] == ' ') end--
        return sb.substring(0, end)
    }

    private fun copyBuffer(
        old: Array<Array<Cell>>,
        oldRows: Int,
        oldCols: Int,
        newRows: Int,
        newCols: Int,
    ): Array<Array<Cell>> {
        // Copies the top-left overlapping region; both buffers are resized
        // independently so no active-view handling is needed here.
        val fresh = freshBuffer(newRows, newCols)
        val keepRows = minOf(oldRows, newRows)
        val keepCols = minOf(oldCols, newCols)
        for (r in 0 until keepRows) {
            val src = if (r < old.size) old[r] else null ?: continue
            for (c in 0 until keepCols) {
                if (c >= src.size) break
                val ch = src[c].copy()
                // Never leave a dangling trailing half at column 0 or at the
                // truncated right edge.
                if (ch.isContinuation() && (c == 0 || c == keepCols - 1 && keepCols < oldCols)) {
                    ch.clear()
                }
                fresh[r][c] = ch
            }
            // A truncated wide base at the right edge renders as its base char;
            // its (lost) trailing half needs no marker. Nothing to do.
        }
        return fresh
    }
}

private fun freshBuffer(rows: Int, cols: Int): Array<Array<Cell>> =
    Array(rows) { freshLine(cols) }

private fun freshLine(cols: Int): Array<Cell> =
    Array(cols) { Cell() }

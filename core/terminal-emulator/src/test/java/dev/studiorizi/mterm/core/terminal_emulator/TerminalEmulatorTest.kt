/*
 * Copyright 2026 StudioRizi.
 * SPDX-License-Identifier: Apache-2.0
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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalEmulatorTest {

    private fun String.bytes(): ByteArray = toByteArray(Charsets.UTF_8)

    @Test
    fun `printable characters appear on current line`() {
        val t = TerminalEmulator(24, 80)
        t.write("hello".bytes())
        assertEquals("hello", t.getLine(0))
        assertEquals(0, t.cursorRow)
        assertEquals(5, t.cursorCol)
    }

    @Test
    fun `newline moves to next line`() {
        val t = TerminalEmulator(24, 80)
        t.write("abc\ndef".bytes())
        assertEquals("abc", t.getLine(0))
        assertEquals("def", t.getLine(1))
        assertEquals(1, t.cursorRow)
    }

    @Test
    fun `carriage return backspace tab behave`() {
        val t = TerminalEmulator(24, 80)
        t.write("abcdef\rXY".bytes())
        assertEquals("XYcdef", t.getLine(0))
        t.write("\n".bytes())
        t.write("a\tb".bytes())
        // 'a' at col 0, tab jumps to col 8, 'b' at col 8.
        assertEquals(9, t.cursorCol)
        assertEquals("XYcdef", t.getLine(0))
    }

    @Test
    fun `SGR sequences produce no visible output but set attributes`() {
        val t = TerminalEmulator(24, 80)
        t.write("\u001B[1;31mAB\u001B[0mCD".bytes())
        assertEquals("ABCD", t.getLine(0))
        val bold = t.getCell(0, 0)
        assertNotNull(bold)
        assertTrue(bold!!.bold)
        assertEquals(1, bold.fg)
        val plain = t.getCell(0, 2)
        assertNotNull(plain)
        assertFalse(plain!!.bold)
        assertEquals(Cell.DEFAULT_FG, plain.fg)
    }

    @Test
    fun `bright SGR colors map to high palette`() {
        val t = TerminalEmulator(24, 80)
        t.write("\u001B[91;102mX".bytes())
        val cell = t.getCell(0, 0)
        assertNotNull(cell)
        assertEquals(9, cell!!.fg)
        assertEquals(10, cell.bg)
    }

    @Test
    fun `cursor movement and home work`() {
        val t = TerminalEmulator(24, 80)
        t.write("ABCDEFGHIJ".bytes())
        t.write("\u001B[1;1H".bytes())
        t.write("Z".bytes())
        assertEquals("ZBCDEFGHIJ", t.getLine(0))
        t.write("\u001B[5C".bytes())
        assertEquals(6, t.cursorCol)
        t.write("\u001B[2D".bytes())
        assertEquals(4, t.cursorCol)
        t.write("\u001B[3B".bytes())
        assertEquals(3, t.cursorRow)
        t.write("\u001B[2A".bytes())
        assertEquals(1, t.cursorRow)
    }

    @Test
    fun `clear screen and clear line work`() {
        val t = TerminalEmulator(24, 80)
        t.write("hello".bytes())
        t.write("\u001B[2J\u001B[H".bytes())
        assertEquals("", t.getLine(0))
        assertEquals(0, t.cursorRow)
        assertEquals(0, t.cursorCol)
        t.write("hello".bytes())
        t.write("\r\u001B[K".bytes())
        assertEquals("", t.getLine(0))
    }

    @Test
    fun `malformed escapes never throw and emulator keeps working`() {
        val t = TerminalEmulator(24, 80)
        val cases = listOf(
            byteArrayOf(0x1B), // lone ESC
            "\u001B[".bytes(), // truncated CSI
            "\u001B[99999999999999999999999m".bytes(), // huge param
            "\u001B]8;;https://example.com\u0007after".bytes(), // BEL-terminated OSC
            "\u001B]8;;https://example.com\u001B\\after".bytes(), // ST-terminated OSC
            "\u001B[?2004".bytes(), // truncated private mode
            byteArrayOf(0xFF.toByte(), 0xFE.toByte(), 0x80.toByte()), // invalid UTF-8
            byteArrayOf(0xC0.toByte(), 0xAF.toByte()), // overlong encoding
            "\u001B(B\u001B)0".bytes(), // charset selection
            "\u001B[38;5;999mX".bytes(), // extended color garbage
            "\u001B[200;100H".bytes(), // out-of-range CUP (clamped, no crash)
            "\u001B[?9999h".bytes(), // unknown private mode
            "\u001B[q\u001B[9999Z".bytes(), // unknown finals
            "\u001B".bytes() + "X".bytes(), // ESC followed by plain char
        )
        for (c in cases) {
            t.write(c)
        }
        // An unterminated OSC swallows following output (correct behavior);
        // the parser must recover once the terminator arrives in a later write.
        t.write("\u001B]8;;https://example.com".bytes())
        t.write("swallowed".bytes())
        t.write("\u0007".bytes())
        // Cursor may have wandered (e.g. clamped CUP); home it, then verify.
        t.write("\u001B[Hok".bytes())
        assertTrue(t.getLine(0).contains("ok"))
    }

    @Test
    fun `split multibyte sequence across writes renders correctly`() {
        val t = TerminalEmulator(24, 80)
        val bytes = "あ".bytes()
        assertTrue(bytes.size > 1)
        t.write(bytes.copyOfRange(0, 1))
        t.write(bytes.copyOfRange(1, bytes.size))
        assertEquals("あ", t.getLine(0))
        assertEquals(2, t.cursorCol)
    }

    @Test
    fun `wide CJK char occupies two columns`() {
        assertEquals(2, UnicodeWidth.width("中".codePointAt(0)))
        assertEquals(1, UnicodeWidth.width('A'.code))
        assertEquals(0, UnicodeWidth.width(0x0301)) // combining acute
        val t = TerminalEmulator(24, 10)
        t.write("あいう".bytes())
        assertEquals(6, t.cursorCol)
        assertEquals("あいう", t.getLine(0))
    }

    @Test
    fun `combining mark does not advance cursor`() {
        val t = TerminalEmulator(24, 80)
        t.write("é".bytes())
        assertEquals(1, t.cursorCol)
        assertEquals("e", t.getLine(0))
    }

    @Test
    fun `fullwidth latin from IME fullwidth mode round-trips`() {
        // Japanese IMEs commit ASCII as fullwidth (U+FF41-FF5A) in fullwidth
        // alphanumeric mode; the emulator must store them verbatim
        // (2 columns each) rather than corrupting the line.
        val t = TerminalEmulator(24, 80)
        t.write("\uFF48\uFF45\uFF4C\uFF4C\uFF4F".bytes())
        assertEquals("\uFF48\uFF45\uFF4C\uFF4C\uFF4F", t.getLine(0))
        assertEquals(10, t.cursorCol)
    }

    @Test
    fun `resize clamps and preserves content`() {
        val t = TerminalEmulator(24, 80)
        t.write("hello".bytes())
        t.resize(1, 100000)
        assertEquals(2, t.rows)
        assertEquals(500, t.cols)
        assertEquals("hello", t.getLine(0))
        t.resize(600, 1)
        assertEquals(500, t.rows)
        assertEquals(2, t.cols)
        t.resize(24, 80)
        assertEquals(24, t.rows)
        assertEquals(80, t.cols)
    }

    @Test
    fun `scrollback is bounded`() {
        val t = TerminalEmulator(4, 20, 5)
        for (i in 0 until 30) {
            t.write("line $i\n".bytes())
        }
        assertEquals(5, t.scrollbackSize())
    }

    @Test
    fun `bracketed paste mode toggles`() {
        val t = TerminalEmulator(24, 80)
        assertFalse(t.bracketedPasteMode)
        t.write("\u001B[?2004h".bytes())
        assertTrue(t.bracketedPasteMode)
        t.write("\u001B[?2004l".bytes())
        assertFalse(t.bracketedPasteMode)
    }

    @Test
    fun `alternate screen isolates scrollback`() {
        val t = TerminalEmulator(4, 20, 100)
        t.write("main\n".bytes())
        t.write("\u001B[?1049h".bytes())
        assertTrue(t.isAlternateScreen)
        for (i in 0 until 10) {
            t.write("alt $i\n".bytes())
        }
        assertEquals(0, t.scrollbackSize())
        t.write("\u001B[?1049l".bytes())
        assertFalse(t.isAlternateScreen)
        assertEquals("main", t.getLine(0))
    }

    @Test
    fun `write validates bounds without throwing`() {
        val t = TerminalEmulator(24, 80)
        val data = "hello".bytes()
        t.write(data, -1, 3)
        t.write(data, 0, 100)
        t.write(data, 4, 5)
        t.write(data, 0, -1)
        t.write(data, 0, 0)
        assertEquals("", t.getLine(0))
        t.write("hi".bytes())
        assertEquals("hi", t.getLine(0))
    }

    @Test
    fun `out of range reads return empty without throwing`() {
        val t = TerminalEmulator(2, 2)
        assertEquals("", t.getLine(-1))
        assertEquals("", t.getLine(99))
        assertEquals("", t.getScrollbackLine(0))
        assertEquals(null, t.getCell(5, 5))
    }

    @Test
    fun `row and scrollback cell snapshots carry colors`() {
        val t = TerminalEmulator(4, 20, 100)
        t.write("\u001B[31mred\u001B[0m\n".bytes())
        val cells = t.getRowCells(0)!!
        assertEquals('r'.code.toChar(), cells[0].ch)
        assertEquals(1, cells[0].fg)
        assertEquals(null, t.getRowCells(99))
        // Scroll the line into scrollback, then read it back with colors.
        repeat(4) { t.write("fill $it\n".bytes()) }
        assertTrue(t.scrollbackSize() > 0)
        val back = t.getScrollbackCells(0)!!
        assertTrue(back.any { it.ch == 'r' && it.fg == 1 })
        assertEquals(null, t.getScrollbackCells(9999))
    }
}

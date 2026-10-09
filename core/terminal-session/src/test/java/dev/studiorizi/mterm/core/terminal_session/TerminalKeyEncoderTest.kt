package dev.studiorizi.mterm.core.terminal_session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TerminalKeyEncoderTest {

    @Test
    fun `arrows encode as CSI sequences`() {
        assertArrayEquals(byteArrayOf(0x1B, '['.code.toByte(), 'A'.code.toByte()), TerminalKeyEncoder.token("UP"))
        assertArrayEquals(byteArrayOf(0x1B, '['.code.toByte(), 'B'.code.toByte()), TerminalKeyEncoder.token("DOWN"))
        assertArrayEquals(byteArrayOf(0x1B, '['.code.toByte(), 'C'.code.toByte()), TerminalKeyEncoder.token("RIGHT"))
        assertArrayEquals(byteArrayOf(0x1B, '['.code.toByte(), 'D'.code.toByte()), TerminalKeyEncoder.token("LEFT"))
    }

    @Test
    fun `modifiers return null (latched by UI)`() {
        assertNull(TerminalKeyEncoder.token("CTRL"))
        assertNull(TerminalKeyEncoder.token("ALT"))
        assertNull(TerminalKeyEncoder.token("NOPE"))
    }

    @Test
    fun `control maps letters to C0 codes`() {
        assertArrayEquals(byteArrayOf(0x03), TerminalKeyEncoder.control('C'))
        assertArrayEquals(byteArrayOf(0x03), TerminalKeyEncoder.control('c'))
        assertArrayEquals(byteArrayOf(0x1B), TerminalKeyEncoder.control('['))
    }

    @Test
    fun `parseList splits pipes and trims`() {
        assertEquals(
            listOf("ESC", "TAB", "CTRL"),
            TerminalKeyEncoder.parseList("ESC| TAB |CTRL|"),
        )
    }
}

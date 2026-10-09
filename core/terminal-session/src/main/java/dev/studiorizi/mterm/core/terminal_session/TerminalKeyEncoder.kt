/*
 * Copyright 2026 StudioRizi. SPDX-License-Identifier: Apache-2.0
 *
 * Key token -> terminal byte sequence mapping for the extra-keys row and
 * hardware keyboard. Pure Kotlin so it is unit-testable without Android.
 */
package dev.studiorizi.mterm.core.terminal_session

object TerminalKeyEncoder {

    /** Applies the Ctrl modifier to a Latin letter (Ctrl+A = 0x01 ... Ctrl+Z = 0x1A). */
    fun control(ch: Char): ByteArray? {
        val upper = ch.uppercaseChar()
        if (upper in 'A'..'Z') return byteArrayOf((upper.code - 'A'.code + 1).toByte())
        return when (ch) {
            ' ', '@', '2' -> byteArrayOf(0x00)
            '[' -> byteArrayOf(0x1B)
            '\\' -> byteArrayOf(0x1C)
            ']' -> byteArrayOf(0x1D)
            '^', '6' -> byteArrayOf(0x1E)
            '_', '-' -> byteArrayOf(0x1F)
            '?' -> byteArrayOf(0x7F)
            else -> null
        }
    }

    fun tokenEscape(vararg tail: Char): ByteArray =
        byteArrayOf(0x1B) + tail.map { it.code.toByte() }.toByteArray()

    /** The 16 ANSI/xterm key token names understood by [token]. */
    val KNOWN_TOKENS: Set<String> = setOf(
        "ESC", "TAB", "CTRL", "ALT", "ENTER", "BACKSPACE",
        "LEFT", "RIGHT", "UP", "DOWN", "HOME", "END", "PGUP", "PGDN",
    )

    /**
     * Returns the byte sequence for a named key token, or null for modifiers
     * (CTRL/ALT, which are handled as latches by the UI) or unknown tokens.
     */
    fun token(name: String): ByteArray? = when (name.trim().uppercase()) {
        "ESC" -> byteArrayOf(0x1B)
        "TAB" -> byteArrayOf(0x09)
        "ENTER", "RETURN", "CR" -> byteArrayOf(0x0D)
        "BACKSPACE", "BS", "DEL" -> byteArrayOf(0x7F)
        "LEFT" -> tokenEscape('[', 'D')
        "RIGHT" -> tokenEscape('[', 'C')
        "UP" -> tokenEscape('[', 'A')
        "DOWN" -> tokenEscape('[', 'B')
        "HOME" -> tokenEscape('[', 'H')
        "END" -> tokenEscape('[', 'F')
        "PGUP" -> tokenEscape('[', '5', '~')
        "PGDN" -> tokenEscape('[', '6', '~')
        else -> null
    }

    fun parseList(value: String): List<String> =
        value.split('|').map { it.trim() }.filter { it.isNotEmpty() }
}

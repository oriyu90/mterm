/*
 * Copyright 2026 StudioRizi.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Clean-room minimal width table written for mterm. It is NOT copied from
 * Termux (GPL) sources. The licensing approach follows the Termux exception
 * concept (see https://github.com/termux/termux-app/blob/master/LICENSE.md):
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
 * Minimal East-Asian-width helper for the terminal emulator.
 *
 * Returns the number of monospace columns a code point occupies:
 * - 0 for combining marks and other zero-width format characters
 *   (ZWJ, variation selectors, etc.),
 * - 2 for Wide/Fullwidth CJK ranges and emoji ranges,
 * - 1 for everything else.
 */
object UnicodeWidth {
    fun width(codePoint: Int): Int {
        if (codePoint < 0 || codePoint > 0x10FFFF) return 1
        // C0/C1 controls and DEL never advance the cursor.
        if (codePoint < 0x20 || codePoint in 0x7F..0x9F) return 0
        // Spacing combining marks that must merge into the previous cell.
        val type = Character.getType(codePoint)
        if (type == Character.NON_SPACING_MARK.toInt() ||
            type == Character.ENCLOSING_MARK.toInt()
        ) {
            return 0
        }
        when (codePoint) {
            0x200B, // ZERO WIDTH SPACE
            0x200C, // ZERO WIDTH NON-JOINER
            0x200D, // ZERO WIDTH JOINER
            0x200E, // LEFT-TO-RIGHT MARK
            0x200F, // RIGHT-TO-LEFT MARK
            0x2060, // WORD JOINER
            0xFEFF, // ZERO WIDTH NO-BREAK SPACE
            -> return 0
        }
        if (codePoint in 0xFE00..0xFE0F) return 0 // variation selectors
        if (codePoint in 0xE0100..0xE01FF) return 0 // variation selectors supplement
        if (isWide(codePoint)) return 2
        return 1
    }

    private fun isWide(cp: Int): Boolean = when {
        cp in 0x1100..0x115F -> true // Hangul Jamo
        cp == 0x2329 || cp == 0x232A -> true
        cp in 0x2E80..0x303E -> true // CJK radicals / Kangxi
        cp in 0x3041..0x33FF -> true // Hiragana, Katakana, Bopomofo, Hangul compat, CJK compat
        cp in 0x3400..0x4DBF -> true // CJK Extension A
        cp in 0x4E00..0xA4CF -> true // CJK Unified + Yi
        cp in 0xA960..0xA97C -> true // Hangul Extended-A
        cp in 0xAC00..0xD7A3 -> true // Hangul Syllables
        cp in 0xF900..0xFAFF -> true // CJK Compatibility Ideographs
        cp in 0xFE10..0xFE19 -> true // vertical forms
        cp in 0xFE30..0xFE4F -> true // CJK Compatibility Forms
        cp in 0xFF00..0xFF60 -> true // Fullwidth ASCII / Katakana
        cp in 0xFFE0..0xFFE6 -> true // Fullwidth symbols
        cp in 0x20000..0x2FFFD -> true // CJK Extensions B-F
        cp in 0x30000..0x3FFFD -> true // CJK Extensions G+
        // Emoji: full blocks plus characters with Emoji_Presentation defaults.
        cp in 0x1F000..0x1FAFF -> true
        cp == 0x231A || cp == 0x231B -> true
        cp in 0x23E9..0x23F3 -> true
        cp in 0x23F8..0x23FA -> true
        cp == 0x25FD || cp == 0x25FE -> true
        cp == 0x2614 || cp == 0x2615 -> true
        cp in 0x2648..0x2653 -> true
        cp == 0x267F || cp == 0x2693 || cp == 0x26A1 -> true
        cp == 0x26AA || cp == 0x26AB -> true
        cp in 0x26BD..0x26BE -> true
        cp == 0x26C5 || cp == 0x26CE -> true
        cp in 0x26D4..0x26D5 -> true
        cp == 0x26EA || cp == 0x26F2 || cp == 0x26F3 -> true
        cp == 0x26F5 || cp == 0x26FA || cp == 0x26FD -> true
        cp == 0x2705 || cp == 0x270A || cp == 0x270B -> true
        cp == 0x2728 || cp == 0x274C || cp == 0x274E -> true
        cp in 0x2753..0x2755 -> true
        cp == 0x2757 || cp == 0x2795 || cp == 0x2796 -> true
        cp == 0x2797 || cp == 0x27B0 || cp == 0x27BF -> true
        cp in 0x2B1B..0x2B1C -> true
        cp == 0x2B50 || cp == 0x2B55 -> true
        cp == 0x3030 || cp == 0x303D -> true
        cp == 0x3297 || cp == 0x3299 -> true
        else -> false
    }
}

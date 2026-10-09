package dev.studiorizi.mterm.full.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * AppTheme parsing never breaks launch: unknown, empty, and null stored
 * values fall back to SYSTEM (which follows the OS setting).
 */
class AppThemeTest {

    @Test
    fun `known keys parse`() {
        assertEquals(AppTheme.SYSTEM, AppTheme.of("system"))
        assertEquals(AppTheme.LIGHT, AppTheme.of("light"))
        assertEquals(AppTheme.DARK, AppTheme.of("dark"))
        assertEquals(AppTheme.RETRO, AppTheme.of("retro"))
    }

    @Test
    fun `unknown keys fall back to system`() {
        assertEquals(AppTheme.SYSTEM, AppTheme.of("amoled"))
        assertEquals(AppTheme.SYSTEM, AppTheme.of(""))
        assertEquals(AppTheme.SYSTEM, AppTheme.of(null))
        assertEquals(AppTheme.SYSTEM, AppTheme.of("DARK"))
    }

    @Test
    fun `terminal palettes have sixteen colors each`() {
        for (palette in listOf(DarkTerminalPalette, LightTerminalPalette, RetroTerminalPalette)) {
            assertEquals(16, palette.colors.size)
        }
    }

    @Test
    fun `palette lookup clamps instead of throwing`() {
        val palette = RetroTerminalPalette
        assertEquals(palette.colors[0], palette.fg(-99))
        assertEquals(palette.colors[15], palette.fg(99))
    }
}

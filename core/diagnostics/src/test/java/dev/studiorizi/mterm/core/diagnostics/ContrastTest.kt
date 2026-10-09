package dev.studiorizi.mterm.core.diagnostics

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contrast gate for every theme's body-text combinations (hallmark rubric:
 * body ≥ 4.5:1, boundaries ≥ 3:1, verified in both light and dark).
 * Intentional exception: retro disabled gray-on-gray carries a shape cue
 * (sunken border + label) and stays out of this gate.
 */
class ContrastTest {

    private fun ratio(fg: Long, bg: Long) = Contrast.ratio(fg.toInt(), bg.toInt())

    @Test
    fun `material light and dark body text pass`() {
        // M3 baseline on-surface pairs.
        assertTrue(ratio(0xFF1D1B20, 0xFFFFFBFF) >= 4.5) // light
        assertTrue(ratio(0xFFE6E0E9, 0xFF141218) >= 4.5) // dark
    }

    @Test
    fun `retro body text passes`() {
        assertTrue(ratio(0xFF000000, 0xFFC0C0C0) >= 4.5) // black on silver panel
        assertTrue(ratio(0xFFFFFFFF, 0xFF000080) >= 4.5) // white on navy title
        assertTrue(ratio(0xFF000000, 0xFFFFFFFF) >= 4.5) // black on white field
        assertTrue(ratio(0xFFFFFFFF, 0xFF008080) >= 4.5) // white on teal desktop
    }

    @Test
    fun `terminal text passes in all palettes`() {
        assertTrue(ratio(0xFFC0CAF5, 0xFF1A1B26) >= 4.5) // dark terminal
        assertTrue(ratio(0xFF1A1B26, 0xFFF5F5F0) >= 4.5) // light terminal
        assertTrue(ratio(0xFFC0C0C0, 0xFF000000) >= 4.5) // retro terminal
    }

    @Test
    fun `identical colors give ratio one`() {
        val r = Contrast.ratio(0xFF808080.toInt(), 0xFF808080.toInt())
        assertTrue(r >= 1.0 && r < 1.000001)
    }
}

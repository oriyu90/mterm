package dev.studiorizi.mterm.core.terminal_emulator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalSearchTest {
    @Test
    fun `empty query matches nothing`() {
        assertTrue(TerminalSearch.searchLines(listOf("hello"), "").isEmpty())
    }

    @Test
    fun `case-insensitive by default`() {
        val hits = TerminalSearch.searchLines(listOf("Hello HELLO"), "hello")
        assertEquals(2, hits.size)
        assertEquals(SearchHit(0, 0, 5), hits[0])
        assertEquals(SearchHit(0, 6, 11), hits[1])
    }

    @Test
    fun `case-sensitive opt-in`() {
        val hits = TerminalSearch.searchLines(listOf("Hello hello"), "hello", ignoreCase = false)
        assertEquals(listOf(SearchHit(0, 6, 11)), hits)
    }

    @Test
    fun `multiple lines in order with unified index`() {
        val hits = TerminalSearch.searchLines(listOf("a x", "no", "x b x"), "x")
        assertEquals(
            listOf(SearchHit(0, 2, 3), SearchHit(2, 0, 1), SearchHit(2, 4, 5)),
            hits,
        )
    }

    @Test
    fun `maxHits caps results`() {
        val lines = List(10) { "x x x" }
        assertEquals(5, TerminalSearch.searchLines(lines, "x", maxHits = 5).size)
    }

    @Test
    fun `urls extracted and trailing punct trimmed`() {
        val spans = TerminalSearch.findUrls(
            listOf("see https://example.com/a., then http://b.org/x) end"),
        )
        assertEquals(2, spans.size)
        assertEquals("https://example.com/a", spans[0].url)
        assertEquals(UrlSpan(0, 4, 4 + "https://example.com/a".length, "https://example.com/a"), spans[0])
        assertEquals("http://b.org/x", spans[1].url)
    }

    @Test
    fun `non-url text yields no spans`() {
        assertTrue(TerminalSearch.findUrls(listOf("just text", "www.no-scheme.com")).isEmpty())
    }

    @Test
    fun `emulator snapshot covers scrollback then screen`() {
        val emu = TerminalEmulator(2, 20)
        emu.write("screen-one\nscreen-two".toByteArray())
        val lines = TerminalSearch.snapshotLines(emu)
        assertTrue(lines.contains("screen-one"))
        assertTrue(lines.contains("screen-two"))
        val hits = TerminalSearch.searchEmulator(emu, "screen")
        assertEquals(2, hits.size)
    }
}

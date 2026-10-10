package dev.studiorizi.mterm.core.terminal_emulator

/**
 * Unified line index for search results: `0..scrollbackSize-1` addresses
 * scrollback (0 = oldest), `scrollbackSize..scrollbackSize+rows-1` addresses
 * the visible screen top-to-bottom. Use [SearchLines.scrollbackSize] to tell
 * which part a hit belongs to.
 */
data class SearchHit(
    /** Unified line index (scrollback first, then screen). */
    val line: Int,
    /** Char offset of the match start within the rendered line text. */
    val startCol: Int,
    /** Char offset of the match end (exclusive) within the line text. */
    val endCol: Int,
)

/** A clickable URL span found in terminal output. */
data class UrlSpan(
    /** Unified line index (same addressing as [SearchHit]). */
    val line: Int,
    val startCol: Int,
    val endCol: Int,
    val url: String,
)

/**
 * Plain-text search and URL detection over rendered terminal lines.
 *
 * Pure functions (no emulator dependency) so the UI can feed any line list
 * and unit tests stay hermetic. [searchEmulator] is a thin adapter that
 * snapshots scrollback + screen in the documented order.
 */
object TerminalSearch {
    private val URL_PATTERN = Regex("""https?://[^\s"'<>`]+""")
    private val TRAILING_PUNCT = setOf('.', ',', ';', ':', '!', '?', ')', ']', '\'')

    /**
     * Finds all (possibly multiple per line) occurrences of [query].
     * Empty query returns empty (never "match everything"). Results are in
     * line order, capped at [maxHits].
     */
    fun searchLines(
        lines: List<String>,
        query: String,
        ignoreCase: Boolean = true,
        maxHits: Int = 1000,
    ): List<SearchHit> {
        if (query.isEmpty() || maxHits <= 0) return emptyList()
        val hits = ArrayList<SearchHit>(minOf(64, maxHits))
        for ((lineIdx, line) in lines.withIndex()) {
            var from = 0
            while (from <= line.length && hits.size < maxHits) {
                val found = line.indexOf(query, from, ignoreCase)
                if (found < 0) break
                hits += SearchHit(lineIdx, found, found + query.length)
                from = found + maxOf(1, query.length)
            }
            if (hits.size >= maxHits) break
        }
        return hits
    }

    /**
     * Extracts URL spans. Trailing sentence punctuation (`.,;:!?)]`) is
     * trimmed so `see https://a.b/.` links to `https://a.b/`. Capped at
     * [maxSpans], line order.
     */
    fun findUrls(lines: List<String>, maxSpans: Int = 500): List<UrlSpan> {
        if (maxSpans <= 0) return emptyList()
        val spans = ArrayList<UrlSpan>(minOf(32, maxSpans))
        for ((lineIdx, line) in lines.withIndex()) {
            for (m in URL_PATTERN.findAll(line)) {
                var end = m.range.last + 1
                while (end > m.range.first + 1 && line[end - 1] in TRAILING_PUNCT) end--
                spans += UrlSpan(lineIdx, m.range.first, end, line.substring(m.range.first, end))
                if (spans.size >= maxSpans) return spans
            }
        }
        return spans
    }

    /** Snapshot of an emulator's lines: scrollback oldest-first, then screen. */
    fun snapshotLines(emu: TerminalEmulator): List<String> {
        val sb = emu.scrollbackSize()
        val out = ArrayList<String>(sb + 64)
        for (i in 0 until sb) out += emu.getScrollbackLine(i)
        // Screen row count is not directly exposed; getRowCells returns null
        // past the end, so probe with a sane bound (rows are at most dozens).
        var r = 0
        while (r < 1024) {
            val cells = emu.getRowCells(r) ?: break
            out += buildString {
                for (c in cells) {
                    if (!c.isContinuation()) append(c.ch)
                }
            }.trimEnd()
            r++
        }
        return out
    }

    /** Convenience: snapshot + [searchLines]. */
    fun searchEmulator(
        emu: TerminalEmulator,
        query: String,
        ignoreCase: Boolean = true,
        maxHits: Int = 1000,
    ): List<SearchHit> = searchLines(snapshotLines(emu), query, ignoreCase, maxHits)

    /** Convenience: snapshot + [findUrls]. */
    fun urlsInEmulator(emu: TerminalEmulator, maxSpans: Int = 500): List<UrlSpan> =
        findUrls(snapshotLines(emu), maxSpans)
}

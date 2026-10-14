package codes.exterkamp.pocketshell

import com.termux.terminal.TerminalBuffer

object Links {
    private val URL = Regex("""(?:https?|ftp|file)://[^\s<>"'`│]+""")

    /**
     * The URL under [column] on [row], if any. [rows] is the text of each screen row and [joinsNext] says whether
     * a row continues onto the next one, so URLs that wrap are found whole.
     */
    fun at(rows: List<String>, joinsNext: List<Boolean>, row: Int, column: Int): String? {
        if (row !in rows.indices) return null
        var first = row
        while (first > 0 && joinsNext[first - 1]) first--
        var last = row
        while (last < rows.lastIndex && joinsNext[last]) last++

        val line = StringBuilder()
        var offset = 0
        for (i in first..last) {
            // Where rows join, drop the padding and pane borders between them so a split URL is contiguous.
            var text = rows[i]
            var lead = 0
            if (i > first) text.trimStart(::isBorder).let { lead = text.length - it.length; text = it }
            if (i < last) text = text.trimEnd(::isBorder)
            if (i == row) offset = line.length + (column - lead).coerceAtLeast(0)
            line.append(text)
        }

        val matches = URL.findAll(line).map { it.range.first to trimTrailing(it.value) }.toList()
        return matches.firstOrNull { (start, url) -> offset in start until start + url.length }?.second
    }

    /** Punctuation that ends a sentence rather than the URL; a closing paren stays if the URL opened one. */
    private fun trimTrailing(url: String): String {
        var end = url.length
        while (end > 0) {
            val c = url[end - 1]
            val unbalancedParen = c == ')' && url.take(end).count { it == '(' } < url.take(end).count { it == ')' }
            if (c in ".,;:!?'\"]>" || unbalancedParen) end-- else break
        }
        return url.take(end)
    }

    /** Pane borders that multiplexers draw around text; never part of a URL. */
    private fun isBorder(c: Char) = c in '\u2500'..'\u257F' || c.isWhitespace()

    /**
     * Whether a row continues onto the next. Besides real soft wraps, a row whose text runs to (nearly) the
     * last column counts, because full-screen programs (herdr, tmux, Claude Code) draw long lines as separate
     * rows without marking them wrapped, often a column or two short of the edge inside a pane.
     */
    fun continues(text: String, next: String?, columns: Int, softWrapped: Boolean): Boolean {
        if (softWrapped) return true
        if (next == null) return false
        val core = text.trimEnd(::isBorder)
        // After a left pane border, allow its one space of padding; otherwise an indented row is a new line.
        var nextCore = next.trimStart { it in '\u2500'..'\u257F' }
        if (nextCore.length < next.length && nextCore.startsWith(" ")) nextCore = nextCore.drop(1)
        return core.length >= columns - 3 && nextCore.isNotEmpty() && !nextCore[0].isWhitespace()
    }

    /** The URL at a cell of a live terminal. */
    fun at(screen: TerminalBuffer, columns: Int, topRow: Int, bottomRow: Int, row: Int, column: Int): String? {
        val texts = (topRow..bottomRow).map { screen.getSelectedText(0, it, columns - 1, it) ?: "" }
        val joins = texts.indices.map { i -> continues(texts[i], texts.getOrNull(i + 1), columns, screen.getLineWrap(topRow + i)) }
        return at(texts, joins, row - topRow, column)
    }
}

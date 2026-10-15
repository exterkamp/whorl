package codes.exterkamp.conch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinksTest {
    private fun at(rows: List<String>, row: Int, col: Int, joins: List<Boolean> = rows.map { false }) =
        Links.at(rows, joins, row, col)

    @Test
    fun findsUrlUnderTap() {
        val rows = listOf("see https://example.com/a?b=1 for details")
        assertEquals("https://example.com/a?b=1", at(rows, 0, 10))
        assertNull(at(rows, 0, 1))
        assertNull(at(rows, 0, 35))
    }

    @Test
    fun dropsSentencePunctuationButKeepsBalancedParens() {
        assertEquals("https://example.com/x", at(listOf("go to https://example.com/x."), 0, 8))
        assertEquals("https://en.wikipedia.org/wiki/Mosh_(software)",
            at(listOf("(https://en.wikipedia.org/wiki/Mosh_(software))"), 0, 5))
    }

    @Test
    fun followsWrappedRows() {
        val rows = listOf("open https://github.com/exterka", "mp/pocketshell/pull/1 now")
        val joins = listOf(true, false)
        val url = "https://github.com/exterkamp/pocketshell/pull/1"
        assertEquals(url, at(rows, 0, 10, joins))
        assertEquals(url, at(rows, 1, 3, joins))
        assertNull(at(rows, 1, 23, joins))
    }

    @Test
    fun picksTheRightOneOfSeveral() {
        val rows = listOf("http://a.example b http://b.example")
        assertEquals("http://a.example", at(rows, 0, 3))
        assertEquals("http://b.example", at(rows, 0, 25))
    }

    @Test
    fun joinsRowsThatStopShortOfTheEdgeInsideAPane() {
        // herdr draws a 53-column pane on a 54-column screen, with no soft-wrap flag.
        val rows = listOf(
            "PR is at https://github.com/exterkamp/pocketshell/pul",
            "l/1.",
        )
        val joins = rows.indices.map { Links.continues(rows[it], rows.getOrNull(it + 1), 54, false) }
        val url = "https://github.com/exterkamp/pocketshell/pull/1"
        assertEquals(url, at(rows, 1, 1, joins))
        assertEquals(url, at(rows, 0, 20, joins))
    }

    @Test
    fun ignoresPaneBordersAtTheJoin() {
        val rows = listOf("│ see https://example.com/very/long/pa │", "│ th/x                                  │")
        val joins = rows.indices.map { Links.continues(rows[it], rows.getOrNull(it + 1), 41, false) }
        assertEquals("https://example.com/very/long/path/x", at(rows, 1, 3, joins))
    }

    @Test
    fun doesNotJoinShortRows() {
        assertEquals(false, Links.continues("short https://a.example", "next", 54, false))
    }
}

package com.bydmate.app.service

import org.junit.Assert.assertEquals
import org.junit.Test

class ChainLogTest {

    @Test
    fun `keeps the newest lines up to the cap`() {
        val existing = (1..5).joinToString("\n") { "line $it" }
        assertEquals("line 3\nline 4\nline 5\nnew", ChainLog.appended(existing, "new", maxLines = 4))
    }

    @Test
    fun `first entry and blank lines`() {
        assertEquals("first", ChainLog.appended("", "first"))
        assertEquals("a\nb", ChainLog.appended("a\n\n", "b"))
    }

    @Test
    fun `default cap is 200 lines`() {
        val existing = (1..300).joinToString("\n") { "l$it" }
        val out = ChainLog.appended(existing, "new").lines()
        assertEquals(ChainLog.MAX_LINES, out.size)
        assertEquals("l102", out.first())
        assertEquals("new", out.last())
    }
}

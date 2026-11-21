package pt.tiago.zerodroid.ui.kit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownTest {
    @Test
    fun parsesBlocks() {
        val b = parseMarkdown("# Title\n\nSome **bold** text\nnext line\n\n- one\n- two\n\n```sh\nls -la\n```\n> quote")
        assertEquals(MdBlock.Heading(1, "Title"), b[0])
        assertEquals(MdBlock.Paragraph("Some **bold** text\nnext line"), b[1])
        assertEquals(MdBlock.Bullet("•", "one", 0), b[2])
        assertEquals(MdBlock.Code("sh", "ls -la"), b[4])
        assertEquals(MdBlock.Quote("quote"), b[5])
    }

    @Test
    fun unclosedFenceStillRendersAsCode() {
        val b = parseMarkdown("text\n```\nstreaming code")
        assertTrue(b.last() is MdBlock.Code)
    }

    @Test
    fun inlineStripsMarkers() {
        assertEquals("a bold and  code  link", inlineMarkdown("a **bold** and `code` [link](https://x)").text)
    }
}

package pt.tiago.zerodroid.ui.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameDocumentTest {
    @Test
    fun htmlFragmentIsWrapped() {
        val doc = frameDocument("html", "<b>hi</b>", "#000000")!!
        assertTrue(doc.contains("<meta name=\"viewport\""))
        assertTrue(doc.contains("<b>hi</b>"))
        assertTrue(doc.contains("background:#000000"))
    }

    @Test
    fun fullHtmlIsKept() = assertEquals("<HTML><body>x</body></HTML>", frameDocument("html", "<HTML><body>x</body></HTML>", "#000000"))

    @Test
    fun svgIsCentredAndScaled() {
        val doc = frameDocument("svg", "<svg width=\"900\"></svg>", "#000000")!!
        assertTrue(doc.contains("max-width:100%"))
        assertTrue(doc.contains("<svg width=\"900\"></svg>"))
    }

    @Test
    fun markdownAndTextAreNative() {
        assertNull(frameDocument("markdown", "# x", "#000000"))
        assertNull(frameDocument("text", "x", "#000000"))
    }
}

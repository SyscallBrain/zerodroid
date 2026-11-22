package pt.tiago.zerodroid.data.canvas

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import pt.tiago.zerodroid.rpc.ZdJson
import pt.tiago.zerodroid.rpc.proto.MessageEntry

class CanvasCaptureTest {
    private fun args(s: String) = ZdJson.parseToJsonElement(s)

    @Test
    fun renderWithDefaults() =
        assertEquals(CanvasRender("default", "html", "<b>x</b>"), parseCanvasRender("canvas", args("""{"action":"render","content":"<b>x</b>"}""")))

    @Test
    fun renderKeepsIdAndType() = assertEquals(
        CanvasRender("sales", "svg", "<svg/>"),
        parseCanvasRender("canvas", args("""{"action":"render","canvas_id":"sales","content_type":"svg","content":"<svg/>"}""")),
    )

    @Test
    fun blankIdIsDefault() =
        assertEquals("default", parseCanvasRender("canvas", args("""{"action":"render","canvas_id":"  ","content":"a"}"""))?.canvasId)

    @Test
    fun ignoresOtherToolsActionsAndTypes() {
        assertNull(parseCanvasRender("shell", args("""{"action":"render","content":"a"}""")))
        assertNull(parseCanvasRender("canvas", args("""{"action":"clear","canvas_id":"x"}""")))
        assertNull(parseCanvasRender("canvas", args("""{"action":"eval","content":"a"}""")))
        assertNull(parseCanvasRender("canvas", args("""{"action":"render","content_type":"eval","content":"a"}""")))
        assertNull(parseCanvasRender("canvas", args("\"render\"")))
        assertNull(parseCanvasRender("canvas", null))
    }

    @Test
    fun ignoresNonStringContent() =
        assertNull(parseCanvasRender("canvas", args("""{"action":"render","content":{"svg":"x"}}""")))

    @Test
    fun ignoresOversizedContent() {
        val big = "a".repeat(MAX_CANVAS_CONTENT + 1)
        assertNull(parseCanvasRender("canvas", args("""{"action":"render","content":"$big"}""")))
    }

    @Test
    fun successText() {
        assertTrue(canvasRenderSucceeded("Rendered html content to canvas 'x' (frame: 1)"))
        assertFalse(canvasRenderSucceeded("Maximum canvas count reached"))
        assertFalse(canvasRenderSucceeded(""))
    }

    @Test
    fun historyPairsCallsAndFailedResults() {
        val call = { id: String -> MessageEntry("assistant", "", "tool_call", id, args("""{"action":"render","canvas_id":"c","content":"$id"}"""), "canvas") }
        val result = { id: String, out: String -> MessageEntry("tool", out, "tool_result", id, null, "canvas", out) }
        val got = canvasCapturesFromHistory(
            listOf(call("a"), result("a", "Rendered html content to canvas 'c' (frame: f)"), call("b"), result("b", "denied")),
        )
        assertEquals(
            listOf(
                CanvasCapture.Render("a", CanvasRender("c", "html", "a")),
                CanvasCapture.Render("b", CanvasRender("c", "html", "b")),
                CanvasCapture.Failed("b"),
            ),
            got,
        )
    }

    @Test
    fun failedResultWithoutItsCallOnThePageIsReported() {
        val result = MessageEntry("tool", "denied", "tool_result", "z", null, "canvas", "denied")
        assertEquals(listOf(CanvasCapture.Failed("z")), canvasCapturesFromHistory(listOf(result)))
        val ok = MessageEntry("tool", "", "tool_result", "y", null, "canvas", "Rendered html content to canvas 'c' (frame: f)")
        val other = MessageEntry("tool", "err", "tool_result", "x", null, "shell", "err")
        assertTrue(canvasCapturesFromHistory(listOf(ok, other)).isEmpty())
    }
}

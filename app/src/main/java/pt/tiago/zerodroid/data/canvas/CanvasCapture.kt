package pt.tiago.zerodroid.data.canvas

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import pt.tiago.zerodroid.rpc.proto.MessageEntry

/** The daemon's own cap on one canvas frame (`MAX_CONTENT_SIZE` in zeroclaw-tools canvas.rs). */
const val MAX_CANVAS_CONTENT = 256 * 1024

private val CONTENT_TYPES = setOf("html", "svg", "markdown", "text")

/** One frame an agent drew with the `canvas` tool's `render` action. */
data class CanvasRender(val canvasId: String, val contentType: String, val content: String)

/** What a chat tells the canvas archive: a frame was drawn, or a drawn frame turned out to fail. */
sealed interface CanvasCapture {
    val toolCallId: String

    data class Render(override val toolCallId: String, val render: CanvasRender) : CanvasCapture
    data class Failed(override val toolCallId: String) : CanvasCapture
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A `canvas` `render` call with usable content, or null for anything else (other tools, actions, types). */
fun parseCanvasRender(toolName: String?, args: JsonElement?): CanvasRender? {
    if (toolName != "canvas") return null
    val obj = args as? JsonObject ?: return null
    if (obj.string("action") != "render") return null
    val content = obj.string("content") ?: return null
    if (content.toByteArray(Charsets.UTF_8).size > MAX_CANVAS_CONTENT) return null
    val type = obj.string("content_type") ?: "html"
    if (type !in CONTENT_TYPES) return null
    val id = obj.string("canvas_id")?.takeIf { it.isNotBlank() } ?: "default"
    return CanvasRender(id, type, content)
}

/** The tool reports success as `Rendered <type> content to canvas '<id>' (frame: <id>)`; nothing else does. */
fun canvasRenderSucceeded(output: String): Boolean = output.startsWith("Rendered ")

/**
 * Canvas renders in a page of history, in order, and a [CanvasCapture.Failed] for every `canvas`
 * result that is not a success, even when its call sits on another page.
 */
fun canvasCapturesFromHistory(messages: List<MessageEntry>): List<CanvasCapture> {
    val out = mutableListOf<CanvasCapture>()
    for (m in messages) {
        val id = m.toolCallId ?: continue
        when (m.kind) {
            "tool_call" -> parseCanvasRender(m.toolName, m.toolInput)?.let { out += CanvasCapture.Render(id, it) }
            "tool_result" -> if (m.toolName == "canvas" && !canvasRenderSucceeded(m.toolOutput ?: m.content)) out += CanvasCapture.Failed(id)
        }
    }
    return out
}

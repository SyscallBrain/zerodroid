package pt.tiago.zerodroid.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * The app's line icons, drawn on a 24-unit grid with a 1.75 stroke. Tinted at the
 * call site, so the color here is only a placeholder.
 */
object ZcIcons {
    private fun icon(name: String, vararg paths: String, fill: Boolean = false, stroke: Float = 1.75f): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        paths.forEach { d ->
            b.addPath(
                pathData = addPathNodes(d),
                fill = if (fill) SolidColor(Color.White) else null,
                stroke = if (fill) null else SolidColor(Color.White),
                strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }

    val Home = icon("home", "M4 10.2 12 4l8 6.2V19a1 1 0 0 1-1 1h-4.5v-5.5h-5V20H5a1 1 0 0 1-1-1z")
    val Chats = icon("chats", "M20 11.5a7.5 7.5 0 0 1-10.9 6.7L4.5 19.5l1.2-4.1A7.5 7.5 0 1 1 20 11.5z")
    val Inbox = icon("inbox", "M3.5 13.5h4.5l1.5 2.5h5l1.5-2.5h4.5", "M6 5h12l2.5 8.5V18a1 1 0 0 1-1 1h-15a1 1 0 0 1-1-1v-4.5z")
    val Config = icon("config", "M4 7h9", "M17 7h3", "M15 5v4", "M4 12h3", "M11 12h9", "M9 10v4", "M4 17h11", "M19 17h1", "M17 15v4")
    val Send = icon("send", "M12 19V5", "M6 11l6-6 6 6", stroke = 2.2f)
    val Stop = icon("stop", "M8 7.5h8a.5.5 0 0 1 .5.5v8a.5.5 0 0 1-.5.5H8a.5.5 0 0 1-.5-.5V8a.5.5 0 0 1 .5-.5z", fill = true)
    val Back = icon("back", "M15 18l-6-6 6-6", stroke = 2f)
    val More = icon("more", "M12 5.5h.01", "M12 12h.01", "M12 18.5h.01", stroke = 3f)
    val Check = icon("check", "M5 12.5l4.5 4.5L19 7.5", stroke = 2.2f)
    val Close = icon("close", "M6.5 6.5l11 11", "M17.5 6.5l-11 11", stroke = 2f)
    val Chevron = icon("chevron", "M9.5 6l6 6-6 6", stroke = 2f)
    val ChevronDown = icon("chevron-down", "M6 9.5l6 6 6-6", stroke = 2f)
    val Plus = icon("plus", "M12 5v14", "M5 12h14", stroke = 2f)
    val Search = icon("search", "M11 18a7 7 0 1 0 0-14 7 7 0 0 0 0 14z", "M20 20l-3.8-3.8")
    val Alert = icon("alert", "M12 8v5", "M12 16.5h.01", stroke = 2.4f)
    val Link = icon("link", "M10 14a4 4 0 0 0 5.66 0l3-3a4 4 0 0 0-5.66-5.66l-1 1", "M14 10a4 4 0 0 0-5.66 0l-3 3a4 4 0 0 0 5.66 5.66l1-1")
    val Qr = icon("qr", "M4.5 4.5h5v5h-5z", "M14.5 4.5h5v5h-5z", "M4.5 14.5h5v5h-5z", "M14.5 14.5h2v2h-2z", "M17.5 17.5h2v2h-2z", "M14.5 19.5h.01", "M19.5 14.5h.01")
    val Clipboard = icon("clipboard", "M9 4.5h6v2.5H9z", "M15 5.5h2a1.5 1.5 0 0 1 1.5 1.5v12A1.5 1.5 0 0 1 17 20.5H7A1.5 1.5 0 0 1 5.5 19V7A1.5 1.5 0 0 1 7 5.5h2")
    val Terminal = icon("terminal", "M5 7l5 5-5 5", "M12.5 17.5h6.5")
    val Refresh = icon("refresh", "M19.5 12a7.5 7.5 0 1 1-2.2-5.3", "M19.5 4.5v4h-4")
    val Shield = icon("shield", "M12 3.5l7 2.8v5.2c0 4.4-3 7.9-7 9-4-1.1-7-4.6-7-9V6.3z", "M9 12l2.2 2.2L15.5 10")
    val Bolt = icon("bolt", "M13 3.5L5.5 13.5H12l-1 7 7.5-10H12z")
    val Clock = icon("clock", "M12 20.5a8.5 8.5 0 1 0 0-17 8.5 8.5 0 0 0 0 17z", "M12 7.5V12l3 2")
    val Wrench = icon("wrench", "M14.7 6.3a4 4 0 0 0 5 5L13 18a2.1 2.1 0 0 1-3-3z", "M10 15l-5.5 5.5")
    val Trash = icon("trash", "M4.5 7h15", "M9.5 7V4.5h5V7", "M6.5 7l1 12.5h9l1-12.5", "M10 10.5v6", "M14 10.5v6")
    val Sun = icon("sun", "M12 16a4 4 0 1 0 0-8 4 4 0 0 0 0 8z", "M12 2.5v2", "M12 19.5v2", "M4.5 12h-2", "M21.5 12h-2", "M6.7 6.7 5.3 5.3", "M18.7 18.7l-1.4-1.4", "M17.3 6.7l1.4-1.4", "M5.3 18.7l1.4-1.4")
    val Battery = icon("battery", "M4.5 8h13a1 1 0 0 1 1 1v6a1 1 0 0 1-1 1h-13a1 1 0 0 1-1-1V9a1 1 0 0 1 1-1z", "M21 11v2", "M7 11v2", "M10 11v2")
    val Unlink = icon("unlink", "M10 14a4 4 0 0 0 5.66 0l3-3a4 4 0 0 0-3.2-6.8", "M14 10a4 4 0 0 0-5.66 0l-3 3a4 4 0 0 0 3.2 6.8", "M4 4l16 16")
    val Sparkle = icon("sparkle", "M12 4l1.8 4.7L18.5 10.5l-4.7 1.8L12 17l-1.8-4.7L5.5 10.5l4.7-1.8z")
    val Star = icon("star", "M12 3.8l2.5 5.1 5.6.8-4 3.9 1 5.6-5.1-2.7-5.1 2.7 1-5.6-4-3.9 5.6-.8z")
    val StarFilled = icon("star-filled", "M12 3.8l2.5 5.1 5.6.8-4 3.9 1 5.6-5.1-2.7-5.1 2.7 1-5.6-4-3.9 5.6-.8z", fill = true)
    val Image = icon("image", "M5 4.5h14a1.5 1.5 0 0 1 1.5 1.5v12a1.5 1.5 0 0 1-1.5 1.5H5A1.5 1.5 0 0 1 3.5 18V6A1.5 1.5 0 0 1 5 4.5z", "M8.5 10.5a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3z", "M20.5 15l-5-5-9 9.5")
    val Copy = icon("copy", "M9 9h10a1 1 0 0 1 1 1v10a1 1 0 0 1-1 1H9a1 1 0 0 1-1-1V10a1 1 0 0 1 1-1z", "M5 15H4.5A1.5 1.5 0 0 1 3 13.5v-9A1.5 1.5 0 0 1 4.5 3h9A1.5 1.5 0 0 1 15 4.5V5")
    val Cpu = icon("cpu", "M7 7h10v10H7z", "M10 3v4", "M14 3v4", "M10 17v4", "M14 17v4", "M3 10h4", "M3 14h4", "M17 10h4", "M17 14h4")
    val Code = icon("code", "M8.5 7.5 4 12l4.5 4.5", "M15.5 7.5 20 12l-4.5 4.5", "M13.5 5l-3 14")
    val Folder = icon("folder", "M3.5 7A1.5 1.5 0 0 1 5 5.5h4.2l2 2.2H19A1.5 1.5 0 0 1 20.5 9.2V17A1.5 1.5 0 0 1 19 18.5H5A1.5 1.5 0 0 1 3.5 17z")
    val Memory = icon("memory", "M3.5 8h17v8h-17z", "M7 8v8", "M11 8v8", "M15 8v8", "M5 16v3", "M19 16v3")
    val Undo = icon("undo", "M9 14.5 4.5 10 9 5.5", "M4.5 10h10a5 5 0 0 1 0 10H12")
    val Branch = icon("branch", "M6.5 4v11", "M17.5 9.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5z", "M6.5 20.5a2.5 2.5 0 1 0 0-5 2.5 2.5 0 0 0 0 5z", "M17.5 9.5a8 8 0 0 1-8 8")
    val Play = icon("play", "M7.5 5.5v13l11-6.5z")
    val Flow = icon("flow", "M4 4.5h7v5H4z", "M13 14.5h7v5h-7z", "M7.5 9.5v4.5a1 1 0 0 0 1 1H13")
    val Book = icon("book", "M2.5 4h6a3.5 3.5 0 0 1 3.5 3.5V20a3 3 0 0 0-3-3h-6.5z", "M21.5 4h-6A3.5 3.5 0 0 0 12 7.5V20a3 3 0 0 1 3-3h6.5z")
}

package pt.tiago.zerodroid.ui.canvas

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import pt.tiago.zerodroid.ui.theme.ZcTokens

/** Where the Canvas overlay is. Back goes View → List → closed. */
sealed interface CanvasRoute {
    data object List : CanvasRoute
    /** [frameId] null = the latest frame. Opened [fromChat], Back returns to the chat. */
    data class View(val canvasId: String, val frameId: String? = null, val fromChat: Boolean = false) : CanvasRoute
}

/** Saves the route as strings: [] (closed), ["L"], or ["V" or "C" (from chat), canvasId, frameId?]. */
val CanvasRouteSaver: Saver<CanvasRoute?, ArrayList<String>> = Saver(
    save = { r ->
        when (r) {
            null -> arrayListOf()
            CanvasRoute.List -> arrayListOf("L")
            is CanvasRoute.View -> arrayListOf(if (r.fromChat) "C" else "V", r.canvasId).apply { r.frameId?.let(::add) }
        }
    },
    restore = { s ->
        when (s.firstOrNull()) {
            "L" -> CanvasRoute.List
            "V", "C" -> s.getOrNull(1)?.let { CanvasRoute.View(it, s.getOrNull(2), fromChat = s[0] == "C") }
            else -> null
        }
    },
)

/** The Canvas overlay: opaque, and swallows taps so nothing reaches the screens underneath. */
@Composable
fun CanvasHost(route: CanvasRoute, onRoute: (CanvasRoute?) -> Unit, onOpenChat: (sessionId: String, agentAlias: String) -> Unit) {
    val back = { onRoute(if (route is CanvasRoute.View && !route.fromChat) CanvasRoute.List else null) }
    BackHandler(onBack = back)
    Box(Modifier.fillMaxSize().background(ZcTokens.background).pointerInput(Unit) { detectTapGestures { } }) {
        when (route) {
            CanvasRoute.List -> CanvasListScreen(onBack = { onRoute(null) }, onOpen = { onRoute(CanvasRoute.View(it)) })
            is CanvasRoute.View -> CanvasViewScreen(
                route.canvasId, route.frameId,
                onBack = back,
                onDeleted = { onRoute(CanvasRoute.List) },
                onOpenChat = { id, alias -> onRoute(null); onOpenChat(id, alias) },
            )
        }
    }
}

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
    /** The canvases drawn in one chat; Back returns to that chat. */
    data class ChatList(val sessionId: String) : CanvasRoute
    /**
     * [frameId] null = the latest frame. Opened [fromChat], Back returns to the chat; opened from a
     * [ChatList], Back returns to it ([chatSession]).
     */
    data class View(
        val canvasId: String,
        val frameId: String? = null,
        val fromChat: Boolean = false,
        val chatSession: String? = null,
    ) : CanvasRoute
}

/**
 * Saves the route as strings: [] (closed), ["L"], ["S", sessionId] (one chat's list), or
 * ["V", canvasId, fromChat, chatSession, frameId] with "" for a missing value.
 */
val CanvasRouteSaver: Saver<CanvasRoute?, ArrayList<String>> = Saver(
    save = { r ->
        when (r) {
            null -> arrayListOf()
            CanvasRoute.List -> arrayListOf("L")
            is CanvasRoute.ChatList -> arrayListOf("S", r.sessionId)
            is CanvasRoute.View -> arrayListOf("V", r.canvasId, if (r.fromChat) "1" else "", r.chatSession.orEmpty(), r.frameId.orEmpty())
        }
    },
    restore = { s ->
        when (s.firstOrNull()) {
            "L" -> CanvasRoute.List
            "S" -> s.getOrNull(1)?.let { CanvasRoute.ChatList(it) }
            "V" -> s.getOrNull(1)?.let {
                CanvasRoute.View(it, s.getOrNull(4)?.ifEmpty { null }, fromChat = s.getOrNull(2) == "1", chatSession = s.getOrNull(3)?.ifEmpty { null })
            }
            else -> null
        }
    },
)

/** Where Back goes from [route]; null closes the overlay. */
fun canvasBack(route: CanvasRoute): CanvasRoute? = when (route) {
    CanvasRoute.List, is CanvasRoute.ChatList -> null
    is CanvasRoute.View -> when {
        route.chatSession != null -> CanvasRoute.ChatList(route.chatSession)
        route.fromChat -> null
        else -> CanvasRoute.List
    }
}

/** The Canvas overlay: opaque, and swallows taps so nothing reaches the screens underneath. */
@Composable
fun CanvasHost(route: CanvasRoute, onRoute: (CanvasRoute?) -> Unit, onOpenChat: (sessionId: String, agentAlias: String) -> Unit) {
    val back = { onRoute(canvasBack(route)) }
    BackHandler(onBack = back)
    Box(Modifier.fillMaxSize().background(ZcTokens.background).pointerInput(Unit) { detectTapGestures { } }) {
        when (route) {
            CanvasRoute.List -> CanvasListScreen(onBack = back, onOpen = { onRoute(CanvasRoute.View(it)) })
            is CanvasRoute.ChatList -> CanvasListScreen(
                onBack = back,
                onOpen = { onRoute(CanvasRoute.View(it, chatSession = route.sessionId)) },
                onlySession = route.sessionId,
            )
            is CanvasRoute.View -> CanvasViewScreen(
                route.canvasId, route.frameId,
                onBack = back,
                onDeleted = { onRoute(canvasBack(route) ?: CanvasRoute.List) },
                onOpenChat = { id, alias -> onRoute(null); onOpenChat(id, alias) },
            )
        }
    }
}

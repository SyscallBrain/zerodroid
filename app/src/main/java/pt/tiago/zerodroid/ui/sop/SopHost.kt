package pt.tiago.zerodroid.ui.sop

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.viewmodel.compose.viewModel
import pt.tiago.zerodroid.ui.theme.ZcTokens

/** Where the Workflows overlay is. Back pops Run → Edit → List → closed. */
sealed interface SopRoute {
    data object List : SopRoute
    /** [name] null = a new workflow. */
    data class Edit(val name: String?) : SopRoute
    data class Run(val name: String, val runId: String) : SopRoute
}

private fun routeKey(r: SopRoute) = when (r) {
    SopRoute.List -> "L"
    is SopRoute.Edit -> r.name?.let { "E:$it" } ?: "N"
    is SopRoute.Run -> "R:${r.runId}"
}

/** Saves the route stack as strings: "L", "E:<name>", "N" (new), "R:<runId>:<name>". */
val SopStackSaver: Saver<List<SopRoute>, ArrayList<String>> = Saver(
    save = { stack ->
        ArrayList(
            stack.map {
                when (it) {
                    SopRoute.List -> "L"
                    is SopRoute.Edit -> it.name?.let { n -> "E:$n" } ?: "N"
                    is SopRoute.Run -> "R:${it.runId}:${it.name}"
                }
            },
        )
    },
    restore = { saved ->
        saved.mapNotNull { s ->
            when {
                s == "L" -> SopRoute.List
                s == "N" -> SopRoute.Edit(null)
                s.startsWith("E:") -> SopRoute.Edit(s.removePrefix("E:"))
                s.startsWith("R:") -> s.removePrefix("R:").split(":", limit = 2).takeIf { it.size == 2 }?.let { SopRoute.Run(it[1], it[0]) }
                else -> null
            }
        }
    },
)

/**
 * The Workflows overlay: opaque, swallows taps so nothing reaches the tabs underneath, like Chat.
 * [stack] is never empty while shown; [onStack] replaces it (empty closes the overlay).
 */
@Composable
fun SopHost(stack: List<SopRoute>, onStack: (List<SopRoute>) -> Unit, onEnableEngine: () -> Unit) {
    val top = stack.lastOrNull() ?: return
    val pop = { onStack(stack.dropLast(1)) }
    val push = { r: SopRoute -> onStack(stack + r) }
    var runAsk by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<String?>(null) }
    // Each stack entry owns its ViewModels; popping an entry clears them (fresh draft next time).
    val stores = androidx.compose.runtime.remember { RouteStores() }
    val keys = stack.mapIndexed { i, r -> "$i:${routeKey(r)}" }
    androidx.compose.runtime.SideEffect { stores.retain(keys.toSet()) }
    androidx.compose.runtime.DisposableEffect(stores) { onDispose { stores.clearAll() } }
    BackHandler(onBack = pop)
    Box(Modifier.fillMaxSize().background(ZcTokens.background).pointerInput(Unit) { detectTapGestures { } }) {
        androidx.compose.runtime.CompositionLocalProvider(
            androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner provides stores.ownerFor(keys.last()),
        ) {
        when (top) {
            SopRoute.List -> {
                val vm: SopListViewModel = viewModel(key = "sop-list") { SopListViewModel() }
                SopListScreen(
                    vm,
                    onBack = pop,
                    onOpen = { push(SopRoute.Edit(it)) },
                    onRun = { name -> runAsk = name },
                    onOpenRun = { name, id -> push(SopRoute.Run(name, id)) },
                    onEnable = onEnableEngine,
                )
            }
            is SopRoute.Edit -> SopEditorEntry(top.name, onBack = pop, onReplace = { onStack(stack.dropLast(1) + it) }, onPush = push, onEnableEngine = onEnableEngine)
            is SopRoute.Run -> SopRunEntry(top.name, top.runId, onBack = pop, onEnableEngine = onEnableEngine)
        }
        }
    }
    runAsk?.let { name ->
        RunSheet(name, onDismiss = { runAsk = null }, onStarted = { id -> runAsk = null; push(SopRoute.Run(name, id)) }, onEngineOff = onEnableEngine)
    }
}

package pt.tiago.zerodroid.ui.cron

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import pt.tiago.zerodroid.ui.sop.RouteStores
import pt.tiago.zerodroid.ui.theme.ZcTokens

/** Where the Automation overlay is. Back pops Output → Edit → Detail → List → closed. */
sealed interface AutomationRoute {
    data object List : AutomationRoute
    data class Detail(val id: String) : AutomationRoute
    /** [id] null = a new job. */
    data class Edit(val id: String?) : AutomationRoute
    /** A run's full output. Not saved across process death (it can be long). */
    data class Output(val title: String, val text: String) : AutomationRoute
}

private fun routeKey(r: AutomationRoute): String? = when (r) {
    AutomationRoute.List -> "L"
    is AutomationRoute.Detail -> "D:${r.id}"
    is AutomationRoute.Edit -> r.id?.let { "E:$it" } ?: "N"
    is AutomationRoute.Output -> null
}

/** The stack as strings: "L", "D:<id>", "E:<id>", "N" (new job). Output entries are dropped. */
fun saveAutomationStack(stack: List<AutomationRoute>): ArrayList<String> = ArrayList(stack.mapNotNull(::routeKey))

fun restoreAutomationStack(saved: List<String>): List<AutomationRoute> = saved.mapNotNull { s ->
    when {
        s == "L" -> AutomationRoute.List
        s == "N" -> AutomationRoute.Edit(null)
        s.startsWith("D:") -> AutomationRoute.Detail(s.removePrefix("D:"))
        s.startsWith("E:") -> AutomationRoute.Edit(s.removePrefix("E:"))
        else -> null
    }
}

val AutomationStackSaver: Saver<List<AutomationRoute>, ArrayList<String>> =
    Saver(save = { saveAutomationStack(it) }, restore = { restoreAutomationStack(it) })

/** After the editor saved [id]: the editor gives way to that job's detail (reused if it is underneath). */
fun afterSave(stack: List<AutomationRoute>, id: String): List<AutomationRoute> {
    val under = stack.dropLast(1)
    return if (under.lastOrNull() == AutomationRoute.Detail(id)) under else under + AutomationRoute.Detail(id)
}

/** The Automation overlay: opaque, swallows taps. [stack] is never empty while shown; empty closes it. */
@Composable
fun AutomationHost(stack: List<AutomationRoute>, onStack: (List<AutomationRoute>) -> Unit) {
    val top = stack.lastOrNull() ?: return
    val pop = { onStack(stack.dropLast(1)) }
    val push = { r: AutomationRoute -> onStack(stack + r) }
    val stores = remember { RouteStores() }
    val keys = stack.mapIndexed { i, r -> "$i:${routeKey(r) ?: "O"}" }
    SideEffect { stores.retain(keys.toSet()) }
    DisposableEffect(stores) { onDispose { stores.clearAll() } }
    // The editor handles Back itself (it asks about unsaved edits).
    BackHandler(enabled = top !is AutomationRoute.Edit, onBack = pop)
    Box(Modifier.fillMaxSize().background(ZcTokens.background).pointerInput(Unit) { detectTapGestures { } }) {
        CompositionLocalProvider(LocalViewModelStoreOwner provides stores.ownerFor(keys.last())) {
            when (top) {
                AutomationRoute.List -> AutomationListScreen(
                    viewModel(key = "cron-list") { AutomationListViewModel() },
                    onBack = pop,
                    onOpen = { push(AutomationRoute.Detail(it)) },
                    onNew = { push(AutomationRoute.Edit(null)) },
                )
                is AutomationRoute.Detail -> AutomationDetailScreen(
                    viewModel(key = "cron-detail-${top.id}") { AutomationDetailViewModel(top.id) },
                    onBack = pop,
                    onEdit = { push(AutomationRoute.Edit(top.id)) },
                    onOutput = { title, text -> push(AutomationRoute.Output(title, text)) },
                )
                is AutomationRoute.Edit -> AutomationEditorScreen(
                    viewModel(key = "cron-edit-${top.id ?: "new"}") { AutomationEditorViewModel(top.id) },
                    onBack = pop,
                    onSaved = { onStack(afterSave(stack, it)) },
                )
                is AutomationRoute.Output -> RunOutputScreen(top.title, top.text, onBack = pop)
            }
        }
    }
}

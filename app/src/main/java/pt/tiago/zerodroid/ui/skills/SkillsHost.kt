package pt.tiago.zerodroid.ui.skills

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

/** Where the Skills overlay is. Back pops Editor → List → closed. */
sealed interface SkillsRoute {
    data object List : SkillsRoute
    data class Edit(val bundle: String, val name: String) : SkillsRoute
}

private fun routeKey(r: SkillsRoute): String = when (r) {
    SkillsRoute.List -> "L"
    is SkillsRoute.Edit -> "E:${r.bundle}/${r.name}"
}

fun saveSkillsStack(stack: List<SkillsRoute>): ArrayList<String> = ArrayList(stack.map(::routeKey))

fun restoreSkillsStack(saved: List<String>): List<SkillsRoute> = saved.mapNotNull { s ->
    when {
        s == "L" -> SkillsRoute.List
        s.startsWith("E:") -> s.removePrefix("E:").split("/", limit = 2).takeIf { it.size == 2 }?.let { (b, n) -> SkillsRoute.Edit(b, n) }
        else -> null
    }
}

val SkillsStackSaver: Saver<List<SkillsRoute>, ArrayList<String>> =
    Saver(save = { saveSkillsStack(it) }, restore = { restoreSkillsStack(it) })

/** The Skills overlay: opaque, swallows taps. [stack] is never empty while shown; empty closes it. */
@Composable
fun SkillsHost(stack: List<SkillsRoute>, onStack: (List<SkillsRoute>) -> Unit) {
    val top = stack.lastOrNull() ?: return
    val pop = { onStack(stack.dropLast(1)) }
    val push = { r: SkillsRoute -> onStack(stack + r) }
    val stores = remember { RouteStores() }
    val keys = stack.mapIndexed { i, r -> "$i:${routeKey(r)}" }
    SideEffect { stores.retain(keys.toSet()) }
    DisposableEffect(stores) { onDispose { stores.clearAll() } }
    // The editor handles Back itself (it asks about unsaved edits).
    BackHandler(enabled = top !is SkillsRoute.Edit, onBack = pop)
    Box(Modifier.fillMaxSize().background(ZcTokens.background).pointerInput(Unit) { detectTapGestures { } }) {
        CompositionLocalProvider(LocalViewModelStoreOwner provides stores.ownerFor(keys.last())) {
            when (top) {
                SkillsRoute.List -> SkillsListScreen(
                    viewModel(key = "skills-list") { SkillsListViewModel() },
                    onBack = pop,
                    onOpen = { bundle, name -> push(SkillsRoute.Edit(bundle, name)) },
                )
                is SkillsRoute.Edit -> SkillEditorScreen(
                    viewModel(key = "skills-edit-${top.bundle}/${top.name}") { SkillEditorViewModel(top.bundle, top.name) },
                    onBack = pop,
                    onDeleted = { onStack(stack.dropLast(1)) },
                )
            }
        }
    }
}

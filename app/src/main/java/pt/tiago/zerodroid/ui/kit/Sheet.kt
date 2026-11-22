package pt.tiago.zerodroid.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity

/**
 * `contentWindowInsets` for every ModalBottomSheet here: none. The Material default pads the content
 * by the status bar minus the sheet's own offset, so a sheet nearly as tall as the screen grows as it
 * nears the top, its Expanded anchor moves down, the padding shrinks, and it bounces on its own.
 * The content pads the navigation bar itself and sits in [SheetBody] to stay below the status bar.
 */
val NoSheetInsets: @Composable () -> WindowInsets = { WindowInsets(0) }

/**
 * Caps sheet content at the height left under the status bar. The cap is fixed, so the sheet's
 * height never depends on where the sheet is; a taller content scrolls or bounds its own lists.
 */
@Composable
fun SheetBody(content: @Composable BoxScope.() -> Unit) {
    val statusBar = WindowInsets.statusBars.getTop(LocalDensity.current)
    Box(
        Modifier.layout { measurable, c ->
            val capped = if (c.hasBoundedHeight) c.copy(maxHeight = (c.maxHeight - statusBar).coerceAtLeast(c.minHeight)) else c
            val p = measurable.measure(capped)
            layout(p.width, p.height) { p.place(0, 0) }
        },
        content = content,
    )
}

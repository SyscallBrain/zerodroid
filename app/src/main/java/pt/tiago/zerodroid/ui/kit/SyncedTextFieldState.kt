package pt.tiago.zerodroid.ui.kit

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow

/**
 * A [TextFieldState] for callers that keep the text as a plain `String` (`value` + `onValueChange`).
 *
 * The field owns the text while it is on screen: every edit goes out through [onValueChange], and a
 * `value` coming back in only overwrites the field when the field did not produce it itself (the
 * caller cleared it, filtered it, or loaded another item). Values the field sent are queued until the
 * caller hands them back, so a caller one frame behind while typing fast never rewinds the text.
 */
@Composable
fun rememberSyncedTextFieldState(value: String, onValueChange: (String) -> Unit): TextFieldState {
    val state = rememberTextFieldState(value)
    val sync = remember { TextSync(value) }
    val emit by rememberUpdatedState(onValueChange)
    LaunchedEffect(value) {
        val sent = sync.pending.indexOf(value)
        if (sent >= 0) {
            repeat(sent + 1) { sync.pending.removeFirst() }
        } else if (value != state.text.toString()) {
            sync.pending.clear()
            sync.last = value
            state.setTextAndPlaceCursorAtEnd(value)
        }
    }
    LaunchedEffect(state) {
        snapshotFlow { state.text.toString() }.collect { text ->
            if (text != sync.last) {
                sync.last = text
                sync.pending.addLast(text)
                if (sync.pending.size > MAX_PENDING) sync.pending.removeFirst()
                emit(text)
            }
        }
    }
    return state
}

private class TextSync(var last: String) {
    val pending = ArrayDeque<String>()
}

/** A caller that never hands values back must not grow the queue without bound. */
private const val MAX_PENDING = 64

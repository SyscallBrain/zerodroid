package pt.tiago.zerodroid.ui.code

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pt.tiago.zerodroid.data.parentFolder
import pt.tiago.zerodroid.rpc.proto.FsEntry
import pt.tiago.zerodroid.rpc.proto.FsListDirResult
import kotlin.coroutines.cancellation.CancellationException

/** What the folder browser of the New flow shows. */
data class FolderBrowse(
    val alias: String,
    /** The folder whose [entries] are on screen; changes only when its listing arrives. */
    val path: String,
    val entries: List<FsEntry> = emptyList(),
    /** A listing is in flight; the previous one stays on screen meanwhile. */
    val loading: Boolean = true,
    val error: String? = null,
    val showHidden: Boolean = false,
    /** Nothing useful on screen, or the listing is slow: show placeholder rows. */
    val showSkeleton: Boolean = false,
) {
    /** A session may start here: the folder on screen is the one just listed. */
    val canStart: Boolean get() = !loading && error == null

    /**
     * How the Work in button looks: lit through quick loads (no blink between folders),
     * dimmed on an error or a slow load. Taps still need [canStart].
     */
    val startLooksEnabled: Boolean get() = error == null && !showSkeleton
}

/**
 * Browses the daemon's folders without flashing: while a folder loads, the last
 * listing stays on screen, and placeholders appear only if nothing is shown yet or
 * the answer takes longer than [skeletonDelayMs]. Listings are kept while the
 * browser is open, so going back to a folder is instant (and refreshed quietly).
 */
class FolderBrowser(
    private val scope: CoroutineScope,
    private val lister: suspend (path: String, showHidden: Boolean) -> FsListDirResult,
    private val errorText: (Throwable) -> String = { it.message ?: "error" },
    private val skeletonDelayMs: Long = 250,
) {
    private val _state = MutableStateFlow<FolderBrowse?>(null)
    val state: StateFlow<FolderBrowse?> = _state.asStateFlow()

    private val cache = HashMap<Pair<String, Boolean>, FsListDirResult>()
    private var request: Job? = null
    private var skeleton: Job? = null

    fun open(alias: String, path: String) {
        cancel()
        cache.clear()
        _state.value = FolderBrowse(alias, normalise(path), showSkeleton = true)
        load(normalise(path), showHidden = false)
    }

    fun browseTo(path: String) {
        val b = _state.value ?: return
        load(normalise(path), b.showHidden)
    }

    fun up() {
        val b = _state.value ?: return
        parentFolder(b.path)?.let { load(it, b.showHidden) }
    }

    fun toggleHidden() {
        val b = _state.value ?: return
        load(b.path, !b.showHidden)
    }

    fun close() {
        cancel()
        cache.clear()
        _state.value = null
    }

    private fun load(target: String, showHidden: Boolean) {
        cancel()
        val cached = cache[target to showHidden]
        _state.update { b ->
            b ?: return
            if (cached != null) {
                b.copy(path = cached.cwd.ifEmpty { target }, entries = cached.entries, loading = true, error = null, showHidden = showHidden, showSkeleton = false)
            } else {
                // Keep what is on screen; placeholders only when there is nothing to keep.
                val empty = b.entries.isEmpty() && b.error == null
                b.copy(loading = true, showHidden = showHidden, showSkeleton = empty)
            }
        }
        if (cached == null && _state.value?.showSkeleton == false) {
            skeleton = scope.launch {
                delay(skeletonDelayMs)
                _state.update { it?.copy(showSkeleton = true) }
            }
        }
        request = scope.launch {
            try {
                val r = lister(target, showHidden)
                val cwd = r.cwd.ifEmpty { target }
                cache[target to showHidden] = r
                cache[cwd to showHidden] = r
                skeleton?.cancel()
                _state.update { it?.copy(path = cwd, entries = r.entries, loading = false, error = null, showSkeleton = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                cache.remove(target to showHidden)
                skeleton?.cancel()
                _state.update { it?.copy(path = target, entries = emptyList(), loading = false, error = errorText(t), showSkeleton = false) }
            }
        }
    }

    private fun cancel() {
        request?.cancel(); request = null
        skeleton?.cancel(); skeleton = null
    }

    private fun normalise(path: String) = path.trim().ifEmpty { "/" }
}

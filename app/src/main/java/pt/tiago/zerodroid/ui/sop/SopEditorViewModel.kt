package pt.tiago.zerodroid.ui.sop

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import pt.tiago.zerodroid.data.ZeroDroidConnection
import pt.tiago.zerodroid.data.sop.SopDraft
import pt.tiago.zerodroid.data.sop.SopRepository
import pt.tiago.zerodroid.data.sop.SopResult
import pt.tiago.zerodroid.data.sop.ToolSuggestions
import pt.tiago.zerodroid.data.sop.WireEdit
import pt.tiago.zerodroid.rpc.proto.SopGraphDto
import pt.tiago.zerodroid.rpc.proto.TriggerSourceRegistryDto

/** A step port the user can wire from, and the `flow_role` it maps to for `sops/wire-draft`. */
enum class PortRole(val wire: String) { Next("sequence"), WaitsFor("dependency"), OnFailure("failure"), Branch("switch") }

sealed interface EditorMode {
    data object Browse : EditorMode
    /** Picking a target for [role] of step [from]; [port] is the switch rule index for [PortRole.Branch]. */
    data class Connect(val from: Int, val role: PortRole, val port: Int? = null) : EditorMode
}

data class SaveReview(val blocking: List<String>, val warnings: List<String>)

data class EditorState(
    val loading: Boolean = true,
    val isNew: Boolean = false,
    val draft: JsonObject? = null,
    val graph: SopGraphDto? = null,
    val dirty: Boolean = false,
    val canUndo: Boolean = false,
    val mode: EditorMode = EditorMode.Browse,
    val openStep: Int? = null,
    val saving: Boolean = false,
    val review: SaveReview? = null,
    val error: String? = null,
    val toast: String? = null,
    val registry: TriggerSourceRegistryDto? = null,
    val toolSuggestions: List<String> = emptyList(),
)

private const val UNDO_CAP = 50
private const val GRAPH_DEBOUNCE_MS = 400L

/**
 * The SOP editor. The draft is the daemon's raw JSON; every graph change (projection, layout,
 * wiring) comes back from the daemon. Each draft change bumps [version]; a daemon reply computed
 * for an older version is dropped, so fast taps (connect then Undo) never resurrect a stale draft.
 */
class SopEditorViewModel(
    name: String?,
    private val repo: SopRepository = ZeroDroidConnection.sops,
    private val chatTools: () -> Set<String> = { ZeroDroidConnection.chats.seenToolNames() },
    private val scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope
    private val _state = MutableStateFlow(EditorState(isNew = name == null))
    val state: StateFlow<EditorState> = _state.asStateFlow()

    private var version = 0L
    private val undoStack = ArrayDeque<Pair<JsonObject, SopGraphDto?>>()
    private var graphJob: Job? = null

    init {
        scope.launch { load(name) }
        scope.launch { (repo.triggerSources() as? SopResult.Ok)?.let { r -> _state.update { it.copy(registry = r.value) } } }
    }

    private suspend fun load(name: String?) {
        if (name == null) {
            val d = SopDraft.blank("")
            _state.update { it.copy(loading = false, draft = d) }
            refreshGraph(d, version)
        } else {
            when (val r = repo.get(name)) {
                is SopResult.Ok -> {
                    _state.update { it.copy(loading = false, draft = r.value) }
                    when (val g = repo.graph(name)) {
                        is SopResult.Ok -> _state.update { it.copy(graph = g.value) }
                        else -> refreshGraph(r.value, version)
                    }
                }
                is SopResult.Error -> _state.update { it.copy(loading = false, error = r.message) }
                SopResult.Forbidden -> _state.update { it.copy(loading = false, error = "Your sign-in can't read workflows") }
                SopResult.Unavailable -> _state.update { it.copy(loading = false, error = "The daemon can't read workflows right now") }
            }
        }
        loadSuggestions()
    }

    private suspend fun loadSuggestions() {
        val names = (repo.list() as? SopResult.Ok)?.value?.map { it.name }.orEmpty()
        val gate = Semaphore(4)
        val tools = names.map { n -> scope.async { gate.withPermit { (repo.get(n) as? SopResult.Ok)?.value?.let(SopDraft::toolNames).orEmpty() } } }
            .awaitAll().flatten().toSet()
        val own = _state.value.draft?.let(SopDraft::toolNames).orEmpty()
        _state.update { it.copy(toolSuggestions = ToolSuggestions.merge(tools + own, runCatching(chatTools).getOrDefault(emptySet()))) }
    }

    // ── draft edits ─────────────────────────────────────────────────────

    /** Applies a local, non-wire edit, records it for Undo, and re-projects the graph after a short pause. */
    fun edit(transform: (JsonObject) -> JsonObject) {
        val cur = _state.value.draft ?: return
        val next = transform(cur)
        if (next == cur) return
        pushUndo(cur)
        version++
        _state.update { it.copy(draft = next, dirty = true, canUndo = true) }
        scheduleGraph(next)
    }

    fun addStep() {
        var n = 0
        edit { d -> SopDraft.addStep(d).also { n = it.second }.first }
        if (n > 0) openStep(n)
    }

    fun deleteStep(n: Int) {
        if (_state.value.openStep == n) openStep(null)
        edit { SopDraft.deleteStep(it, n) }
    }

    fun moveStep(n: Int, dir: Int) = edit { SopDraft.moveStep(it, n, dir) }

    fun duplicateStep(n: Int) {
        var m = 0
        edit { d -> SopDraft.duplicateStep(d, n).also { m = it.second }.first }
        if (m > 0) openStep(m)
    }

    fun openStep(n: Int?) = _state.update { it.copy(openStep = n) }

    fun undo() {
        val (d, g) = undoStack.removeLastOrNull() ?: return
        version++
        graphJob?.cancel()
        _state.update { it.copy(draft = d, graph = g ?: it.graph, dirty = true, canUndo = undoStack.isNotEmpty(), mode = EditorMode.Browse) }
        if (g == null) scheduleGraph(d)
    }

    // ── wiring ──────────────────────────────────────────────────────────

    fun startConnect(from: Int, role: PortRole, port: Int? = null) =
        _state.update { it.copy(mode = EditorMode.Connect(from, role, port), openStep = null) }

    fun cancelConnect() = _state.update { it.copy(mode = EditorMode.Browse) }

    fun connectTo(to: Int) {
        val m = _state.value.mode as? EditorMode.Connect ?: return
        _state.update { it.copy(mode = EditorMode.Browse) }
        wire(WireEdit("connect", m.from, to, m.role.wire, m.port))
    }

    /** Adds a step and wires the pending port to it in one go. */
    fun connectToNewStep() {
        val m = _state.value.mode as? EditorMode.Connect ?: return
        val cur = _state.value.draft ?: return
        val (next, n) = SopDraft.addStep(cur)
        pushUndo(cur)
        version++
        _state.update { it.copy(draft = next, dirty = true, canUndo = true, mode = EditorMode.Browse) }
        wire(WireEdit("connect", m.from, n, m.role.wire, m.port), pushUndo = false)
    }

    fun disconnect(from: Int, to: Int, role: PortRole, port: Int? = null) = wire(WireEdit("disconnect", from, to, role.wire, port))

    private fun wire(edit: WireEdit, pushUndo: Boolean = true) {
        val cur = _state.value.draft ?: return
        if (pushUndo) pushUndo(cur)
        val v = ++version
        graphJob?.cancel()
        _state.update { it.copy(canUndo = undoStack.isNotEmpty()) }
        scope.launch {
            when (val r = repo.wireDraft(cur, edit)) {
                is SopResult.Ok -> if (v == version) {
                    version++
                    _state.update { it.copy(draft = r.value.sop, graph = r.value.graph, dirty = true, toast = null) }
                }
                is SopResult.Error -> if (v == version) _state.update { it.copy(toast = r.message) }
                else -> if (v == version) _state.update { it.copy(toast = "Couldn't change the connection") }
            }
        }
    }

    // ── save / delete ───────────────────────────────────────────────────

    fun save(force: Boolean = false) {
        val s = _state.value
        val d = s.draft ?: return
        if (s.saving) return
        if (SopDraft.name(d).isBlank()) {
            _state.update { it.copy(error = "Give the workflow a name first") }
            return
        }
        _state.update { it.copy(saving = true, error = null) }
        scope.launch {
            val v = when (val r = repo.validate(d)) {
                is SopResult.Ok -> r.value
                else -> null
            }
            if (v != null && (v.blocking.isNotEmpty() || (v.warnings.isNotEmpty() && !force))) {
                _state.update { it.copy(saving = false, review = SaveReview(v.blocking, v.warnings)) }
                return@launch
            }
            val write = if (s.isNew) repo.create(d) else repo.save(d)
            _state.update {
                when (write) {
                    is SopResult.Ok -> it.copy(saving = false, isNew = false, dirty = it.draft != d, review = null, toast = "Saved")
                    is SopResult.Error -> it.copy(saving = false, review = null, error = write.message)
                    SopResult.Forbidden -> it.copy(saving = false, review = null, error = "Your sign-in can't edit workflows")
                    SopResult.Unavailable -> it.copy(saving = false, review = null, error = "The daemon can't save workflows right now")
                }
            }
        }
    }

    fun dismissReview() = _state.update { it.copy(review = null) }

    fun delete(onDone: () -> Unit) {
        val name = _state.value.draft?.let(SopDraft::name) ?: return
        if (_state.value.isNew) return onDone()
        scope.launch {
            when (val r = repo.delete(name)) {
                is SopResult.Ok -> onDone()
                is SopResult.Error -> _state.update { it.copy(error = r.message) }
                else -> _state.update { it.copy(error = "Couldn't delete this workflow") }
            }
        }
    }

    /** Saves a copy under [newName] (the daemon can't rename remotely) and reports the new name. */
    fun duplicateAs(newName: String, onDone: (String) -> Unit) {
        val d = _state.value.draft ?: return
        val n = newName.trim()
        if (n.isEmpty()) return
        scope.launch {
            when (val r = repo.create(SopDraft.set(d, "name", JsonPrimitive(n)))) {
                is SopResult.Ok -> onDone(n)
                is SopResult.Error -> _state.update { it.copy(error = r.message) }
                else -> _state.update { it.copy(error = "Couldn't create the copy") }
            }
        }
    }

    fun consumeToast() = _state.update { it.copy(toast = null) }
    fun consumeError() = _state.update { it.copy(error = null) }
    fun showToast(text: String) = _state.update { it.copy(toast = text) }

    // ── helpers ─────────────────────────────────────────────────────────

    private fun pushUndo(d: JsonObject) {
        undoStack.addLast(d to _state.value.graph)
        while (undoStack.size > UNDO_CAP) undoStack.removeFirst()
    }

    private fun scheduleGraph(d: JsonObject) {
        val v = version
        graphJob?.cancel()
        graphJob = scope.launch {
            delay(GRAPH_DEBOUNCE_MS)
            refreshGraph(d, v)
        }
    }

    private suspend fun refreshGraph(d: JsonObject, v: Long) {
        val r = repo.graphDraft(d)
        if (r is SopResult.Ok && v == version) _state.update { it.copy(graph = r.value) }
    }
}

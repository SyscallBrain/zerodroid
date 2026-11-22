package pt.tiago.zerodroid.ui.cron

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pt.tiago.zerodroid.data.ZeroDroidConnection
import pt.tiago.zerodroid.data.cron.CronDraft
import pt.tiago.zerodroid.data.cron.CronRepository
import pt.tiago.zerodroid.data.cron.CronResult
import pt.tiago.zerodroid.rpc.proto.AgentEntry
import pt.tiago.zerodroid.rpc.proto.CronJob
import pt.tiago.zerodroid.rpc.proto.CronPatchParams

const val NEEDS_UPDATE_AGENT = "The daemon needs the automation update to create agent jobs."
const val UNCONFIRMED_SAVE = "Couldn't confirm the job was saved. Check the list, then try again."

data class EditorState(
    val isNew: Boolean = true,
    val loading: Boolean = true,
    val draft: CronDraft = CronDraft(),
    val original: CronDraft = CronDraft(),
    /** The job being edited; null for a new one. */
    val job: CronJob? = null,
    val agents: List<AgentEntry> = emptyList(),
    val saving: Boolean = false,
    val problems: List<String> = emptyList(),
    val error: String? = null,
    /** Set once saved: the screen moves on to this job. */
    val savedId: String? = null,
) {
    val dirty: Boolean get() = draft.dirty(original)
    /** A failed load leaves nothing to save: the editor shows only the error. */
    val canSave: Boolean get() = !loading && !saving && (isNew || (job != null && dirty))
    /** Only cron-expression schedules can be edited; `at`/`every` ones show read-only. */
    val scheduleEditable: Boolean get() = job?.schedule?.kind?.let { it == "cron" } ?: true
}

class AutomationEditorViewModel(
    private val id: String?,
    private val repo: CronRepository = ZeroDroidConnection.crons,
    private val scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope
    private val _state = MutableStateFlow(EditorState(isNew = id == null))
    val state: StateFlow<EditorState> = _state.asStateFlow()
    private var loaded = false

    fun load() {
        if (loaded) return
        loaded = true
        scope.launch {
            val agents = (repo.agents() as? CronResult.Ok)?.value.orEmpty()
            if (id == null) {
                val d = CronDraft(agent = agents.firstOrNull { it.enabled }?.alias.orEmpty())
                _state.update { it.copy(loading = false, agents = agents, draft = d, original = d) }
                return@launch
            }
            when (val r = repo.get(id)) {
                is CronResult.Ok -> {
                    val d = CronDraft.from(r.value)
                    _state.update { it.copy(loading = false, agents = agents, job = r.value, draft = d, original = d) }
                }
                CronResult.Forbidden -> _state.update { it.copy(loading = false, agents = agents, error = "This device can't read this job.") }
                is CronResult.Lost -> _state.update { it.copy(loading = false, agents = agents, error = r.message) }
                is CronResult.Error -> _state.update { it.copy(loading = false, agents = agents, error = r.message) }
            }
        }
    }

    fun update(f: (CronDraft) -> CronDraft) = _state.update { it.copy(draft = f(it.draft), problems = emptyList()) }

    fun consumeError() = _state.update { it.copy(error = null) }

    fun save() {
        val s = _state.value
        if (s.saving) return
        // A job that failed to load has no original to patch against.
        if (!s.isNew && s.job == null) return
        val problems = s.draft.problems()
        if (problems.isNotEmpty()) return _state.update { it.copy(problems = problems) }
        _state.update { it.copy(saving = true, error = null) }
        scope.launch { if (id == null) add(s.draft) else patch(id, s.draft, s.original) }
    }

    private suspend fun add(d: CronDraft) {
        // The ids before the add: if its reply is lost, only a job that wasn't there before can be it.
        val before = (repo.list() as? CronResult.Ok)?.value?.map { it.id }?.toSet()
        when (val r = repo.add(d.toAdd())) {
            is CronResult.Ok ->
                if (d.agentJob && !r.value.isAgent) {
                    // An old daemon stores an agent request as an empty shell job: take it back.
                    repo.delete(r.value.id)
                    fail(NEEDS_UPDATE_AGENT)
                } else done(r.value.id)
            CronResult.Forbidden -> fail(if (d.agentJob) NEEDS_ADMIN_NOTE else "This device can't create jobs.")
            is CronResult.Error -> fail(r.message)
            is CronResult.Lost -> {
                // The add may have landed with its reply lost: look before offering a retry.
                val match = if (before == null) null
                else (repo.list() as? CronResult.Ok)?.value?.firstOrNull { it.id !in before && matches(it, d) }
                if (match != null) done(match.id) else fail(UNCONFIRMED_SAVE)
            }
        }
    }

    private suspend fun patch(id: String, d: CronDraft, original: CronDraft) {
        val p = d.toPatch(id, original) ?: return done(id)
        when (val r = repo.patch(p)) {
            is CronResult.Ok -> if (reflects(r.value, p)) done(id) else fail(NEEDS_UPDATE_CHANGE)
            CronResult.Forbidden -> fail(if (d.agentJob) NEEDS_ADMIN_NOTE else "This device can't edit this job.")
            is CronResult.Lost -> fail(r.message)
            is CronResult.Error -> fail(r.message)
        }
    }

    private fun done(id: String) = _state.update { it.copy(saving = false, savedId = id) }
    private fun fail(msg: String) = _state.update { it.copy(saving = false, error = msg) }

    private fun matches(j: CronJob, d: CronDraft): Boolean {
        val a = d.toAdd()
        return j.agentAlias == a.agent && j.name.orEmpty() == a.name.orEmpty() &&
            (j.schedule?.expr ?: j.expression) == a.schedule &&
            (if (d.agentJob) j.prompt == a.prompt else j.command == a.command)
    }

    /** An old daemon ignores fields it doesn't know: check the ones only the update can apply. */
    private fun reflects(j: CronJob, p: CronPatchParams): Boolean =
        (p.model == null || j.model == p.model) &&
            (p.clearModel != true || j.model == null) &&
            (p.delivery == null || sameDelivery(j.delivery, p.delivery)) &&
            (p.allowedTools == null || j.allowedTools == p.allowedTools) &&
            (p.clearAllowedTools != true || j.allowedTools == null) &&
            (p.sessionTarget == null || j.sessionTarget == p.sessionTarget) &&
            (p.usesMemory == null || j.usesMemory == p.usesMemory)

    private fun sameDelivery(a: pt.tiago.zerodroid.rpc.proto.CronDelivery, b: pt.tiago.zerodroid.rpc.proto.CronDelivery): Boolean =
        a.mode.equals(b.mode, ignoreCase = true) &&
            (!b.mode.equals("announce", ignoreCase = true) || (a.channel == b.channel && a.to == b.to && a.bestEffort == b.bestEffort))
}

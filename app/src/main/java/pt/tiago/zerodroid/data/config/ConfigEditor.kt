package pt.tiago.zerodroid.data.config

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pt.tiago.zerodroid.data.ChatSessions
import pt.tiago.zerodroid.rpc.proto.AgentDeletePreview
import pt.tiago.zerodroid.rpc.proto.AgentDeleteResult
import pt.tiago.zerodroid.rpc.proto.ApplyOutcome
import pt.tiago.zerodroid.rpc.proto.ConfigField
import pt.tiago.zerodroid.rpc.proto.ConfigSection
import pt.tiago.zerodroid.rpc.proto.ConfigTemplate
import pt.tiago.zerodroid.rpc.userMessage

const val SET_MANY_MAX = 256

data class ApplyReport(
    val result: ApplyResult,
    val outcomes: Map<String, ApplyOutcome> = emptyMap(),
    val conflicts: List<String> = emptyList(),
)

sealed interface ReloadGate {
    data object NotNeeded : ReloadGate
    data class Blocked(val running: Int) : ReloadGate
    data object Allowed : ReloadGate
}

data class SearchHit(val field: ConfigField, val sectionKey: String, val sectionLabel: String)

/** Where the draft survives process death (non-secret entries only). */
interface DraftPersistence {
    suspend fun load(): String?
    suspend fun save(json: String)

    object None : DraftPersistence {
        override suspend fun load(): String? = null
        override suspend fun save(json: String) = Unit
    }
}

/**
 * Process-level config editor state: sections, cached field lists, the staged draft,
 * apply results and structural edits. Screens observe it; it outlives them.
 */
class ConfigEditor(
    private val repo: ConfigRepository,
    private val chats: ChatSessions,
    private val scope: CoroutineScope,
    private val persistence: DraftPersistence = DraftPersistence.None,
) {
    private val _sections = MutableStateFlow<List<ConfigSection>>(emptyList())
    val sections: StateFlow<List<ConfigSection>> = _sections.asStateFlow()
    private val _readOnly = MutableStateFlow(false)
    val readOnly: StateFlow<Boolean> = _readOnly.asStateFlow()
    val draft = ConfigDraft()
    private val _applying = MutableStateFlow(false)
    val applying: StateFlow<Boolean> = _applying.asStateFlow()
    private val _lastApply = MutableStateFlow<ApplyReport?>(null)
    val lastApply: StateFlow<ApplyReport?> = _lastApply.asStateFlow()
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _structureVersion = MutableStateFlow(0)
    /** Bumps after every structural edit so lists of entries reload. */
    val structureVersion: StateFlow<Int> = _structureVersion.asStateFlow()

    private val fieldCache = mutableMapOf<String, List<ConfigField>>()
    private var index: List<ConfigField>? = null
    private var templatesCache: List<ConfigTemplate>? = null
    private val keysCache = mutableMapOf<String, List<String>>()
    private val aliasCache = mutableMapOf<String, List<String>>()
    private val lock = Mutex()
    private var restored = false

    init {
        scope.launch { draft.entries.drop(1).collect { runCatching { persistence.save(draft.persistable()) } } }
    }

    suspend fun loadSections() {
        runCatching { repo.sections() }
            .onSuccess { _sections.value = it; _error.value = null }
            .onFailure { _error.value = it.userMessage() }
        if (!restored) {
            restored = true
            val saved = runCatching { persistence.load() }.getOrNull()
            if (saved != null && draft.entries.value.isEmpty()) {
                // Without the index the saved draft cannot be matched to fields: keep it and try next time.
                val idx = runCatching { searchIndex() }.getOrNull()
                if (idx == null) {
                    restored = false
                } else {
                    draft.restore(saved) { p -> idx.firstOrNull { it.path == p } }
                }
            }
        }
    }

    suspend fun fields(prefix: String, refresh: Boolean = false): List<ConfigField> = lock.withLock {
        if (!refresh) fieldCache[prefix]?.let { return@withLock it }
        repo.fields(prefix).also { fieldCache[prefix] = it }
    }

    suspend fun searchIndex(refresh: Boolean = false): List<ConfigField> {
        if (!refresh) index?.let { return it }
        return repo.fields(null).also { index = it }
    }

    suspend fun templates(): List<ConfigTemplate> = templatesCache ?: repo.templates().also { templatesCache = it }

    suspend fun mapKeys(path: String, refresh: Boolean = false): List<String> {
        if (!refresh) keysCache[path]?.let { return it }
        return repo.mapKeys(path).also { keysCache[path] = it }
    }

    suspend fun aliasValues(source: String): List<String> = aliasCache[source] ?: repo.aliasValues(source).also { aliasCache[source] = it }

    private fun invalidate() {
        fieldCache.clear()
        keysCache.clear()
        aliasCache.clear()
        index = null
    }

    /**
     * Applies from the editor's own scope, so closing the review sheet cannot cancel a batch
     * the daemon may already be committing. A second call while one runs is ignored.
     */
    fun applyInBackground(confirmedConflicts: Set<String> = emptySet()) {
        if (_applying.value) return
        _applying.value = true
        scope.launch { apply(confirmedConflicts, alreadyMarked = true) }
    }

    suspend fun apply(confirmedConflicts: Set<String> = emptySet(), alreadyMarked: Boolean = false): ApplyReport {
        if (!alreadyMarked) {
            if (_applying.value) return _lastApply.value ?: ApplyReport(ApplyResult.Failed("Already applying"))
            _applying.value = true
        }
        try {
            return applyNow(confirmedConflicts)
        } finally {
            _applying.value = false
        }
    }

    private suspend fun applyNow(confirmedConflicts: Set<String>): ApplyReport {
        val sets = draft.toSetMany()
        if (sets.isEmpty()) return ApplyReport(ApplyResult.Ok(emptyList()))
        if (sets.size > SET_MANY_MAX) {
            return ApplyReport(ApplyResult.Failed("Too many changes at once (max $SET_MANY_MAX). Apply in parts.")).also { _lastApply.value = it }
        }
        val fresh = runCatching { repo.fields(null) }.getOrElse {
            return ApplyReport(ApplyResult.Failed(it.userMessage())).also { r -> _lastApply.value = r }
        }
        index = fresh
        val conflicts = draft.conflicts(fresh) - confirmedConflicts
        if (conflicts.isNotEmpty()) {
            return ApplyReport(ApplyResult.Failed("Some settings changed on the daemon since you edited them."), conflicts = conflicts)
                .also { _lastApply.value = it }
        }
        val result = repo.apply(sets)
        val report = when (result) {
            is ApplyResult.Ok -> {
                draft.clear()
                invalidate()
                _structureVersion.value++
                // config/status lists recent changes from every client; report only what this apply wrote.
                val applied = result.props.toSet()
                val outcomes = runCatching { repo.status() }.getOrDefault(emptyList())
                    .associate { it.path.joinToString(".") to it.outcome }
                    .filterKeys { it in applied }
                ApplyReport(result, outcomes)
            }
            ApplyResult.Forbidden -> {
                _readOnly.value = true
                ApplyReport(result)
            }
            else -> ApplyReport(result)
        }
        _lastApply.value = report
        return report
    }

    /** Refreshes outcomes after a reload/reconnect. */
    suspend fun refreshOutcomes() {
        val report = _lastApply.value ?: return
        val outcomes = runCatching { repo.status() }.getOrNull() ?: return
        _lastApply.value = report.copy(outcomes = report.outcomes + outcomes.associate { it.path.joinToString(".") to it.outcome })
    }

    fun dismissReport() { _lastApply.value = null }

    private suspend fun <T> structural(block: suspend () -> T): T = try {
        block().also {
            invalidate()
            _structureVersion.value++
        }
    } catch (e: ConfigForbiddenException) {
        _readOnly.value = true
        throw e
    }

    suspend fun addEntry(mapPath: String, key: String) = structural { repo.createKey(mapPath, key) }

    suspend fun renameEntry(mapPath: String, from: String, to: String) {
        check(draft.entries.value.none { it.path.startsWith("$mapPath.$from.") || it.path == "$mapPath.$from" }) {
            "Apply or discard the changes to $from before renaming it."
        }
        structural { repo.renameKey(mapPath, from, to) }
    }

    suspend fun deleteEntry(mapPath: String, key: String) {
        structural { repo.deleteKey(mapPath, key) }
        draft.unstageUnder("$mapPath.$key")
    }

    suspend fun clearSecret(path: String) {
        structural { repo.deleteProp(path) }
        draft.unstage(path)
    }

    suspend fun previewAgentDelete(alias: String): AgentDeletePreview = repo.agentDeletePreview(alias)

    suspend fun deleteAgent(alias: String): AgentDeleteResult =
        structural { repo.deleteAgent(alias) }.also { if (it.deleted) draft.unstageUnder("agents.$alias") }

    suspend fun reloadGate(): ReloadGate {
        val queued = _lastApply.value?.outcomes?.values?.any { it == ApplyOutcome.QueuedForReload } == true
        if (!queued) return ReloadGate.NotNeeded
        val running = chats.running.value.size + runCatching { repo.runningTurns() }.getOrDefault(0)
        return if (running > 0) ReloadGate.Blocked(running) else ReloadGate.Allowed
    }

    /** Returns an error message, or null when the daemon accepted the reload. */
    suspend fun reload(): String? = repo.reload()

    fun clear() {
        draft.clear()
        invalidate()
        templatesCache = null
        _sections.value = emptyList()
        _readOnly.value = false
        _lastApply.value = null
        restored = false
    }

    companion object {
        fun sectionFor(path: String, sections: List<ConfigSection>): ConfigSection? =
            sections.filter { path == it.key || path.startsWith(it.key + ".") }.maxByOrNull { it.key.length }

        fun search(q: String, index: List<ConfigField>, sections: List<ConfigSection>): List<SearchHit> {
            val needle = q.trim().lowercase()
            if (needle.isEmpty()) return emptyList()
            return index.asSequence().mapNotNull { f ->
                val path = f.path.lowercase()
                val rank = when {
                    path.split('.').any { it.startsWith(needle) } -> 0
                    needle in path -> 1
                    needle in f.description.lowercase() -> 2
                    !f.isSecret && f.value != null && needle in displayJson(f.value).lowercase() -> 3
                    else -> return@mapNotNull null
                }
                val s = sectionFor(f.path, sections)
                rank to SearchHit(f, s?.key ?: f.path.substringBefore('.'), s?.label ?: f.path.substringBefore('.'))
            }.sortedWith(compareBy({ it.first }, { it.second.field.path })).map { it.second }.take(50).toList()
        }

        /** A rejection the review sheet cannot pin on one of the drafted entries is shown as a banner. */
        fun rejectionBanner(result: ApplyResult?, draftPaths: Set<String>): String? =
            (result as? ApplyResult.Rejected)?.takeIf { it.prop == null || it.prop !in draftPaths }?.reason

        fun validateAlias(name: String, existing: List<String>): String? = when {
            name.isBlank() -> "Give it a name"
            name.any { it.isWhitespace() } -> "No spaces"
            '.' in name -> "No dots"
            name in existing -> "$name already exists"
            else -> null
        }
    }
}

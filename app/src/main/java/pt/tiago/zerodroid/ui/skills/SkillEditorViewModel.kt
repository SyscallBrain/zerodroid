package pt.tiago.zerodroid.ui.skills

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pt.tiago.zerodroid.data.ZeroDroidConnection
import pt.tiago.zerodroid.data.skills.SkillResult
import pt.tiago.zerodroid.data.skills.SkillsRepository
import pt.tiago.zerodroid.rpc.proto.SkillFrontmatter
import pt.tiago.zerodroid.rpc.proto.SkillSlashOption

data class SkillEditorState(
    val bundle: String,
    val name: String,
    val loading: Boolean = true,
    val description: String = "",
    val license: String = "",
    val author: String = "",
    val version: String = "",
    val category: String = "",
    val tags: String = "",
    val always: Boolean = false,
    val body: String = "",
    val saving: Boolean = false,
    val deleting: Boolean = false,
    val saved: Boolean = false,
    val deleted: Boolean = false,
    val error: String? = null,
    val forbidden: Boolean = false,
    /** Frontmatter fields the editor doesn't touch (slash options) are preserved on save. */
    val originalSlashOptions: List<SkillSlashOption> = emptyList(),
    /** Snapshot of the editable fields as loaded/last saved, for the dirty check. */
    val originalSnapshot: String = "",
) {
    val dirty: Boolean
        get() = !loading && !deleted && snapshot() != originalSnapshot

    fun snapshot(): String = listOf(description, license, author, version, category, tags, always.toString(), body).joinToString("\u0001")

    fun toFrontmatter(): SkillFrontmatter = SkillFrontmatter(
        name = name,
        description = description,
        license = license.trim().ifEmpty { null },
        author = author.trim().ifEmpty { null },
        version = version.trim().ifEmpty { null },
        category = category.trim().ifEmpty { null },
        tags = tags.split(',').map { it.trim() }.filter { it.isNotEmpty() },
        always = always,
        slashOptions = originalSlashOptions,
    )
}

class SkillEditorViewModel(
    private val bundle: String,
    private val name: String,
    private val repo: SkillsRepository = ZeroDroidConnection.skills,
    private val scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope
    private val _state = MutableStateFlow(SkillEditorState(bundle, name))
    val state: StateFlow<SkillEditorState> = _state.asStateFlow()

    fun load() {
        scope.launch {
            when (val r = repo.read(bundle, name)) {
                is SkillResult.Ok -> {
                    val f = r.value.frontmatter
                    val loaded = SkillEditorState(
                        bundle = bundle, name = name, loading = false,
                        description = f.description,
                        license = f.license ?: "",
                        author = f.author ?: "",
                        version = f.version ?: "",
                        category = f.category ?: "",
                        tags = f.tags.joinToString(", "),
                        always = f.always,
                        body = r.value.body,
                        originalSlashOptions = f.slashOptions,
                    )
                    _state.value = loaded.copy(originalSnapshot = loaded.snapshot())
                }
                SkillResult.Forbidden -> _state.update { it.copy(loading = false, forbidden = true, error = "This device can't read skills.") }
                is SkillResult.Lost -> _state.update { it.copy(loading = false, error = r.message) }
                is SkillResult.Error -> _state.update { it.copy(loading = false, error = r.message) }
            }
        }
    }

    fun update(f: (SkillEditorState) -> SkillEditorState) = _state.update(f)

    fun save() {
        val s = _state.value
        if (s.saving || s.deleted) return
        _state.update { it.copy(saving = true, error = null) }
        scope.launch {
            when (val r = repo.write(bundle, name, s.toFrontmatter(), s.body)) {
                is SkillResult.Ok -> _state.update { it.copy(saving = false, saved = true, originalSnapshot = it.snapshot()) }
                SkillResult.Forbidden -> _state.update { it.copy(saving = false, forbidden = true, error = "This device can't edit skills.") }
                is SkillResult.Lost -> _state.update { it.copy(saving = false, error = r.message + " The save may still have landed.") }
                is SkillResult.Error -> _state.update { it.copy(saving = false, error = r.message) }
            }
        }
    }

    fun delete() {
        val s = _state.value
        if (s.deleting || s.deleted) return
        _state.update { it.copy(deleting = true, error = null) }
        scope.launch {
            when (val r = repo.delete(bundle, name)) {
                is SkillResult.Ok -> _state.update { it.copy(deleting = false, deleted = true) }
                SkillResult.Forbidden -> _state.update { it.copy(deleting = false, forbidden = true, error = "This device can't delete skills.") }
                is SkillResult.Lost -> _state.update { it.copy(deleting = false, error = r.message + " The delete may still have landed.") }
                is SkillResult.Error -> _state.update { it.copy(deleting = false, error = r.message) }
            }
        }
    }

    fun consumeError() = _state.update { it.copy(error = null) }
}

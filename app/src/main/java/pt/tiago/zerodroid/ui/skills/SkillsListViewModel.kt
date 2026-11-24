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
import pt.tiago.zerodroid.data.skills.AgentSkill
import pt.tiago.zerodroid.data.skills.SkillResult
import pt.tiago.zerodroid.data.skills.SkillsRepository
import pt.tiago.zerodroid.rpc.proto.AgentEntry

data class SkillsListState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val agents: List<AgentEntry> = emptyList(),
    val selectedAgent: String? = null,
    /** Bundle skills of the selected agent, one entry per skill. */
    val bundleSkills: List<AgentSkill> = emptyList(),
    /** Workspace skills of the selected agent (read-only, no content over RPC). */
    val workspaceSkills: List<AgentSkill> = emptyList(),
    /** Bundle aliases assigned to the selected agent (`agents.<alias>.skill_bundles`). */
    val agentBundles: List<String> = emptyList(),
    /** Bundle aliases configured on the daemon (`skills/bundles`). */
    val daemonBundles: List<String> = emptyList(),
    val error: String? = null,
    val forbidden: Boolean = false,
) {
    val skills: List<AgentSkill> get() = bundleSkills + workspaceSkills
}

class SkillsListViewModel(
    private val repo: SkillsRepository = ZeroDroidConnection.skills,
    private val scopeOverride: CoroutineScope? = null,
) : ViewModel() {
    private val scope: CoroutineScope get() = scopeOverride ?: viewModelScope
    private val _state = MutableStateFlow(SkillsListState())
    val state: StateFlow<SkillsListState> = _state.asStateFlow()

    /** Loads the agent list, then the selected agent's skills. */
    fun refresh() {
        scope.launch { doRefresh() }
    }

    fun selectAgent(alias: String) {
        if (_state.value.selectedAgent == alias) return
        _state.update { it.copy(selectedAgent = alias, error = null) }
        scope.launch { loadSkills() }
    }

    fun pullToRefresh() {
        scope.launch {
            _state.update { it.copy(refreshing = true) }
            doRefresh()
            _state.update { it.copy(refreshing = false) }
        }
    }

    private suspend fun doRefresh() {
        val agents = when (val r = repo.agents()) {
            is SkillResult.Ok -> r.value
            else -> emptyList()
        }
        _state.update { s ->
            val selected = s.selectedAgent?.takeIf { a -> agents.any { it.alias == a } } ?: agents.firstOrNull()?.alias
            s.copy(agents = agents, selectedAgent = selected)
        }
        loadSkills()
    }

    private suspend fun loadSkills() {
        val alias = _state.value.selectedAgent ?: run {
            _state.update { it.copy(loading = false, bundleSkills = emptyList(), workspaceSkills = emptyList()) }
            return
        }
        _state.update { it.copy(loading = true, error = null, forbidden = false) }
        val daemonBundles = when (val r = repo.bundles()) {
            is SkillResult.Ok -> r.value.map { it.alias }.sorted()
            else -> emptyList()
        }
        val bundleAliases = when (val r = repo.agentSkillBundles(alias)) {
            is SkillResult.Ok -> r.value
            is SkillResult.Forbidden -> {
                _state.update { it.copy(loading = false, forbidden = true, bundleSkills = emptyList(), agentBundles = emptyList(), daemonBundles = daemonBundles) }
                null
            }
            is SkillResult.Lost -> { _state.update { it.copy(loading = false, error = r.message, daemonBundles = daemonBundles) }; null }
            is SkillResult.Error -> { _state.update { it.copy(loading = false, error = r.message, daemonBundles = daemonBundles) }; null }
        }
        var bundleSkills = emptyList<AgentSkill>()
        var error: String? = _state.value.error
        var forbidden = _state.value.forbidden
        if (bundleAliases != null) {
            when (val r = repo.list()) {
                is SkillResult.Ok -> bundleSkills = r.value
                    .filter { it.bundle in bundleAliases }
                    .map { AgentSkill(it.name, it.frontmatter.description, it.bundle, "bundle", it.directory, it.frontmatter) }
                    .sortedWith(compareBy({ it.bundle }, { it.name }))
                is SkillResult.Forbidden -> { forbidden = true; error = null }
                is SkillResult.Lost -> error = r.message
                is SkillResult.Error -> error = r.message
            }
        }
        val workspace = repo.workspaceSkills(alias)
            .sorted()
            .map { AgentSkill(it, "", null, "workspace") }
        _state.update {
            it.copy(
                loading = false, bundleSkills = bundleSkills, workspaceSkills = workspace,
                agentBundles = bundleAliases ?: emptyList(), daemonBundles = daemonBundles,
                error = error, forbidden = forbidden,
            )
        }
    }

    fun consumeError() = _state.update { it.copy(error = null) }
}

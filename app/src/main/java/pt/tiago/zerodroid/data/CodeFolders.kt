package pt.tiago.zerodroid.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/** What [CodeFolders] keeps on the phone. */
data class CodeFolderState(
    /** sessionId → the folder the Code session is rooted at. */
    val bySession: Map<String, String> = emptyMap(),
    /** Most recent first. */
    val recent: List<String> = emptyList(),
)

interface CodeFolderPersistence {
    suspend fun load(): CodeFolderState
    suspend fun save(s: CodeFolderState)
}

private val CODE_FOLDERS = stringPreferencesKey("code_folders")
private val CODE_RECENT = stringPreferencesKey("code_recent")

class DataStoreCodeFolderPersistence(private val context: Context) : CodeFolderPersistence {
    override suspend fun load(): CodeFolderState {
        val prefs = context.uiStore.data.first()
        val map = prefs[CODE_FOLDERS].orEmpty().lineSequence()
            .mapNotNull { line -> line.split('\t', limit = 2).takeIf { it.size == 2 && it[0].isNotBlank() } }
            .associate { it[0] to it[1] }
        val recent = prefs[CODE_RECENT].orEmpty().lines().filter { it.isNotBlank() }
        return CodeFolderState(map, recent)
    }

    override suspend fun save(s: CodeFolderState) {
        context.uiStore.edit {
            it[CODE_FOLDERS] = s.bySession.entries.joinToString("\n") { (k, v) -> "$k\t$v" }
            it[CODE_RECENT] = s.recent.joinToString("\n")
        }
    }
}

/**
 * The folder of each Code session, kept on the phone: `session/list-acp` does not
 * report it, only `session/new` does. Also remembers recently used folders.
 */
class CodeFolders(private val persistence: CodeFolderPersistence, private val maxRecent: Int = 8) {
    private val _state = MutableStateFlow(CodeFolderState())
    val state: StateFlow<CodeFolderState> = _state.asStateFlow()

    suspend fun load() {
        _state.value = runCatching { persistence.load() }.getOrDefault(CodeFolderState())
    }

    fun folderOf(sessionId: String): String? = _state.value.bySession[sessionId]

    suspend fun remember(sessionId: String, folder: String) {
        if (folder.isBlank()) return
        val s = _state.value
        val next = s.copy(
            bySession = s.bySession + (sessionId to folder),
            recent = (listOf(folder) + s.recent.filterNot { it == folder }).take(maxRecent),
        )
        if (next == s) return
        _state.value = next
        runCatching { persistence.save(next) }
    }

    suspend fun forget(sessionId: String) {
        val s = _state.value
        if (sessionId !in s.bySession) return
        val next = s.copy(bySession = s.bySession - sessionId)
        _state.value = next
        runCatching { persistence.save(next) }
    }
}

/** Last path segment, for titles: `/data/x/proj/` → `proj`; `/` stays `/`. */
fun folderName(path: String): String = path.trimEnd('/').substringAfterLast('/').ifEmpty { "/" }

/** The parent of an absolute path, or null at the root. */
fun parentFolder(path: String): String? {
    val trimmed = path.trimEnd('/')
    if (trimmed.isEmpty()) return null
    val parent = trimmed.substringBeforeLast('/', "")
    return parent.ifEmpty { "/" }
}

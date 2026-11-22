package pt.tiago.zerodroid.data.canvas

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import pt.tiago.zerodroid.rpc.ZdJson
import java.io.File
import java.security.MessageDigest

private const val MAX_FRAMES = 50
/** Frame ids never to add again: deleted, evicted by [MAX_FRAMES] or failed. Kept newest last. */
private const val TOMBSTONES = "deleted.ids"
private const val MAX_TOMBSTONES = 20_000

/** Receives canvas frames seen in chats. */
fun interface CanvasSink {
    fun onCapture(sessionId: String, agentAlias: String, capture: CanvasCapture)

    /** A page of history at once, so the sink can write each canvas once. */
    fun onCaptures(sessionId: String, agentAlias: String, captures: List<CanvasCapture>) =
        captures.forEach { onCapture(sessionId, agentAlias, it) }
}

@Serializable
data class StoredFrame(
    val id: String,
    val contentType: String,
    val content: String,
    val at: Long,
    val sessionId: String,
    val agentAlias: String,
    val toolCallId: String,
)

@Serializable
data class CanvasSummary(
    val canvasId: String,
    val name: String,
    val updatedAt: Long,
    val frameCount: Int,
    val latestType: String,
    val agentAlias: String,
    val sessionId: String,
)

@Serializable
private data class CanvasFile(val canvasId: String, val name: String, val frames: List<StoredFrame>)

/**
 * Canvas frames drawn in this phone's chats, kept on the phone: the daemon holds canvases in
 * memory only, and the ones drawn from RPC sessions are not even reachable through its gateway.
 * One JSON file per canvas under a directory per daemon. Captures are applied in arrival order.
 */
class CanvasArchive(
    private val root: File,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
) : CanvasSink {
    private val lock = Mutex()
    private val queue = Channel<suspend () -> Unit>(Channel.UNLIMITED)
    @Volatile private var dir: File? = null
    private val _canvases = MutableStateFlow<List<CanvasSummary>>(emptyList())
    /** The bound daemon's canvases, most recently updated first. */
    val canvases: StateFlow<List<CanvasSummary>> = _canvases.asStateFlow()
    /** frame id ("session/toolCall") → canvas id, for the bound daemon. */
    @Volatile private var index: Map<String, String> = emptyMap()
    private val summaries = mutableMapOf<String, CanvasSummary>()
    private val tombstones = LinkedHashSet<String>()

    init {
        // One failed write (disk full, unreadable dir) must neither crash the app nor stop later captures.
        scope.launch {
            for (op in queue) {
                try {
                    op()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {
                }
            }
        }
    }

    /** Switches to [daemonKey]'s canvases, after every capture already queued for the previous one. */
    suspend fun bind(daemonKey: String) = enqueue {
        val d = dirFor(daemonKey)
        if (d == dir) return@enqueue
        dir = d
        summaries.clear()
        tombstones.clear()
        runCatching { File(d, TOMBSTONES).takeIf { it.isFile }?.readLines()?.filterTo(tombstones) { it.isNotBlank() } }
        val idx = mutableMapOf<String, String>()
        d.listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().forEach { f ->
            val c = read(f) ?: return@forEach
            if (c.frames.isEmpty()) return@forEach
            summaries[c.canvasId] = summaryOf(c)
            c.frames.forEach { idx[it.id] = c.canvasId }
        }
        index = idx
        publish()
    }

    override fun onCapture(sessionId: String, agentAlias: String, capture: CanvasCapture) =
        onCaptures(sessionId, agentAlias, listOf(capture))

    override fun onCaptures(sessionId: String, agentAlias: String, captures: List<CanvasCapture>) {
        if (captures.isNotEmpty()) queue.trySend { io { apply(sessionId, agentAlias, captures) } }
    }

    /** Canvas files written so far (tests check that a history page costs one write per canvas). */
    internal var saveCount = 0
        private set

    /**
     * The daemon's key changed (a new CA chain on an OIDC renewal): move its canvases to [to]
     * unless [to] already has some. Bind [to] afterwards.
     */
    suspend fun migrate(from: String, to: String) = enqueue {
        val src = dirFor(from)
        val dst = dirFor(to)
        if (src == dst || !src.isDirectory || dst.exists()) return@enqueue
        if (src.renameTo(dst) && dir == src) dir = dst
    }

    /** Runs [block] on the worker, after everything queued before it. */
    private suspend fun <T> enqueue(block: suspend () -> T): T {
        val done = CompletableDeferred<T>()
        queue.send {
            try {
                done.complete(io(block))
            } catch (e: Throwable) {
                done.completeExceptionally(e)
            }
        }
        return done.await()
    }

    /** Waits until every capture sent so far is written. */
    suspend fun awaitIdle() {
        val done = CompletableDeferred<Unit>()
        queue.send { done.complete(Unit) }
        done.await()
    }

    /** Frames of [canvasId], oldest first. */
    suspend fun frames(canvasId: String): List<StoredFrame> = io { load(canvasId)?.frames.orEmpty() }

    /** Where the frame drawn by [toolCallId] in [sessionId] is: (canvas id, frame id). */
    fun locate(sessionId: String, toolCallId: String): Pair<String, String>? {
        val id = frameId(sessionId, toolCallId)
        return index[id]?.let { it to id }
    }

    /** Canvases with a frame drawn in [sessionId], most recently updated first. */
    fun canvasesFor(sessionId: String): List<String> {
        val prefix = "$sessionId/"
        val ids = index.entries.filter { it.key.startsWith(prefix) }.mapTo(HashSet()) { it.value }
        return canvases.value.map { it.canvasId }.filter { it in ids }
    }

    suspend fun rename(canvasId: String, name: String) = io {
        val c = load(canvasId) ?: return@io
        runCatching { save(c.copy(name = name.trim().ifEmpty { canvasId })) }
        publish()
    }

    /** Removes the canvas; its frames are not added again when their chat is reloaded. */
    suspend fun delete(canvasId: String) = io {
        val d = dir ?: return@io
        bury(index.filterValues { it == canvasId }.keys)
        File(d, fileName(canvasId)).delete()
        summaries.remove(canvasId)
        index = index.filterValues { it != canvasId }
        publish()
    }

    /** Applies [captures] in order, writing each canvas they touch once. */
    private fun apply(sessionId: String, agentAlias: String, captures: List<CanvasCapture>) {
        val d = dir ?: return
        val open = mutableMapOf<String, CanvasFile?>()
        val dirty = mutableSetOf<String>()
        val idx = index.toMutableMap()
        val buried = mutableListOf<String>()
        fun file(canvasId: String) = open.getOrPut(canvasId) { load(canvasId) }
        for (capture in captures) {
            val id = frameId(sessionId, capture.toolCallId)
            when (capture) {
                is CanvasCapture.Render -> {
                    if (id in idx || id in tombstones || id in buried) continue
                    val r = capture.render
                    val old = file(r.canvasId) ?: CanvasFile(r.canvasId, r.canvasId, emptyList())
                    val frame = StoredFrame(id, r.contentType, r.content, clock(), sessionId, agentAlias, capture.toolCallId)
                    val frames = (old.frames + frame).takeLast(MAX_FRAMES)
                    val kept = frames.mapTo(HashSet()) { it.id }
                    old.frames.filter { it.id !in kept }.forEach { idx.remove(it.id); buried += it.id }
                    open[r.canvasId] = old.copy(frames = frames)
                    idx[id] = r.canvasId
                    dirty += r.canvasId
                }
                is CanvasCapture.Failed -> {
                    buried += id
                    val canvasId = idx.remove(id) ?: continue
                    val old = file(canvasId) ?: continue
                    open[canvasId] = old.copy(frames = old.frames.filter { it.id != id })
                    dirty += canvasId
                }
            }
        }
        for (canvasId in dirty) {
            val c = open[canvasId] ?: continue
            if (c.frames.isEmpty()) {
                File(d, fileName(canvasId)).delete()
                summaries.remove(canvasId)
            } else {
                save(c)
            }
        }
        index = idx
        bury(buried)
        if (dirty.isNotEmpty()) publish()
    }

    private fun bury(ids: Collection<String>) {
        if (ids.isEmpty() || !tombstones.addAll(ids)) return
        while (tombstones.size > MAX_TOMBSTONES) tombstones.remove(tombstones.first())
        val d = dir ?: return
        runCatching {
            d.mkdirs()
            val tmp = File(d, "$TOMBSTONES.tmp")
            tmp.writeText(tombstones.joinToString("\n"))
            if (!tmp.renameTo(File(d, TOMBSTONES))) {
                File(d, TOMBSTONES).delete()
                tmp.renameTo(File(d, TOMBSTONES))
            }
        }
    }

    private fun load(canvasId: String): CanvasFile? = dir?.let { read(File(it, fileName(canvasId))) }

    private fun read(f: File): CanvasFile? =
        if (!f.isFile) null else runCatching { ZdJson.decodeFromString(CanvasFile.serializer(), f.readText()) }.getOrNull()

    private fun save(c: CanvasFile) {
        val d = dir ?: return
        d.mkdirs()
        val target = File(d, fileName(c.canvasId))
        val tmp = File(d, target.name + ".tmp")
        tmp.writeText(ZdJson.encodeToString(CanvasFile.serializer(), c))
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
        summaries[c.canvasId] = summaryOf(c)
        saveCount++
    }

    private fun publish() {
        _canvases.value = summaries.values.sortedByDescending { it.updatedAt }
    }

    private fun dirFor(daemonKey: String) = File(root, sha256(daemonKey).take(16))

    private suspend fun <T> io(block: suspend () -> T): T = withContext(Dispatchers.IO) { lock.withLock { block() } }

    private companion object {
        fun frameId(sessionId: String, toolCallId: String) = "$sessionId/$toolCallId"

        /** URL-safe Base64 of the id; a hash when that would pass the file system's name limit. */
        fun fileName(canvasId: String): String {
            val b64 = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(canvasId.toByteArray(Charsets.UTF_8))
            return (if (b64.length <= 200) b64 else "h-" + sha256(canvasId)) + ".json"
        }

        fun sha256(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

        fun summaryOf(c: CanvasFile): CanvasSummary {
            val last = c.frames.last()
            return CanvasSummary(c.canvasId, c.name, last.at, c.frames.size, last.contentType, last.agentAlias, last.sessionId)
        }
    }
}

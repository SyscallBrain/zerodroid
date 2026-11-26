package pt.tiago.zerodroid.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import pt.tiago.zerodroid.rpc.proto.MessageEntry
import pt.tiago.zerodroid.rpc.proto.SessionUpdate

enum class ToolStatus { Running, Ok, Failed }
enum class ApprovalStatus { Pending, Approved, AlwaysApproved, Denied, Expired, ConnectionLost }
enum class NoticeKind { BudgetWarning, HistoryTrimmed, TurnCancelled, TurnFailed, Error }

/** One row of the transcript. [key] is unique within a [ChatState] and stable across updates. */
sealed interface ChatItem {
    val key: String

    /** [images] are SHA-256 digests of images sent with the message (see `ImageCache`). */
    data class User(override val key: String, val text: String, val steer: Boolean = false, val images: List<String> = emptyList()) : ChatItem

    data class Agent(override val key: String, val text: String, val streaming: Boolean = false) : ChatItem

    data class Thought(override val key: String, val text: String, val streaming: Boolean = false) : ChatItem

    data class Tool(
        override val key: String,
        val callId: String,
        val name: String,
        val args: String,
        val status: ToolStatus,
        val output: String?,
        val startedAt: Long,
        val durationMs: Long? = null,
        /** A file edit or write, kept whole (not cut like [args]) so the card can show a diff. */
        val edit: FileEdit? = null,
    ) : ChatItem

    data class Approval(
        override val key: String,
        val requestId: String,
        val toolName: String,
        val summary: String,
        val deadlineAt: Long,
        val status: ApprovalStatus,
        val answeredAt: Long? = null,
    ) : ChatItem

    /** A question the agent asked via `elicitation/create` (e.g. `ask_user` with choices). */
    data class Question(
        override val key: String,
        val requestId: String,
        val message: String,
        /** Option labels, in order. */
        val options: List<String>,
        /** Wire values ("choice-N") paired with [options], sent back on accept. */
        val consts: List<String>,
        val multi: Boolean,
        val status: QuestionStatus,
        /** Chosen option labels, once answered. */
        val answer: List<String> = emptyList(),
        val answeredAt: Long? = null,
    ) : ChatItem

    data class Notice(
        override val key: String,
        val kind: NoticeKind,
        val text: String,
        val retry: Boolean = false,
    ) : ChatItem
}

data class ChatState(
    val items: List<ChatItem> = emptyList(),
    val turnRunning: Boolean = false,
    val contextUsed: Long? = null,
    val contextMax: Long? = null,
    val lastUserPrompt: String? = null,
    val hasOlder: Boolean = false,
    /** Index of the oldest loaded message in the daemon's history, for `before_index` paging. */
    val historyStart: Int = 0,
    /** Monotonic counter for item keys. */
    val seq: Long = 0,
)

const val MAX_TOOL_OUTPUT = 65_536
private const val TRUNCATED = "… (truncated)"

fun truncateForDisplay(s: String): String =
    if (s.length <= MAX_TOOL_OUTPUT) s else s.take(MAX_TOOL_OUTPUT - TRUNCATED.length) + TRUNCATED

/** What the transcript renders: with the reasoning pref off, Thought rows drop out. */
fun visibleItems(items: List<ChatItem>, showReasoning: Boolean): List<ChatItem> =
    if (showReasoning) items else items.filterNot { it is ChatItem.Thought }

/** Pretty, compact rendering of tool arguments: a lone string arg is shown bare. */
fun argsPreview(raw: JsonElement?): String = when (raw) {
    null, JsonNull -> ""
    is JsonPrimitive -> raw.content
    is JsonObject -> if (raw.size == 1 && raw.values.first() is JsonPrimitive) {
        (raw.values.first() as JsonPrimitive).content
    } else {
        raw.entries.joinToString("\n") { (k, v) -> "$k: ${if (v is JsonPrimitive) v.content else v.toString()}" }
    }
    else -> raw.toString()
}.let(::truncateForDisplay)

private fun ChatState.nextKey(prefix: String): Pair<String, ChatState> = "$prefix$seq" to copy(seq = seq + 1)

private fun ChatState.append(prefix: String, make: (String) -> ChatItem): ChatState {
    val (key, s) = nextKey(prefix)
    return s.copy(items = s.items + make(key))
}

private fun ChatState.closeStreams(): ChatState = copy(items = items.map {
    when (it) {
        is ChatItem.Agent -> if (it.streaming) it.copy(streaming = false) else it
        is ChatItem.Thought -> if (it.streaming) it.copy(streaming = false) else it
        else -> it
    }
})

fun localPrompt(state: ChatState, text: String, images: List<String> = emptyList()): ChatState {
    val steer = state.turnRunning
    // An image prompt is not retried: its text alone would reach the model without the images.
    return state.append("u") { ChatItem.User(it, text, steer, images) }
        .copy(lastUserPrompt = if (steer) state.lastUserPrompt else text.takeIf { images.isEmpty() }, turnRunning = true)
}

fun addNotice(state: ChatState, kind: NoticeKind, text: String, retry: Boolean = false): ChatState =
    state.append("n") { ChatItem.Notice(it, kind, text, retry) }

fun connectionLost(state: ChatState): ChatState {
    val hadWork = state.turnRunning ||
        state.items.any { it is ChatItem.Approval && it.status == ApprovalStatus.Pending } ||
        state.items.any { it is ChatItem.Question && it.status == QuestionStatus.Pending }
    val items = state.items.map { item ->
        when (item) {
            is ChatItem.Approval -> if (item.status == ApprovalStatus.Pending) item.copy(status = ApprovalStatus.ConnectionLost) else item
            is ChatItem.Question -> if (item.status == QuestionStatus.Pending) item.copy(status = QuestionStatus.ConnectionLost) else item
            is ChatItem.Tool -> if (item.status == ToolStatus.Running) item.copy(status = ToolStatus.Failed) else item
            else -> item
        }
    }
    val base = state.copy(items = items, turnRunning = false).closeStreams()
    return if (hadWork) addNotice(base, NoticeKind.TurnCancelled, "Turn cancelled: connection lost", retry = true) else base
}

fun setApprovalStatus(state: ChatState, requestId: String, status: ApprovalStatus, now: Long): ChatState =
    state.copy(items = state.items.map {
        if (it is ChatItem.Approval && it.requestId == requestId && it.status == ApprovalStatus.Pending) {
            it.copy(status = status, answeredAt = now)
        } else it
    })

/** Appends a question card for a daemon `elicitation/create` request, unless already present. */
fun questionReceived(state: ChatState, q: PendingQuestion): ChatState {
    if (state.items.any { it is ChatItem.Question && it.requestId == q.requestId }) return state
    return state.closeStreams().append("q") {
        ChatItem.Question(it, q.requestId, q.message, q.options.map { o -> o.title }, q.options.map { o -> o.const }, q.multi, q.status, q.answer, q.answeredAt)
    }.copy(turnRunning = true)
}

fun setQuestionStatus(state: ChatState, requestId: String, status: QuestionStatus, answer: List<String>, now: Long): ChatState =
    state.copy(items = state.items.map {
        if (it is ChatItem.Question && it.requestId == requestId && it.status == QuestionStatus.Pending) {
            it.copy(status = status, answer = answer, answeredAt = now)
        } else it
    })

fun reduce(state: ChatState, event: SessionUpdate, now: Long): ChatState = when (event) {
    is SessionUpdate.AgentMessageChunk -> {
        val last = state.items.lastOrNull()
        if (last is ChatItem.Agent && last.streaming) {
            state.copy(items = state.items.dropLast(1) + last.copy(text = last.text + event.text), turnRunning = true)
        } else {
            state.closeStreams().append("a") { ChatItem.Agent(it, event.text, streaming = true) }.copy(turnRunning = true)
        }
    }

    is SessionUpdate.AgentThoughtChunk -> {
        val last = state.items.lastOrNull()
        if (last is ChatItem.Thought && last.streaming) {
            state.copy(items = state.items.dropLast(1) + last.copy(text = last.text + event.text), turnRunning = true)
        } else {
            state.closeStreams().append("t") { ChatItem.Thought(it, event.text, streaming = true) }.copy(turnRunning = true)
        }
    }

    is SessionUpdate.ToolCall -> state.closeStreams().append("c") {
        ChatItem.Tool(it, event.toolCallId, event.name, argsPreview(event.rawInput), ToolStatus.Running, null, now, edit = fileEditOf(event.name, event.rawInput))
    }.copy(turnRunning = true)

    is SessionUpdate.ToolResult -> {
        val existing = state.items.lastOrNull { it is ChatItem.Tool && it.callId == event.toolCallId } as ChatItem.Tool?
        val failed = event.rawOutput.startsWith("Error", ignoreCase = true)
        if (existing != null) {
            val done = existing.copy(
                status = if (failed) ToolStatus.Failed else ToolStatus.Ok,
                output = truncateForDisplay(event.rawOutput),
                durationMs = (now - existing.startedAt).coerceAtLeast(0),
            )
            state.copy(items = state.items.map { if (it.key == existing.key) done else it })
        } else {
            state.append("c") {
                ChatItem.Tool(
                    it, event.toolCallId, event.name, "",
                    if (failed) ToolStatus.Failed else ToolStatus.Ok,
                    truncateForDisplay(event.rawOutput), now,
                )
            }
        }
    }

    is SessionUpdate.ApprovalRequest -> {
        if (state.items.any { it is ChatItem.Approval && it.requestId == event.requestId }) state
        else state.closeStreams().append("p") {
            ChatItem.Approval(
                it, event.requestId, event.toolName, event.argumentsSummary,
                now + event.timeoutSecs * 1_000L, ApprovalStatus.Pending,
            )
        }
    }

    is SessionUpdate.ContextUsage -> state.copy(
        contextUsed = event.inputTokens ?: state.contextUsed,
        contextMax = event.maxContextTokens ?: state.contextMax,
    )

    is SessionUpdate.BudgetWarning -> addNotice(
        state, NoticeKind.BudgetWarning,
        "Budget warning: $%.2f of $%.2f (%s)".format(event.currentUsd, event.limitUsd, event.period),
    )

    is SessionUpdate.HistoryTrimmed -> addNotice(
        state, NoticeKind.HistoryTrimmed,
        "Older context trimmed: ${event.droppedMessages} messages",
    )

    is SessionUpdate.TurnComplete -> {
        var s = state.closeStreams().copy(turnRunning = false)
        // A turn whose text never streamed (e.g. a non-streaming provider) still shows its reply.
        val lastUser = s.items.indexOfLast { it is ChatItem.User && !it.steer }
        val streamedReply = s.items.drop(lastUser + 1).any { it is ChatItem.Agent }
        if (!streamedReply && event.content.isNotBlank() && event.outcome != "failed") {
            s = s.append("a") { ChatItem.Agent(it, event.content) }
        }
        s = s.copy(items = s.items.map {
            if (it is ChatItem.Tool && it.status == ToolStatus.Running) it.copy(status = ToolStatus.Failed) else it
        })
        when (event.outcome) {
            "cancelled" -> addNotice(s, NoticeKind.TurnCancelled, "Turn stopped", retry = true)
            "failed" -> addNotice(
                s, NoticeKind.TurnFailed,
                "Turn failed" + event.content.takeIf { it.isNotBlank() }?.let { ": ${it.take(400)}" }.orEmpty(),
                retry = true,
            )
            else -> s
        }
    }

    is SessionUpdate.Plan, is SessionUpdate.Unknown -> state
}

/**
 * Code (ACP) sessions store the user turn with the runtime's `[CURRENT DATE & TIME: …]`
 * prefix; zerocode strips it for display, and so does this.
 */
fun stripDatePrefix(content: String): String {
    if (!content.startsWith("[CURRENT DATE & TIME:")) return content
    val end = content.indexOf(']')
    return if (end < 0) content else content.substring(end + 1).trimStart()
}

/** Rebuilds a transcript from `session/messages`, oldest first. */
fun fromHistory(messages: List<MessageEntry>, start: Int = 0, hasOlder: Boolean = false): ChatState {
    var s = ChatState(historyStart = start, hasOlder = hasOlder)
    var lastUser: String? = null
    for (m in messages) {
        s = when (m.kind) {
            "tool_call" -> s.append("h") {
                ChatItem.Tool(it, m.toolCallId ?: it, m.toolName ?: "tool", argsPreview(m.toolInput), ToolStatus.Ok, null, 0, edit = fileEditOf(m.toolName ?: "", m.toolInput))
            }
            "tool_result" -> {
                val output = truncateForDisplay(m.toolOutput ?: m.content)
                val match = s.items.lastOrNull { it is ChatItem.Tool && it.callId == m.toolCallId } as ChatItem.Tool?
                if (match != null) s.copy(items = s.items.map { if (it.key == match.key) match.copy(output = output) else it })
                else s.append("h") { ChatItem.Tool(it, m.toolCallId ?: it, m.toolName ?: "tool", "", ToolStatus.Ok, output, 0) }
            }
            else -> when (m.role) {
                "user" -> {
                    val (text, images) = splitMarkers(stripDatePrefix(m.content))
                    lastUser = text.takeIf { images.isEmpty() }
                    s.append("h") { ChatItem.User(it, text, images = images) }
                }
                "assistant" -> if (m.content.isBlank()) s else s.append("h") { ChatItem.Agent(it, m.content) }
                else -> s
            }
        }
    }
    return s.copy(lastUserPrompt = lastUser)
}

/** Puts an older page of history in front of the current transcript. */
fun prependHistory(state: ChatState, older: ChatState): ChatState {
    val prefix = "o${state.seq}-"
    val renamed = older.items.map {
        when (it) {
            is ChatItem.User -> it.copy(key = prefix + it.key)
            is ChatItem.Agent -> it.copy(key = prefix + it.key)
            is ChatItem.Thought -> it.copy(key = prefix + it.key)
            is ChatItem.Tool -> it.copy(key = prefix + it.key)
            is ChatItem.Approval -> it.copy(key = prefix + it.key)
            is ChatItem.Question -> it.copy(key = prefix + it.key)
            is ChatItem.Notice -> it.copy(key = prefix + it.key)
        }
    }
    return state.copy(
        items = renamed + state.items,
        hasOlder = older.hasOlder,
        historyStart = older.historyStart,
        seq = state.seq + 1,
        lastUserPrompt = state.lastUserPrompt ?: older.lastUserPrompt,
    )
}

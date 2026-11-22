package pt.tiago.zerodroid.service

import pt.tiago.zerodroid.data.ChatItem
import pt.tiago.zerodroid.data.ChatState
import pt.tiago.zerodroid.data.NoticeKind

/** Hold the foreground service while a phone-started turn runs or one of its approvals waits. */
fun shouldHoldForeground(runningTurns: Set<String>, pendingApprovals: Int): Boolean =
    runningTurns.isNotEmpty() || pendingApprovals > 0

/**
 * Text for an update of the ongoing notification, or null when nothing is in flight: the
 * service is about to stop, and re-posting then can leave an orphan "Waiting for your
 * approval…" behind once the foreground notification is removed.
 */
fun ongoingUpdate(runningAgents: List<String>, pendingApprovals: Int): String? =
    if (runningAgents.isEmpty() && pendingApprovals == 0) null else ongoingText(runningAgents)

/** Ongoing-notification text: "researcher is working…", "researcher and coder are working…", "3 agents are working…". */
fun ongoingText(runningAgents: List<String>): String {
    val agents = runningAgents.distinct()
    return when (agents.size) {
        0 -> "Waiting for your approval…"
        1 -> "${agents[0]} is working…"
        2 -> "${agents[0]} and ${agents[1]} are working…"
        else -> "${agents.size} agents are working…"
    }
}

/** What the "turn finished" notification says. */
data class FinishedTurn(val title: String, val text: String, val failed: Boolean)

/**
 * Describes how the latest turn in [state] ended, or null when it needs no notification:
 * a turn the user stopped themselves. Looks only at the items after the last prompt.
 */
fun finishedTurn(agent: String, state: ChatState): FinishedTurn? {
    val items = state.items
    val lastPrompt = items.indexOfLast { it is ChatItem.User && !it.steer }
    val turn = items.drop(lastPrompt + 1)
    val notice = turn.filterIsInstance<ChatItem.Notice>().lastOrNull {
        it.kind == NoticeKind.TurnCancelled ||
            it.kind == NoticeKind.TurnFailed ||
            it.kind == NoticeKind.Error
    }
    if (notice != null) {
        if (notice.kind == NoticeKind.TurnCancelled && notice.text == "Turn stopped") return null
        val title = if (notice.kind == NoticeKind.TurnCancelled) "$agent stopped" else "$agent failed"
        return FinishedTurn(title, notice.text, failed = true)
    }
    val reply = turn.filterIsInstance<ChatItem.Agent>().lastOrNull()?.text?.trim().orEmpty()
    return FinishedTurn("$agent finished", reply.ifEmpty { "The turn is done" }, failed = false)
}

/** Markdown reply → one readable paragraph for the notification shade. */
internal fun plainPreview(text: String): String = text
    .replace(Regex("```[a-zA-Z0-9_-]*"), "")
    .replace(Regex("(?m)^\\s{0,3}(#{1,6}|>|[-*+])\\s+"), "")
    .replace("**", "").replace("__", "").replace("`", "")
    .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")
    .replace(Regex("\\n{2,}"), "\n")
    .trim()

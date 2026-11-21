package pt.tiago.zerodroid.service

/** Hold the foreground service while a phone-started turn runs or one of its approvals waits. */
fun shouldHoldForeground(runningTurns: Set<String>, pendingApprovals: Int): Boolean =
    runningTurns.isNotEmpty() || pendingApprovals > 0

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

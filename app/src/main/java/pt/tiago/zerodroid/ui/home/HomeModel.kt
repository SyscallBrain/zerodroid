package pt.tiago.zerodroid.ui.home

import pt.tiago.zerodroid.rpc.ConnState
import pt.tiago.zerodroid.rpc.proto.AgentStatusEntry
import pt.tiago.zerodroid.rpc.proto.CronJob
import pt.tiago.zerodroid.ui.kit.AgentState
import pt.tiago.zerodroid.ui.kit.Tone
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

data class ConnectionPill(val label: String, val tone: Tone, val pulse: Boolean)

data class AgentRowModel(val alias: String, val subtitle: String, val state: AgentState)

data class HomeModel(
    val connection: ConnectionPill,
    /** Headline split so the last word can carry the accent gradient. */
    val headlineLead: String,
    val headlineAccent: String,
    val headlineTone: Tone,
    val daemonVersion: String? = null,
    val uptimeText: String? = null,
    val costToday: String? = null,
    val costLimit: String? = null,
    /** 0..1 of the daily budget, when a limit is known. */
    val costFraction: Float? = null,
    val activeSessions: Int? = null,
    val nextCron: String? = null,
    val nextCronLabel: String? = null,
    val agents: List<AgentRowModel> = emptyList(),
    val attention: Int = 0,
    val attentionSubtitle: String? = null,
    val stale: Boolean = false,
    val loaded: Boolean = false,
    val hint: String? = null,
    val memory: MetricText = MetricText(null),
    val cpu: MetricText = MetricText(null),
    val core: List<ComponentRow> = emptyList(),
    val channels: List<ComponentRow> = emptyList(),
    val upNext: List<Pair<String, String>> = emptyList(),
    val costMonth: String? = null,
    /** Pinned agents, shown above the rest; [agents] then excludes them. */
    val pinned: List<AgentRowModel> = emptyList(),
) {
    val headline: String get() = if (headlineLead.isEmpty()) headlineAccent else "$headlineLead $headlineAccent"
}

data class HomeInputs(
    val connState: ConnState,
    val host: String,
    val daemonName: String,
    val uptimeSeconds: Long? = null,
    val costToday: Double? = null,
    val costLimit: Double? = null,
    val activeSessions: Int? = null,
    val agents: List<AgentStatusEntry>? = null,
    val crons: List<CronJob>? = null,
    val pendingApprovals: Int = 0,
    /** Aliases with an approval waiting on this phone, in arrival order. */
    val approvalAgents: List<Pair<String, String>> = emptyList(),
    /** Aliases with a turn running (on this phone or reported running by the daemon). */
    val workingAgents: Set<String> = emptySet(),
    val process: pt.tiago.zerodroid.rpc.proto.ProcessStats? = null,
    val components: Map<String, pt.tiago.zerodroid.rpc.proto.ComponentHealth>? = null,
    val costMonth: Double? = null,
    val pins: List<String> = emptyList(),
)

private val HHMM = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
private val DAY_HHMM = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH)

/** Banner copy for a refused connection: an expired OIDC sign-in is renewed, anything else means pairing again. */
fun authFailedBanner(message: String): String =
    if (message == pt.tiago.zerodroid.data.EXPIRED_MESSAGE) "Your sign-in expired. Sign in again to reconnect."
    else "The daemon refused this phone. Pair again in Config."

fun isLoopback(host: String) = host == "127.0.0.1" || host == "::1" || host.equals("localhost", ignoreCase = true)

fun formatUptime(seconds: Long): String {
    val d = seconds / 86_400
    val h = (seconds % 86_400) / 3_600
    val m = (seconds % 3_600) / 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        else -> "${m}m"
    }
}

fun formatUsd(v: Double): String = if (v < 10) "$%.2f".format(Locale.ENGLISH, v) else "$%.0f".format(Locale.ENGLISH, v)

/** The earliest enabled job by its daemon-computed `next_run`. */
fun nextCron(jobs: List<CronJob>, now: Instant, zone: ZoneId): Pair<String, String?>? {
    val next = jobs.asSequence()
        .filter { it.enabled }
        .mapNotNull { j -> j.nextRun?.let { runCatching { Instant.parse(it) }.getOrNull() }?.let { it to j } }
        .filter { (t, _) -> !t.isBefore(now.minusSeconds(60)) }
        .minByOrNull { it.first } ?: return null
    val local = ZonedDateTime.ofInstant(next.first, zone)
    val today = ZonedDateTime.ofInstant(now, zone).toLocalDate()
    val text = if (local.toLocalDate() == today) local.format(HHMM) else local.format(DAY_HHMM)
    val job = next.second
    return text to (job.name?.takeIf { it.isNotBlank() } ?: job.agentAlias?.takeIf { it.isNotBlank() })
}

private fun stateRank(s: AgentState) = when (s) {
    AgentState.NeedsYou -> 0
    AgentState.Working -> 1
    AgentState.Idle -> 2
    AgentState.Offline -> 3
}

fun buildHomeModel(inputs: HomeInputs, now: Instant, zone: ZoneId, previous: HomeModel? = null): HomeModel {
    val where = if (isLoopback(inputs.host)) "this phone" else "${inputs.daemonName} · Tailscale"
    val ready = inputs.connState is ConnState.Ready
    val (lead, accent, tone) = when (val s = inputs.connState) {
        is ConnState.Ready -> Triple("ZeroClaw is", "running", Tone.Live)
        is ConnState.Connecting, ConnState.Idle -> Triple("", "Connecting…", Tone.Neutral)
        is ConnState.Backoff -> Triple("Daemon", "unreachable", Tone.Danger)
        is ConnState.AuthFailed -> Triple("Access", "refused", Tone.Danger)
    }
    val pill = when (inputs.connState) {
        is ConnState.Ready -> ConnectionPill(where, Tone.Success, false)
        is ConnState.Connecting, ConnState.Idle -> ConnectionPill(where, Tone.Neutral, true)
        is ConnState.Backoff -> ConnectionPill(where, Tone.Danger, false)
        is ConnState.AuthFailed -> ConnectionPill(where, Tone.Danger, false)
    }
    val hint = when (val s = inputs.connState) {
        is ConnState.Backoff -> "Retrying in ${(s.retryInMs + 999) / 1000}s · ${s.cause}"
        is ConnState.AuthFailed ->
            if (s.message == pt.tiago.zerodroid.data.EXPIRED_MESSAGE) "${s.message}. Sign in again to reconnect."
            else "${s.message}. Unpair in Config and pair again."
        else -> null
    }
    val keep = previous?.takeIf { !ready || it.loaded }

    val needsYou = inputs.approvalAgents.map { it.first }.toSet()
    val agents = inputs.agents?.let { list ->
        list.map { a ->
            val state = when {
                !a.enabled -> AgentState.Offline
                a.alias in needsYou -> AgentState.NeedsYou
                a.alias in inputs.workingAgents -> AgentState.Working
                else -> AgentState.Idle
            }
            val sessions = a.persistedSessions
            val subtitle = listOfNotNull(
                a.runtimeProfile?.takeIf { it.isNotBlank() },
                when {
                    state == AgentState.NeedsYou -> inputs.approvalAgents.firstOrNull { it.first == a.alias }?.second?.let { "wants $it" }
                    sessions > 0 -> "$sessions session${if (sessions == 1) "" else "s"}"
                    else -> null
                },
                if (a.channels.isNotEmpty() && state != AgentState.NeedsYou) a.channels.joinToString(", ") else null,
            ).joinToString(" · ").ifEmpty { if (a.enabled) "ready" else "disabled" }
            AgentRowModel(a.alias, subtitle, state)
        }.sortedWith(compareBy({ stateRank(it.state) }, { it.alias.lowercase() }))
    } ?: (keep?.let { it.pinned + it.agents }.orEmpty())

    val cron = inputs.crons?.let { nextCron(it, now, zone) }
    val attentionSubtitle = inputs.approvalAgents.takeIf { it.isNotEmpty() }?.let { list ->
        val (alias, tool) = list.first()
        "$alias · $tool" + if (list.size > 1) ", +${list.size - 1}" else ""
    }

    val (core, channels) = inputs.components?.let(::systemComponents) ?: ((keep?.core ?: emptyList()) to (keep?.channels ?: emptyList()))
    val pinSet = inputs.pins.toSet()
    val pinned = orderPinnedRows(agents.filter { it.alias in pinSet }, inputs.pins)
    return HomeModel(
        memory = if (inputs.process != null) memoryMetric(inputs.process) else keep?.memory ?: MetricText(null),
        cpu = if (inputs.process != null) cpuMetric(inputs.process) else keep?.cpu ?: MetricText(null),
        core = core,
        channels = channels,
        upNext = inputs.crons?.let { upNext(it, now, zone) } ?: keep?.upNext.orEmpty(),
        costMonth = inputs.costMonth?.let(::formatUsd) ?: keep?.costMonth,
        pinned = pinned,
        connection = pill,
        headlineLead = lead,
        headlineAccent = accent,
        headlineTone = tone,
        daemonVersion = (inputs.connState as? ConnState.Ready)?.info?.serverVersion ?: keep?.daemonVersion,
        uptimeText = inputs.uptimeSeconds?.let(::formatUptime) ?: keep?.uptimeText,
        costToday = inputs.costToday?.let(::formatUsd) ?: keep?.costToday,
        costLimit = inputs.costLimit?.takeIf { it > 0 }?.let { "/ ${formatUsd(it)}" },
        costFraction = if (inputs.costToday != null && inputs.costLimit != null && inputs.costLimit > 0) {
            (inputs.costToday / inputs.costLimit).toFloat().coerceIn(0f, 1f)
        } else null,
        activeSessions = inputs.activeSessions ?: keep?.activeSessions,
        nextCron = cron?.first ?: if (inputs.crons != null) null else keep?.nextCron,
        nextCronLabel = cron?.second ?: if (inputs.crons != null) null else keep?.nextCronLabel,
        agents = agents.filter { it.alias !in pinSet },
        attention = inputs.pendingApprovals,
        attentionSubtitle = attentionSubtitle,
        stale = !ready && keep != null && keep.loaded,
        loaded = (ready && inputs.agents != null) || (keep?.loaded == true),
        hint = hint,
    )
}

private fun orderPinnedRows(rows: List<AgentRowModel>, pins: List<String>) =
    pt.tiago.zerodroid.data.orderPinned(rows, pins) { it.alias }

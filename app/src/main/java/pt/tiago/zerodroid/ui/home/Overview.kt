package pt.tiago.zerodroid.ui.home

import pt.tiago.zerodroid.rpc.proto.ComponentHealth
import pt.tiago.zerodroid.rpc.proto.CronJob
import pt.tiago.zerodroid.rpc.proto.ProcessStats
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** "142 MB", "1.2 GB"; "—" when unknown. Binary units, as the dashboard shows RSS. */
fun formatBytes(b: Long): String {
    if (b <= 0) return "—"
    val mb = b / (1024.0 * 1024.0)
    return if (mb < 1024) "${mb.roundToInt()} MB" else "%.1f GB".format(Locale.ENGLISH, mb / 1024.0)
}

/** A tile's value, its muted suffix and an optional 0..1 bar. Null value = unknown ("—"). */
data class MetricText(val value: String?, val suffix: String? = null, val fraction: Float? = null, val caption: String? = null)

/** "0.4%" below 1%, whole numbers above. */
private fun percent(f: Double): String = if (f < 0.01) "%.1f%%".format(Locale.ENGLISH, f * 100) else "${(f * 100).roundToInt()}%"

fun memoryMetric(p: ProcessStats?): MetricText {
    if (p == null || p.rssBytes <= 0) return MetricText(null)
    val fraction = if (p.systemRamTotalBytes > 0) (p.rssBytes.toDouble() / p.systemRamTotalBytes).toFloat().coerceIn(0f, 1f) else null
    return MetricText(
        formatBytes(p.rssBytes),
        fraction = fraction,
        caption = if (p.systemRamTotalBytes > 0) "${percent(p.rssBytes.toDouble() / p.systemRamTotalBytes)} of ${formatBytes(p.systemRamTotalBytes)}" else null,
    )
}

/**
 * An idle daemon on a phone sits well under 1 % of all cores; rounding that to "0%" reads as
 * "not measured". One decimal below 10 %, and "<0.1%" for any non-zero trace.
 */
internal fun cpuText(pct: Double): String = when {
    pct <= 0.0 -> "0%"
    pct < 0.1 -> "<0.1%"
    pct < 9.95 -> "%.1f%%".format(Locale.ENGLISH, pct)
    else -> "${pct.roundToInt()}%"
}

/** `cpu_percent` is summed across cores; normalise to one 0..100 figure. */
fun cpuMetric(p: ProcessStats?): MetricText {
    val raw = p?.cpuPercent ?: return MetricText(null)
    val pct = (raw / maxOf(p.numCpus, 1)).coerceIn(0.0, 100.0)
    return MetricText(cpuText(pct), if (p.numCpus > 0) "${p.numCpus} cores" else null, (pct / 100).toFloat())
}

enum class Health { Ok, Warn, Error, Unknown }

data class ComponentRow(val key: String, val label: String, val health: Health, val detail: String?)

private val CORE_ORDER = listOf("daemon", "gateway", "scheduler", "channels", "heartbeat", "control-plane", "sop_dispatch")

private fun healthOf(status: String) = when (status.lowercase()) {
    "ok" -> Health.Ok
    "error", "failed" -> Health.Error
    "starting", "degraded", "restarting" -> Health.Warn
    else -> Health.Unknown
}

private fun rowOf(key: String, label: String, c: ComponentHealth): ComponentRow {
    val h = healthOf(c.status)
    val detail = if (h == Health.Ok) null else listOfNotNull(
        c.lastError?.takeIf { it.isNotBlank() },
        if (c.restartCount > 0) "restarted ${c.restartCount}×" else null,
    ).joinToString(" · ").ifEmpty { null }
    return ComponentRow(key, label, h, detail)
}

/** (core components, channel components), each in display order. */
fun systemComponents(c: Map<String, ComponentHealth>): Pair<List<ComponentRow>, List<ComponentRow>> {
    val (channelKeys, coreKeys) = c.keys.partition { it.startsWith("channel:") }
    val ordered = CORE_ORDER.filter { it in coreKeys } + coreKeys.filter { it !in CORE_ORDER }.sorted()
    val core = ordered.map { k -> rowOf(k, k.replace('_', ' ').replace('-', ' '), c.getValue(k)) }
    val channels = channelKeys.sorted().map { k ->
        val ref = k.removePrefix("channel:")
        rowOf(k, ref.replaceFirst(".", " · "), c.getValue(k))
    }
    return core to channels
}

private val HHMM = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)
private val DAY_HHMM = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH)

/** The next [n] enabled jobs as (time text, label). */
fun upNext(jobs: List<CronJob>, now: Instant, zone: ZoneId, n: Int = 3): List<Pair<String, String>> {
    val today = ZonedDateTime.ofInstant(now, zone).toLocalDate()
    return jobs.asSequence()
        .filter { it.enabled }
        .mapNotNull { j -> j.nextRun?.let { runCatching { Instant.parse(it) }.getOrNull() }?.let { it to j } }
        .filter { (t, _) -> !t.isBefore(now.minusSeconds(60)) }
        .sortedBy { it.first }
        .take(n)
        .map { (t, j) ->
            val local = ZonedDateTime.ofInstant(t, zone)
            val time = if (local.toLocalDate() == today) local.format(HHMM) else local.format(DAY_HHMM)
            time to (j.name?.takeIf { it.isNotBlank() } ?: j.agentAlias?.takeIf { it.isNotBlank() } ?: j.id)
        }.toList()
}

/** Healthy channels collapse into a count; only the ones with a problem are listed. */
data class FoldedChannels(val problems: List<ComponentRow>, val healthy: Int)

fun foldChannels(channels: List<ComponentRow>): FoldedChannels {
    val (ok, problems) = channels.partition { it.health == Health.Ok }
    return FoldedChannels(problems, ok.size)
}

package pt.tiago.zerodroid.ui.cron

import pt.tiago.zerodroid.rpc.proto.CronJob
import pt.tiago.zerodroid.ui.kit.Tone
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val WHEN = DateTimeFormatter.ofPattern("d MMM, HH:mm")

/** The job's title: its name, else the first line of what it runs. */
fun jobTitle(j: CronJob): String =
    j.name?.takeIf { it.isNotBlank() } ?: (if (j.isAgent) j.prompt.orEmpty() else j.command).lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } ?: j.id

fun scheduleText(j: CronJob): String = when (j.schedule?.kind) {
    "at" -> "Once at ${localTime(j.schedule?.at)}"
    "every" -> j.schedule?.everyMs?.let { "Every ${Duration.ofMillis(it).toMinutes()} min" } ?: "Repeating"
    else -> (j.schedule?.expr ?: j.expression) + (j.schedule?.tz?.let { " · $it" } ?: "")
}

/** "in 3h", "in 12m", "in 2d"; empty when unknown or past. */
fun untilText(iso: String?, now: Instant = Instant.now()): String {
    val t = iso?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return ""
    val d = Duration.between(now, t)
    return when {
        d.isNegative -> "due"
        d.toMinutes() < 1 -> "in <1m"
        d.toMinutes() < 60 -> "in ${d.toMinutes()}m"
        d.toHours() < 24 -> "in ${d.toHours()}h"
        else -> "in ${d.toDays()}d"
    }
}

fun localTime(iso: String?, zone: ZoneId = ZoneId.systemDefault()): String =
    iso?.let { runCatching { Instant.parse(it).atZone(zone).format(WHEN) }.getOrNull() } ?: "—"

fun durationText(ms: Long?): String = when {
    ms == null -> ""
    ms < 1000 -> "${ms}ms"
    ms < 60_000 -> "%.1fs".format(ms / 1000.0)
    else -> "${ms / 60_000}m ${(ms % 60_000) / 1000}s"
}

/** Pill for a run or job status ("ok" / "error" on the daemon). */
fun statusPill(status: String?): Pair<String, Tone> = when (status) {
    null -> "Never ran" to Tone.Neutral
    "ok" -> "OK" to Tone.Success
    "error" -> "Failed" to Tone.Danger
    else -> status.replaceFirstChar { it.uppercase() } to Tone.Attention
}

fun jobPill(j: CronJob): Pair<String, Tone> = if (!j.enabled) "Paused" to Tone.Neutral else statusPill(j.lastStatus)

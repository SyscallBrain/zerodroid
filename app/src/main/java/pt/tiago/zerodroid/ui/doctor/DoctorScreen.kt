package pt.tiago.zerodroid.ui.doctor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.tiago.zerodroid.data.AppPhase
import pt.tiago.zerodroid.data.ZeroDroidConnection
import pt.tiago.zerodroid.rpc.ConnState
import pt.tiago.zerodroid.rpc.proto.DiagResult
import pt.tiago.zerodroid.ui.kit.ConnectionBanner
import pt.tiago.zerodroid.ui.kit.EmptyState
import pt.tiago.zerodroid.ui.kit.SectionHeader
import pt.tiago.zerodroid.ui.kit.SkeletonLines
import pt.tiago.zerodroid.ui.kit.StatusDot
import pt.tiago.zerodroid.ui.kit.StatusPill
import pt.tiago.zerodroid.ui.kit.Tone
import pt.tiago.zerodroid.ui.kit.ZcCard
import pt.tiago.zerodroid.ui.kit.ZcGhostButton
import pt.tiago.zerodroid.ui.kit.ZcIconButton
import pt.tiago.zerodroid.ui.kit.ZcPrimaryButton
import pt.tiago.zerodroid.ui.kit.ZcTopBar
import pt.tiago.zerodroid.ui.kit.clickableNoRipple
import pt.tiago.zerodroid.ui.theme.ZcIcons
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType
import java.text.DateFormat
import java.util.Date

internal fun severityTone(severity: String): Tone = when (severity) {
    "error" -> Tone.Danger
    "warn" -> Tone.Attention
    "ok" -> Tone.Success
    else -> Tone.Neutral
}

/** The daemon this phone is paired with, as the Doctor cache keys it. */
internal fun currentDaemonKey(): String? = (ZeroDroidConnection.phase.value as? AppPhase.Paired)?.profile?.daemonKey()

/** ZeroClaw's own checks (`doctor/run`), with a way into the config entry each finding names. */
@Composable
fun DoctorScreen(onBack: () -> Unit, onFix: (DoctorFix) -> Unit) {
    val store = ZeroDroidConnection.doctor
    val s by store.state.collectAsStateWithLifecycle()
    val conn by ZeroDroidConnection.state.collectAsStateWithLifecycle()
    val ready = conn is ConnState.Ready
    val daemon = currentDaemonKey()
    val result = s.resultFor(daemon)
    // Issues first; OK findings are many and rarely interesting.
    var shownList by rememberSaveable { mutableStateOf(listOf("error", "warn")) }
    val shown = shownList.toSet()
    val toggle = { sev: String -> shownList = if (sev in shown) shownList - sev else shownList + sev }

    // Opening Doctor runs it when there is nothing yet for this daemon.
    LaunchedEffect(ready, daemon) { if (ready && result == null && !s.running) store.start(daemon) }

    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars)) {
        Column(Modifier.fillMaxSize()) {
            ZcTopBar(
                "Doctor",
                navigation = { ZcIconButton(ZcIcons.Back, "Back", onClick = onBack) },
                actions = {
                    if (result != null && !s.running) ZcIconButton(ZcIcons.Refresh, "Run again", enabled = ready) { store.start(daemon) }
                },
            )
            ConnectionBanner(!ready, "Offline. Doctor runs on the daemon; reconnect to run it.", Tone.Danger)
            ConnectionBanner(s.error != null, s.error.orEmpty(), Tone.Danger, action = "Retry", onAction = { store.consumeError(); store.start(daemon) })
            ConnectionBanner(
                result?.timedOutPhase != null,
                "The ${result?.timedOutPhase?.replace('_', ' ')} check ran out of time; its findings are missing.",
                Tone.Attention,
            )
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = ZcTokens.gutter, end = ZcTokens.gutter, top = 6.dp, bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(ZcTokens.gap),
            ) {
                when {
                    result == null && s.running -> item("running") {
                        ZcCard(Modifier.fillMaxWidth()) {
                            StatusPill("Running checks", Tone.Live, pulse = true)
                            Spacer(Modifier.height(8.dp))
                            Text("ZeroClaw checks its config, workspace, channels and tools, and asks every model to answer. This can take up to a minute.", style = ZcType.small)
                            Spacer(Modifier.height(12.dp))
                            SkeletonLines(4)
                        }
                    }
                    result == null -> item("empty") {
                        EmptyState(
                            ZcIcons.Shield, "Not run yet", "Doctor checks the daemon's config, workspace, channels, tools and models.",
                            action = { ZcPrimaryButton("Run Doctor", icon = ZcIcons.Play, enabled = ready) { store.start(daemon) } },
                        )
                    }
                    else -> {
                        item("summary") {
                            Column {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    SeverityToggle("error", "${result.summary.errors} error${if (result.summary.errors == 1) "" else "s"}", shown, toggle)
                                    SeverityToggle("warn", "${result.summary.warnings} warning${if (result.summary.warnings == 1) "" else "s"}", shown, toggle)
                                    SeverityToggle("ok", "${result.summary.ok} OK", shown, toggle)
                                }
                                Spacer(Modifier.height(8.dp))
                                if (s.running) {
                                    StatusPill("Running checks", Tone.Live, pulse = true)
                                } else {
                                    s.at?.let { StatusPill("Checked ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(it))}", Tone.Neutral, dot = false) }
                                }
                            }
                        }
                        val groups = groupFindings(result.results, shown)
                        if (groups.isEmpty()) {
                            item("clean") {
                                val issues = result.summary.errors + result.summary.warnings
                                EmptyState(
                                    ZcIcons.Check,
                                    if (issues == 0) "All ${result.summary.ok} checks passed" else "Nothing to show",
                                    if (issues == 0) "Tap OK above to see what was checked." else "Every severity is hidden. Tap a count above.",
                                )
                            }
                        }
                        groups.forEach { (category, items) ->
                            item("h-$category") { SectionHeader(category.replace('_', ' ').replace('-', ' '), Modifier.padding(top = 6.dp)) }
                            item("c-$category") {
                                ZcCard(Modifier.fillMaxWidth().alpha(if (s.running) 0.5f else 1f), padding = PaddingValues(horizontal = 16.dp, vertical = 4.dp)) {
                                    items.forEachIndexed { i, r ->
                                        if (i > 0) HorizontalDivider(color = ZcTokens.hairline)
                                        FindingRow(r, onFix)
                                    }
                                }
                            }
                        }
                        result.logPath?.let { path ->
                            item("log") {
                                SelectionContainer { Text("Daemon log: $path", style = ZcType.monoSmall.copy(color = ZcTokens.faint), modifier = Modifier.padding(top = 8.dp)) }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SeverityToggle(severity: String, text: String, shown: Set<String>, toggle: (String) -> Unit) {
    val on = severity in shown
    Box(Modifier.alpha(if (on) 1f else 0.45f).clickableNoRipple { toggle(severity) }) {
        StatusPill(text, if (on) severityTone(severity) else Tone.Neutral)
    }
}

@Composable
private fun FindingRow(r: DiagResult, onFix: (DoctorFix) -> Unit) {
    val fix = doctorFix(r)
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        StatusDot(severityTone(r.severity), Modifier.padding(top = 8.dp))
        Spacer(Modifier.width(10.dp))
        SelectionContainer(Modifier.weight(1f)) { Text(r.message, style = ZcType.body) }
        if (fix != null) {
            Spacer(Modifier.width(8.dp))
            ZcGhostButton(if (fix is DoctorFix.Open) "Fix" else "Config", compact = true) { onFix(fix) }
        }
    }
}

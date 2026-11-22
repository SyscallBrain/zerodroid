package pt.tiago.zerodroid.ui.config

import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.tiago.zerodroid.BuildConfig
import pt.tiago.zerodroid.data.AppPhase
import pt.tiago.zerodroid.data.ZeroDroidConnection
import pt.tiago.zerodroid.rpc.ConnState
import pt.tiago.zerodroid.ui.home.isLoopback
import pt.tiago.zerodroid.ui.kit.SectionHeader
import pt.tiago.zerodroid.ui.kit.StatusPill
import pt.tiago.zerodroid.ui.kit.Tone
import pt.tiago.zerodroid.ui.kit.ZcCard
import pt.tiago.zerodroid.ui.kit.ZcDangerButton
import pt.tiago.zerodroid.ui.kit.ZcGhostButton
import pt.tiago.zerodroid.ui.kit.ZcIconButton
import pt.tiago.zerodroid.ui.kit.ZcSwitch
import pt.tiago.zerodroid.ui.kit.ZcTopBar
import pt.tiago.zerodroid.ui.theme.ZcIcons
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val DATE = DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH)

@Composable
fun PhoneScreen(debug: Boolean, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val phase by ZeroDroidConnection.phase.collectAsStateWithLifecycle()
    val conn by ZeroDroidConnection.state.collectAsStateWithLifecycle()
    val unreachable by ZeroDroidConnection.simulateUnreachable.collectAsStateWithLifecycle()
    val showWorkflows by ZeroDroidConnection.screen.showWorkflowsTile.collectAsStateWithLifecycle()
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val profile = (phase as? AppPhase.Paired)?.profile
    var confirmUnpair by remember { mutableStateOf(false) }
    val context = LocalContext.current
    // Ticks so "expires in N min" stays true while the screen is open.
    val now by androidx.compose.runtime.produceState(System.currentTimeMillis() / 1000) {
        while (true) {
            kotlinx.coroutines.delay(15_000)
            value = System.currentTimeMillis() / 1000
        }
    }

    Column(modifier.fillMaxSize()) {
        ZcTopBar("ZeroDroid & pairing", navigation = { ZcIconButton(ZcIcons.Back, "Back", onClick = onBack) })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ZcTokens.gutter).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(ZcTokens.gap),
        ) {
            SectionHeader("Paired daemon")
            ZcCard(Modifier.fillMaxWidth(), padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                if (profile != null) {
                    InfoRow(ZcIcons.Terminal, "Name", profile.name) {
                        val (label, tone) = when (conn) {
                            is ConnState.Ready -> "connected" to Tone.Success
                            is ConnState.Backoff -> "offline" to Tone.Danger
                            is ConnState.AuthFailed -> "refused" to Tone.Danger
                            else -> "connecting" to Tone.Neutral
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(profile.name, style = ZcType.small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Spacer(Modifier.width(8.dp))
                            StatusPill(label, tone)
                        }
                    }
                    Divider()
                    InfoRow(ZcIcons.Link, "Address", "${profile.host}:${profile.wssPort}" + if (isLoopback(profile.host)) " · this phone" else "")
                    Divider()
                    InfoRow(ZcIcons.Bolt, "Version", (conn as? ConnState.Ready)?.info?.serverVersion ?: ZeroDroidConnection.lastServerVersion ?: "—")
                    Divider()
                    InfoRow(
                        ZcIcons.Shield, "Certificate",
                        if (profile.notAfter > 0) "valid until " + DATE.format(Instant.ofEpochSecond(profile.notAfter).atZone(ZoneId.systemDefault())) else "—",
                    )
                    Divider()
                    InfoRow(ZcIcons.Qr, "Device", profile.deviceId.take(18), mono = true)
                    if (profile.isOidc) {
                        Divider()
                        InfoRow(ZcIcons.Clock, "Sign-in", oidcSignInText(profile, now))
                    }
                }
            }
            if (profile?.isOidc == true) {
                ZcGhostButton("Sign in again", icon = ZcIcons.Refresh, modifier = Modifier.fillMaxWidth(), onClick = ZeroDroidConnection::requestRenew)
            }

            SectionHeader("Home")
            ZcCard(Modifier.fillMaxWidth()) {
                val toggle = { on: Boolean -> scope.launch { ZeroDroidConnection.screen.setShowWorkflowsTile(on) }; Unit }
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { toggle(!showWorkflows) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Show Workflows card", style = ZcType.body)
                        Text("Off hides it from Home. Approvals still reach the Inbox.", style = ZcType.caption)
                    }
                    ZcSwitch(showWorkflows, toggle)
                }
            }

            SectionHeader("Background")
            ZcCard(Modifier.fillMaxWidth()) {
                Text("Running turns and approvals keep the connection open in the background with an ongoing notification. Samsung may still freeze the app; exempting it from battery optimisation helps.", style = ZcType.small)
                Spacer(Modifier.height(12.dp))
                val pm = context.getSystemService(PowerManager::class.java)
                val exempt = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
                if (exempt) {
                    StatusPill("Battery optimisation off for ZeroDroid", Tone.Success)
                } else {
                    ZcGhostButton("Battery settings", icon = ZcIcons.Battery, compact = true) {
                        runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                    }
                }
            }

            if (debug) {
                SectionHeader("Diagnostics (debug build)")
                ZcCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Drop connection", style = ZcType.body)
                            Text("Closes the socket as if the network failed", style = ZcType.caption)
                        }
                        ZcGhostButton("Drop", compact = true, onClick = ZeroDroidConnection::dropConnection)
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Simulate unreachable", style = ZcType.body)
                            Text("Connections fail until this is off", style = ZcType.caption)
                        }
                        ZcSwitch(unreachable, ZeroDroidConnection::setSimulateUnreachable)
                    }
                }
            }

            SectionHeader("This phone")
            ZcCard(Modifier.fillMaxWidth()) {
                Text(
                    if (profile?.isOidc == true) "Unpairing deletes this phone's certificate, key and token. To connect again, sign in with OIDC."
                    else "Unpairing deletes this phone's certificate, key and token. To connect again, run the pairing helper.",
                    style = ZcType.small,
                )
                Spacer(Modifier.height(12.dp))
                ZcDangerButton("Unpair this phone", icon = ZcIcons.Unlink, modifier = Modifier.fillMaxWidth()) { confirmUnpair = true }
            }
            Text(
                "ZeroDroid ${BuildConfig.VERSION_NAME} by Tiago Correia",
                style = ZcType.caption.copy(color = ZcTokens.faint),
                modifier = Modifier.padding(top = 8.dp).align(Alignment.CenterHorizontally),
            )
        }
    }

    if (confirmUnpair) {
        AlertDialog(
            onDismissRequest = { confirmUnpair = false },
            containerColor = ZcTokens.raised,
            title = { Text("Unpair ${profile?.name ?: "daemon"}?", style = ZcType.heading) },
            text = { Text("Running turns stop and pending approvals are denied. The daemon keeps your chats.", style = ZcType.body.copy(color = ZcTokens.muted)) },
            confirmButton = {
                TextButton(onClick = { confirmUnpair = false; ZeroDroidConnection.unpair() }) {
                    Text("Unpair", style = ZcType.button.copy(color = ZcTokens.danger))
                }
            },
            dismissButton = { TextButton(onClick = { confirmUnpair = false }) { Text("Cancel", style = ZcType.button.copy(color = ZcTokens.muted)) } },
        )
    }
}

@Composable
private fun Divider() = HorizontalDivider(color = ZcTokens.hairline)

@Composable
private fun InfoRow(icon: ImageVector, label: String, value: String, mono: Boolean = false, trailing: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = ZcTokens.muted, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, style = ZcType.body, modifier = Modifier.weight(1f))
        if (trailing != null) trailing() else Text(
            value,
            style = if (mono) ZcType.monoSmall.copy(color = ZcTokens.muted) else ZcType.small,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

internal fun oidcSignInText(profile: pt.tiago.zerodroid.data.DaemonProfile, now: Long): String {
    val expires = profile.tokenExpiresAt ?: return "${profile.authProvider} · expiry unknown"
    val left = expires - now
    return if (left <= 30) "${profile.authProvider} · expired" else "${profile.authProvider} · expires in ${(left + 59) / 60} min"
}

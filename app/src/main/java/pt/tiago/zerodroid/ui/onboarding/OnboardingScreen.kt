package pt.tiago.zerodroid.ui.onboarding

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import pt.tiago.zerodroid.ui.kit.CodeBlock
import pt.tiago.zerodroid.ui.kit.ConnectionBanner
import pt.tiago.zerodroid.ui.kit.SkeletonLines
import pt.tiago.zerodroid.ui.kit.Tone
import pt.tiago.zerodroid.ui.kit.ZcCard
import pt.tiago.zerodroid.ui.kit.ZcGhostButton
import pt.tiago.zerodroid.ui.kit.ZcPrimaryButton
import pt.tiago.zerodroid.ui.kit.ZcTextField
import pt.tiago.zerodroid.ui.theme.ZcIcons
import pt.tiago.zerodroid.ui.theme.ZcMonoFamily
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

@Composable
fun OnboardingScreen(
    state: OnbState,
    notice: String?,
    onLink: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    onScanQr: () -> Unit,
    onOidc: () -> Unit = {},
) {
    OnboardingFrame {
        if (notice != null && state is OnbState.Welcome) {
            Spacer(Modifier.height(20.dp))
            ConnectionBanner(true, notice, Tone.Attention, Modifier.padding(horizontal = 0.dp))
        }
        Spacer(Modifier.height(32.dp))
        AnimatedContent(
            targetState = state,
            contentKey = { it::class },
            transitionSpec = {
                (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 12 }) togetherWith fadeOut(tween(150))
            },
            label = "onb",
        ) { s ->
            when (s) {
                OnbState.Welcome, is OnbState.Input -> PairOptions(onLink, onScanQr, onOidc)
                OnbState.Verifying -> Working("Checking the daemon", "Fetching its certificate and matching the fingerprint from the link.")
                is OnbState.Confirm -> Confirm(s, onConfirm, onCancel)
                OnbState.Enrolling -> Working("Issuing this phone's certificate", "The private key is created in Android Keystore and never leaves the phone.")
                OnbState.Done -> Working("Connecting", "Pairing saved. Opening your dashboard.")
                is OnbState.Failed -> Failed(s.message, onCancel)
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

/** The brand header and background shared by link pairing and OIDC sign-in. */
@Composable
internal fun OnboardingFrame(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(ZcTokens.background)
            .background(
                Brush.radialGradient(
                    listOf(ZcTokens.accentEnd.copy(alpha = 0.14f), ZcTokens.background.copy(alpha = 0f)),
                    center = androidx.compose.ui.geometry.Offset(200f, 0f),
                    radius = 1400f,
                ),
            ),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(48.dp))
            BrandMark()
            Spacer(Modifier.height(28.dp))
            Text("ZeroDroid", style = ZcType.display.copy(fontSize = 34.sp))
            Spacer(Modifier.height(6.dp))
            Text(
                buildAnnotatedString {
                    append("Your ZeroClaw agents, ")
                    withStyle(SpanStyle(brush = ZcTokens.accentHorizontal)) { append("in your pocket.") }
                },
                style = ZcType.title.copy(color = ZcTokens.muted, fontWeight = FontWeight(500)),
            )
            content()
        }
    }
}

@Composable
private fun BrandMark() {
    Box(
        Modifier
            .size(56.dp)
            .background(ZcTokens.accent, RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(ZcIcons.Bolt, null, tint = ZcTokens.onAccent, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun PairOptions(onLink: (String) -> Unit, onScanQr: () -> Unit, onOidc: () -> Unit) {
    val context = LocalContext.current
    var manual by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    var clipError by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ZcCard(Modifier.fillMaxWidth()) {
            Text("Pair with a daemon", style = ZcType.heading)
            Spacer(Modifier.height(4.dp))
            Text(
                "On the machine running ZeroClaw, run the pairing helper. In Termux on this phone it opens ZeroDroid by itself.",
                style = ZcType.small,
            )
            Spacer(Modifier.height(14.dp))
            CodeBlock("tools/pair-phone.sh")
            Spacer(Modifier.height(16.dp))
            ZcPrimaryButton(
                "Paste pairing link",
                icon = ZcIcons.Clipboard,
                modifier = Modifier.fillMaxWidth(),
            ) {
                val clip = readClipboard(context)
                if (clip != null && clip.trim().startsWith("zerodroid://", ignoreCase = true) || clip?.contains("ca_fp=") == true) {
                    clipError = null
                    onLink(clip!!)
                } else {
                    clipError = if (clip.isNullOrBlank()) "The clipboard is empty. Paste the link below." else "The clipboard has no pairing link. Paste it below."
                    manual = true
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ZcGhostButton("Scan QR", icon = ZcIcons.Qr, modifier = Modifier.weight(1f), onClick = onScanQr)
                ZcGhostButton("Type it", icon = ZcIcons.Link, modifier = Modifier.weight(1f)) { manual = !manual }
            }
            AnimatedVisibility(manual, enter = expandVertically() + fadeIn(), exit = fadeOut()) {
                Column(Modifier.padding(top = 14.dp)) {
                    if (clipError != null) {
                        Text(clipError!!, style = ZcType.caption.copy(color = ZcTokens.attention))
                        Spacer(Modifier.height(8.dp))
                    }
                    ZcTextField(
                        value = text,
                        onValueChange = { text = it },
                        placeholder = "zerodroid://pair?v=1&…",
                        mono = true,
                        singleLine = false,
                        maxLines = 4,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onDone = { if (text.isNotBlank()) onLink(text) }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(10.dp))
                    ZcPrimaryButton("Continue", enabled = text.isNotBlank(), modifier = Modifier.fillMaxWidth()) { onLink(text) }
                }
            }
        }
        ZcGhostButton("Sign in with OIDC", icon = ZcIcons.Shield, modifier = Modifier.fillMaxWidth(), onClick = onOidc)
        Row(Modifier.padding(horizontal = 4.dp), verticalAlignment = Alignment.Top) {
            Icon(ZcIcons.Shield, null, tint = ZcTokens.faint, modifier = Modifier.size(16.dp).padding(top = 1.dp))
            Spacer(Modifier.width(8.dp))
            Text(
                "The link carries a long-lived token. ZeroDroid encrypts it with a key that never leaves this phone, and you confirm a short code before anything is saved.",
                style = ZcType.caption.copy(color = ZcTokens.faint),
            )
        }
    }
}

@Composable
private fun Confirm(s: OnbState.Confirm, onConfirm: () -> Unit, onCancel: () -> Unit) {
    var armed by remember(s) { mutableStateOf(false) }
    LaunchedEffect(s) {
        delay(600)
        armed = true
    }
    ZcCard(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(20.dp)) {
        Text(
            buildAnnotatedString {
                append("Pair with ")
                withStyle(SpanStyle(fontWeight = FontWeight(700), color = ZcTokens.text)) { append(s.name) }
                append(" at ")
                withStyle(SpanStyle(fontFamily = ZcMonoFamily, color = ZcTokens.text)) { append(s.host) }
                append("?")
            },
            style = ZcType.body.copy(color = ZcTokens.muted),
        )
        Spacer(Modifier.height(22.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .background(ZcTokens.background, RoundedCornerShape(ZcTokens.inputRadius))
                .border(1.dp, ZcTokens.accentStart.copy(alpha = 0.25f), RoundedCornerShape(ZcTokens.inputRadius))
                .padding(vertical = 22.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                s.sas,
                style = TextStyle(
                    fontFamily = ZcMonoFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 40.sp,
                    letterSpacing = 0.08.em,
                    brush = ZcTokens.accentHorizontal,
                ),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "Does this match the code shown in Termux? If it doesn't, cancel: someone else's link or daemon.",
            style = ZcType.small,
            textAlign = TextAlign.Start,
        )
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ZcGhostButton("Cancel", modifier = Modifier.weight(1f), onClick = onCancel)
            ZcPrimaryButton("It matches, pair", enabled = armed, icon = ZcIcons.Check, modifier = Modifier.weight(1.4f), onClick = onConfirm)
        }
    }
}

@Composable
internal fun Working(title: String, hint: String) {
    ZcCard(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(20.dp)) {
        Text(title, style = ZcType.heading)
        Spacer(Modifier.height(4.dp))
        Text(hint, style = ZcType.small)
        Spacer(Modifier.height(18.dp))
        SkeletonLines(3)
    }
}

@Composable
internal fun Failed(message: String, onRetry: () -> Unit, title: String = "Pairing didn't go through") {
    ZcCard(
        Modifier.fillMaxWidth(),
        border = ZcTokens.danger.copy(alpha = 0.3f),
        padding = androidx.compose.foundation.layout.PaddingValues(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(30.dp).background(ZcTokens.danger.copy(alpha = 0.14f), RoundedCornerShape(9.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(ZcIcons.Alert, null, tint = ZcTokens.danger, modifier = Modifier.size(18.dp)) }
            Spacer(Modifier.width(12.dp))
            Text(title, style = ZcType.heading)
        }
        Spacer(Modifier.height(12.dp))
        Text(message, style = ZcType.body.copy(color = ZcTokens.muted))
        Spacer(Modifier.height(18.dp))
        ZcPrimaryButton("Try again", icon = ZcIcons.Refresh, modifier = Modifier.fillMaxWidth(), onClick = onRetry)
    }
}

private fun readClipboard(context: Context): String? = runCatching {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}.getOrNull()

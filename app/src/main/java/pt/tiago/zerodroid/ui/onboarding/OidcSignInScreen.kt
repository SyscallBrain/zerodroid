package pt.tiago.zerodroid.ui.onboarding

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import pt.tiago.zerodroid.enroll.OidcTargetException.Field
import pt.tiago.zerodroid.ui.kit.ZcCard
import pt.tiago.zerodroid.ui.kit.ZcGhostButton
import pt.tiago.zerodroid.ui.kit.ZcPrimaryButton
import pt.tiago.zerodroid.ui.kit.ZcTextField
import pt.tiago.zerodroid.ui.kit.clickableNoRipple
import pt.tiago.zerodroid.ui.theme.ZcIcons
import pt.tiago.zerodroid.ui.theme.ZcMonoFamily
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

/** Opens the identity provider's page in a Custom Tab, falling back to any browser. */
fun openSignInPage(context: Context, uri: String) {
    val parsed = Uri.parse(uri)
    try {
        CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, parsed)
    } catch (_: ActivityNotFoundException) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, parsed).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

/**
 * OIDC sign-in (onboarding) or renewal ([renew] = true, started by the caller with `vm.renew()`).
 * [onBack] leaves the screen; [onDone] fires once the sign-in finished.
 */
@Composable
fun OidcSignInScreen(vm: OidcSignInViewModel, renew: Boolean, onBack: () -> Unit, onDone: () -> Unit = {}) {
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) { if (state == OidcState.Done) onDone() }
    OnboardingFrame {
        Spacer(Modifier.height(24.dp))
        Text(if (renew) "Renew your sign-in" else "Sign in with OIDC", style = ZcType.heading)
        Spacer(Modifier.height(16.dp))
        AnimatedContent(
            targetState = state,
            contentKey = { it::class },
            transitionSpec = { (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 12 }) togetherWith fadeOut(tween(150)) },
            label = "oidc",
        ) { s ->
            val cancel = { vm.cancel(); onBack() }
            when (s) {
                is OidcState.Form ->
                    if (renew) Working("Starting", "Contacting the gateway.")
                    else OidcForm(s, onSubmit = vm::submit, onBack = cancel)
                OidcState.CheckingGateway -> Working("Checking the gateway", "Verifying its HTTPS certificate and the sign-in providers it offers.")
                is OidcState.TrustPrompt -> TrustPrompt(s, onTrust = vm::trust, onCancel = cancel)
                is OidcState.Approve -> Approve(s, onCancel = cancel)
                OidcState.Issuing -> Working("Issuing this phone's certificate", "The private key is created in Android Keystore and never leaves the phone.")
                OidcState.Connecting -> Working("Connecting", "Checking that the daemon accepts the sign-in.")
                OidcState.Done -> Working("Signed in", if (renew) "Reconnecting with the new sign-in." else "Opening your dashboard.")
                is OidcState.Failed -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Failed(s.message, onRetry = { if (renew) vm.renew() else vm.cancel() }, title = "Sign-in didn't go through")
                    ZcGhostButton("Close", modifier = Modifier.fillMaxWidth(), onClick = cancel)
                }
            }
        }
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun OidcForm(s: OidcState.Form, onSubmit: (String, String, String?, String?) -> Unit, onBack: () -> Unit) {
    var host by rememberSaveable { mutableStateOf("") }
    var alias by rememberSaveable { mutableStateOf("") }
    var wss by rememberSaveable { mutableStateOf("") }
    var gateway by rememberSaveable { mutableStateOf("") }
    var advanced by rememberSaveable { mutableStateOf(s.fieldError?.first == Field.WssUrl || s.fieldError?.first == Field.GatewayUrl) }
    val error = s.fieldError
    val submit = { onSubmit(host, alias, wss.ifBlank { null }, gateway.ifBlank { null }) }
    val uri = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next, autoCorrectEnabled = false)
    val shownHost = host.trim().ifEmpty { "host" }

    ZcCard(Modifier.fillMaxWidth(), padding = PaddingValues(20.dp)) {
        Text("For daemons whose operator set up an identity provider. You approve the sign-in in your browser; no pairing link is needed.", style = ZcType.small)
        Spacer(Modifier.height(16.dp))
        FieldLabel("Daemon host")
        ZcTextField(host, { host = it }, "daemon.example.com", mono = true, keyboardOptions = uri, modifier = Modifier.fillMaxWidth())
        FieldError(error, Field.Host)
        Spacer(Modifier.height(12.dp))
        FieldLabel("Provider alias")
        ZcTextField(
            alias, { alias = it }, "corp", mono = true,
            keyboardOptions = uri.copy(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (host.isNotBlank() && alias.isNotBlank()) submit() }),
            modifier = Modifier.fillMaxWidth(),
        )
        FieldError(error, Field.Alias)
        Spacer(Modifier.height(12.dp))
        Text(
            if (advanced) "Hide advanced" else "Advanced",
            style = ZcType.caption.copy(color = ZcTokens.accentStart),
            modifier = Modifier.clickableNoRipple { advanced = !advanced },
        )
        AnimatedVisibility(advanced, enter = expandVertically() + fadeIn(), exit = fadeOut()) {
            Column(Modifier.padding(top = 12.dp)) {
                FieldLabel("WSS URL")
                ZcTextField(wss, { wss = it }, "wss://$shownHost:9781/", mono = true, keyboardOptions = uri, modifier = Modifier.fillMaxWidth())
                FieldError(error, Field.WssUrl)
                Spacer(Modifier.height(12.dp))
                FieldLabel("Gateway URL")
                ZcTextField(gateway, { gateway = it }, "https://$shownHost:42617", mono = true, keyboardOptions = uri, modifier = Modifier.fillMaxWidth())
                FieldError(error, Field.GatewayUrl)
            }
        }
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ZcGhostButton("Back", modifier = Modifier.weight(1f), onClick = onBack)
            ZcPrimaryButton("Continue", enabled = host.isNotBlank() && alias.isNotBlank(), modifier = Modifier.weight(1.4f), onClick = submit)
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = ZcType.caption.copy(color = ZcTokens.muted))
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun FieldError(error: Pair<Field, String>?, field: Field) {
    if (error?.first == field) {
        Spacer(Modifier.height(6.dp))
        Text(error.second, style = ZcType.caption.copy(color = ZcTokens.danger))
    }
}

@Composable
private fun TrustPrompt(s: OidcState.TrustPrompt, onTrust: () -> Unit, onCancel: () -> Unit) {
    ZcCard(Modifier.fillMaxWidth(), border = ZcTokens.attention.copy(alpha = 0.35f), padding = PaddingValues(20.dp)) {
        Text("Android does not trust this gateway's certificate", style = ZcType.body.copy(fontWeight = FontWeight(600)))
        Spacer(Modifier.height(6.dp))
        Text(s.host, style = ZcType.small.copy(fontFamily = ZcMonoFamily, color = ZcTokens.text))
        Spacer(Modifier.height(14.dp))
        Box(
            Modifier.fillMaxWidth()
                .background(ZcTokens.background, RoundedCornerShape(ZcTokens.inputRadius))
                .border(1.dp, ZcTokens.hairline, RoundedCornerShape(ZcTokens.inputRadius))
                .padding(14.dp),
        ) {
            Text(
                s.sha256.uppercase().chunked(4).joinToString(" "),
                style = TextStyle(fontFamily = ZcMonoFamily, fontSize = 13.sp, lineHeight = 20.sp, color = ZcTokens.text),
            )
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "SHA-256 of the certificate. Trust it only if it matches the one on your gateway. ZeroDroid will accept this exact certificate from now on and ask again if it changes.",
            style = ZcType.small,
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ZcGhostButton("Cancel", modifier = Modifier.weight(1f), onClick = onCancel)
            ZcPrimaryButton("Trust this certificate", icon = ZcIcons.Shield, modifier = Modifier.weight(1.6f), onClick = onTrust)
        }
    }
}

@Composable
private fun Approve(s: OidcState.Approve, onCancel: () -> Unit) {
    val context = LocalContext.current
    // Opens once per grant; a recomposition or a return from the browser does not reopen it.
    var opened by rememberSaveable(s.userCode) { mutableStateOf(false) }
    LaunchedEffect(s.userCode) {
        if (!opened) {
            opened = true
            openSignInPage(context, s.openUri)
        }
    }
    val left by produceState(s.expiresAt - System.currentTimeMillis() / 1000, s.expiresAt) {
        while (true) {
            value = (s.expiresAt - System.currentTimeMillis() / 1000).coerceAtLeast(0)
            delay(1_000)
        }
    }
    var copied by remember(s.userCode) { mutableStateOf(false) }

    ZcCard(Modifier.fillMaxWidth(), padding = PaddingValues(20.dp)) {
        Text("Approve the sign-in in your browser", style = ZcType.body.copy(fontWeight = FontWeight(600)))
        Spacer(Modifier.height(4.dp))
        Text("Check that the page is ${s.verificationHost} and that it shows this code.", style = ZcType.small)
        Spacer(Modifier.height(18.dp))
        Box(
            Modifier.fillMaxWidth()
                .background(ZcTokens.background, RoundedCornerShape(ZcTokens.inputRadius))
                .border(1.dp, ZcTokens.accentStart.copy(alpha = 0.25f), RoundedCornerShape(ZcTokens.inputRadius))
                .clickableNoRipple {
                    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    cm.setPrimaryClip(ClipData.newPlainText("Sign-in code", s.userCode))
                    copied = true
                }
                .padding(vertical = 22.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                s.userCode,
                style = TextStyle(fontFamily = ZcMonoFamily, fontWeight = FontWeight.Bold, fontSize = 34.sp, letterSpacing = 0.08.em, brush = ZcTokens.accentHorizontal),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            (if (copied) "Copied · " else "Tap to copy · ") + "expires in ${left / 60}:${"%02d".format(left % 60)}",
            style = ZcType.caption.copy(color = ZcTokens.faint),
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ZcGhostButton("Cancel", modifier = Modifier.weight(1f), onClick = onCancel)
            ZcPrimaryButton("Open browser", icon = ZcIcons.Link, modifier = Modifier.weight(1.4f)) { openSignInPage(context, s.openUri) }
        }
        Spacer(Modifier.height(12.dp))
        Text("Waiting for the approval…", style = ZcType.caption.copy(color = ZcTokens.muted))
    }
}

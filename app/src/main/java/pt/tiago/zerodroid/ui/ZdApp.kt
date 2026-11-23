package pt.tiago.zerodroid.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import pt.tiago.zerodroid.data.AppPhase
import pt.tiago.zerodroid.data.ZeroDroidConnection
import pt.tiago.zerodroid.ui.chat.ChatScreen
import pt.tiago.zerodroid.ui.chat.ChatViewModel
import pt.tiago.zerodroid.ui.chats.ChatsScreen
import pt.tiago.zerodroid.ui.chats.ChatsViewModel
import pt.tiago.zerodroid.ui.code.CodeScreen
import pt.tiago.zerodroid.ui.code.CodeViewModel
import pt.tiago.zerodroid.ui.home.HomeScreen
import pt.tiago.zerodroid.ui.home.HomeViewModel
import pt.tiago.zerodroid.ui.inbox.InboxScreen
import pt.tiago.zerodroid.ui.inbox.InboxViewModel
import pt.tiago.zerodroid.ui.kit.KitGallery
import pt.tiago.zerodroid.ui.kit.ZcTab
import pt.tiago.zerodroid.ui.kit.ZcTabBar
import pt.tiago.zerodroid.ui.onboarding.OnbState
import pt.tiago.zerodroid.ui.onboarding.OnboardingScreen
import pt.tiago.zerodroid.ui.onboarding.OnboardingViewModel
import pt.tiago.zerodroid.ui.theme.ZcIcons
import pt.tiago.zerodroid.ui.theme.ZcTheme
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

/** A request from outside the UI (notification tap) to open a chat. */
data class OpenChat(val sessionId: String, val agentAlias: String)

@Composable
fun ZdApp(
    onboarding: OnboardingViewModel,
    debug: Boolean,
    externalOpen: OpenChat?,
    onExternalOpenConsumed: () -> Unit,
    onScanQr: () -> Unit,
) {
    val phase by ZeroDroidConnection.phase.collectAsStateWithLifecycle()
    val onb by onboarding.state.collectAsStateWithLifecycle()
    ZcTheme {
        Box(Modifier.fillMaxSize().background(ZcTokens.background)) {
            when (val p = phase) {
                AppPhase.Loading -> Unit
                is AppPhase.Unpaired -> {
                    var oidc by rememberSaveable { mutableStateOf(false) }
                    if (oidc) {
                        val context = LocalContext.current
                        val oidcVm: pt.tiago.zerodroid.ui.onboarding.OidcSignInViewModel = viewModel(key = "oidc-onboard") {
                            pt.tiago.zerodroid.ui.onboarding.oidcSignInViewModel(context, pt.tiago.zerodroid.ui.onboarding.OidcMode.Onboard)
                        }
                        // The view model outlives an unpair: a finished sign-in must not greet the next one.
                        LaunchedEffect(oidcVm) { if (oidcVm.state.value == pt.tiago.zerodroid.ui.onboarding.OidcState.Done) oidcVm.cancel() }
                        androidx.activity.compose.BackHandler { oidcVm.cancel(); oidc = false }
                        pt.tiago.zerodroid.ui.onboarding.OidcSignInScreen(oidcVm, renew = false, onBack = { oidc = false })
                    } else {
                        OnboardingScreen(
                            state = onb,
                            notice = p.notice,
                            onLink = onboarding::onLink,
                            onConfirm = onboarding::onConfirm,
                            onCancel = onboarding::onCancel,
                            onScanQr = onScanQr,
                            onOidc = { oidc = true },
                        )
                    }
                }
                is AppPhase.Paired -> {
                    LaunchedEffect(onb) { if (onb is OnbState.Done) onboarding.onCancel() }
                    PairedShell(p.profile, debug, externalOpen, onExternalOpenConsumed)
                }
            }
        }
    }
}

/** The main shell plus, for OIDC daemons, the sign-in banner on top and the renew sheet over everything. */
@Composable
private fun PairedShell(profile: pt.tiago.zerodroid.data.DaemonProfile, debug: Boolean, externalOpen: OpenChat?, onExternalOpenConsumed: () -> Unit) {
    val signIn by ZeroDroidConnection.signIn.collectAsStateWithLifecycle()
    val renewOpen by ZeroDroidConnection.renewRequested.collectAsStateWithLifecycle()
    val bannerShown = signIn is pt.tiago.zerodroid.data.SignInStatus.Expiring || signIn == pt.tiago.zerodroid.data.SignInStatus.Expired
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (bannerShown) {
                SignInBanner(signIn, Modifier.windowInsetsPadding(WindowInsets.statusBars), onRenew = ZeroDroidConnection::requestRenew)
            }
            Box(
                Modifier.weight(1f).then(
                    if (bannerShown) Modifier.consumeWindowInsets(WindowInsets.statusBars) else Modifier,
                ),
            ) { MainShell(debug, externalOpen, onExternalOpenConsumed) }
        }
        // Outside the `if`: it must survive the sheet closing, or every opening would be generation 0.
        var generation by rememberSaveable { mutableIntStateOf(0) }
        if (renewOpen && profile.isOidc) {
            val context = LocalContext.current
            // The profile as it was when the sheet opened. A successful renew updates the live profile
            // before the sheet closes; keying on the live one would spawn a second renew.
            val opened = remember { profile }
            // A fresh view model per opening: a previous grant's state never leaks into the next one.
            val vm: pt.tiago.zerodroid.ui.onboarding.OidcSignInViewModel = viewModel(key = pt.tiago.zerodroid.ui.onboarding.renewViewModelKey(opened, generation)) {
                pt.tiago.zerodroid.ui.onboarding.oidcSignInViewModel(context, pt.tiago.zerodroid.ui.onboarding.OidcMode.Renew(opened))
            }
            val close = { vm.cancel(); generation++; ZeroDroidConnection.renewHandled() }
            LaunchedEffect(vm) { vm.renew() }
            androidx.activity.compose.BackHandler(onBack = close)
            Box(Modifier.fillMaxSize().background(ZcTokens.background).pointerInput(Unit) { detectTapGestures { } }) {
                pt.tiago.zerodroid.ui.onboarding.OidcSignInScreen(vm, renew = true, onBack = close, onDone = close)
            }
        }
    }
}

@Composable
private fun SignInBanner(status: pt.tiago.zerodroid.data.SignInStatus, modifier: Modifier = Modifier, onRenew: () -> Unit) {
    val now by androidx.compose.runtime.produceState(System.currentTimeMillis() / 1000) {
        while (true) {
            kotlinx.coroutines.delay(15_000)
            value = System.currentTimeMillis() / 1000
        }
    }
    val (text, action, tone) = when (status) {
        is pt.tiago.zerodroid.data.SignInStatus.Expiring -> {
            val min = ((status.expiresAt - now + 59) / 60).coerceAtLeast(1)
            Triple("Sign-in expires in $min min", "Renew", pt.tiago.zerodroid.ui.kit.Tone.Attention)
        }
        else -> Triple("Your sign-in expired", "Sign in again", pt.tiago.zerodroid.ui.kit.Tone.Danger)
    }
    pt.tiago.zerodroid.ui.kit.ConnectionBanner(true, text, tone, modifier, action = action, onAction = onRenew)
}

private const val PREFS = "zerodroid-ui"
private const val TAB_CODE = 2
private const val TAB_INBOX = 3
private const val TAB_CONFIG = 4
private const val ASKED_NOTIF = "asked_notifications"

@Composable
private fun MainShell(debug: Boolean, externalOpen: OpenChat?, onExternalOpenConsumed: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    // A cold start from a notification opens straight on the chat: Home is never drawn first and
    // the chat does not slide in (AnimatedContent does not animate its initial state).
    var chatId by rememberSaveable { mutableStateOf(externalOpen?.sessionId) }
    var chatAlias by rememberSaveable { mutableStateOf(externalOpen?.agentAlias) }
    var gallery by rememberSaveable { mutableStateOf(false) }
    var agentAlias by rememberSaveable { mutableStateOf<String?>(null) }
    var configJump by remember { mutableStateOf<pt.tiago.zerodroid.ui.config.ConfigRoute?>(null) }
    var sopStack by rememberSaveable(stateSaver = pt.tiago.zerodroid.ui.sop.SopStackSaver) { mutableStateOf(emptyList<pt.tiago.zerodroid.ui.sop.SopRoute>()) }
    var canvasRoute by rememberSaveable(stateSaver = pt.tiago.zerodroid.ui.canvas.CanvasRouteSaver) { mutableStateOf<pt.tiago.zerodroid.ui.canvas.CanvasRoute?>(null) }
    var automationStack by rememberSaveable(stateSaver = pt.tiago.zerodroid.ui.cron.AutomationStackSaver) { mutableStateOf(emptyList<pt.tiago.zerodroid.ui.cron.AutomationRoute>()) }
    var doctorOpen by rememberSaveable { mutableStateOf(false) }
    var notifRationale by remember { mutableStateOf(false) }
    val context = LocalContext.current

    val inboxVm: InboxViewModel = viewModel()
    val pendingCount by ZeroDroidConnection.approvals.pendingCount.collectAsStateWithLifecycle()
    val gateCount by inboxVm.gateCount.collectAsStateWithLifecycle()

    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    val askNotifications = {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val needs = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needs && !prefs.getBoolean(ASKED_NOTIF, false)) {
            prefs.edit().putBoolean(ASKED_NOTIF, true).apply()
            notifRationale = true
        }
    }

    LaunchedEffect(externalOpen) {
        if (externalOpen != null) {
            ZeroDroidConnection.chats.get(externalOpen.sessionId, externalOpen.agentAlias)
            chatId = externalOpen.sessionId
            chatAlias = externalOpen.agentAlias
            onExternalOpenConsumed()
        }
    }

    BackHandler(enabled = gallery) { gallery = false }
    BackHandler(enabled = !gallery && chatId != null) { chatId = null }
    BackHandler(enabled = !gallery && chatId == null && tab != 0) { tab = 0 }
    // Declared last so it wins over the chat underneath: Back from Agent info returns to the chat.
    BackHandler(enabled = !gallery && agentAlias != null) { agentAlias = null }

    val tabNames = listOf("home", "chats", "code", "inbox", "config")
    val label = when {
        gallery -> "gallery"
        canvasRoute != null -> "canvas"
        doctorOpen -> "doctor"
        automationStack.isNotEmpty() -> "automation"
        sopStack.isNotEmpty() -> "workflows"
        agentAlias != null -> "agent"
        chatId != null -> "chat"
        else -> tabNames.getOrElse(tab) { "config" }
    }
    androidx.compose.runtime.SideEffect { pt.tiago.zerodroid.Perf.screen = label }

    val openChat: (String, String) -> Unit = { id, alias ->
        pt.tiago.zerodroid.Perf.navigate("chat")
        ZeroDroidConnection.chats.get(id, alias)
        chatId = id
        chatAlias = alias
    }

    // Tabs stay composed under an open chat and across tab switches: Back and tab taps show an
    // existing screen instead of composing one from scratch.
    val chatShown = chatId != null
    val tabsAlpha = animateFloatAsState(if (chatShown) 0f else 1f, tween(if (chatShown) 150 else 180), label = "tabs")
    // With the keyboard up the tabs end at its top edge, so a focused field low on a screen (the last
    // config field) can scroll above it; the tab bar steps aside while typing.
    @OptIn(ExperimentalLayoutApi::class)
    val imeOpen = WindowInsets.isImeVisible
    Box(Modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxSize()
                .skipWhen { chatShown && tabsAlpha.value == 0f }
                .graphicsLayer { alpha = tabsAlpha.value }
                .imePadding(),
        ) {
            KeepAliveTabs(selected = tab, modifier = Modifier.weight(1f).windowInsetsPadding(WindowInsets.statusBars)) { index, selected ->
                val active = selected && !chatShown
                when (index) {
                    0 -> HomeScreen(
                        viewModel<HomeViewModel>(), active = active, onOpenInbox = { pt.tiago.zerodroid.Perf.navigate("inbox"); tab = TAB_INBOX },
                        onBrandLongPress = { if (debug) gallery = true },
                        onOpenAgent = { pt.tiago.zerodroid.Perf.navigate("agent"); agentAlias = it },
                        onOpenWorkflows = { pt.tiago.zerodroid.Perf.navigate("workflows"); sopStack = listOf(pt.tiago.zerodroid.ui.sop.SopRoute.List) },
                        workflowsWaiting = gateCount,
                        onOpenCanvas = { pt.tiago.zerodroid.Perf.navigate("canvas"); canvasRoute = pt.tiago.zerodroid.ui.canvas.CanvasRoute.List },
                        onOpenAutomation = { pt.tiago.zerodroid.Perf.navigate("automation"); automationStack = listOf(pt.tiago.zerodroid.ui.cron.AutomationRoute.List) },
                        onOpenDoctor = { pt.tiago.zerodroid.Perf.navigate("doctor"); doctorOpen = true },
                    )
                    1 -> ChatsScreen(viewModel<ChatsViewModel>(), active = active, onOpen = openChat)
                    TAB_CODE -> CodeScreen(viewModel<CodeViewModel>(), active = active, onOpen = openChat)
                    TAB_INBOX -> InboxScreen(
                        inboxVm, active = active, onOpenChat = openChat,
                        onOpenRun = { n, id -> sopStack = listOf(pt.tiago.zerodroid.ui.sop.SopRoute.Run(n, id)) },
                    )
                    else -> pt.tiago.zerodroid.ui.config.ConfigTab(
                        debug, active = active, jump = configJump, onJumpConsumed = { configJump = null },
                        onOpenDoctor = { pt.tiago.zerodroid.Perf.navigate("doctor"); doctorOpen = true },
                    )
                }
            }
            if (!imeOpen) ZcTabBar(
                tabs = listOf(
                    ZcTab("Home", ZcIcons.Home),
                    ZcTab("Chats", ZcIcons.Chats),
                    ZcTab("Code", ZcIcons.Code),
                    ZcTab("Inbox", ZcIcons.Inbox, badge = pendingCount + gateCount),
                    ZcTab("Config", ZcIcons.Config),
                ),
                selected = tab,
                onSelect = { pt.tiago.zerodroid.Perf.navigate(tabNames.getOrElse(it) { "config" }); tab = it },
            )
        }

        AnimatedContent(
            targetState = chatId,
            transitionSpec = {
                if (targetState != null) {
                    (slideInHorizontally(tween(220)) { it / 4 } + fadeIn(tween(220))) togetherWith fadeOut(tween(150))
                } else {
                    fadeIn(tween(180)) togetherWith (slideOutHorizontally(tween(200)) { it / 4 } + fadeOut(tween(200)))
                }
            },
            label = "nav",
        ) { id ->
            if (id != null) {
                val alias = chatAlias ?: "agent"
                val vm: ChatViewModel = viewModel(key = "chat-$id") { ChatViewModel(ZeroDroidConnection.chats.get(id, alias)) }
                // Opaque, and swallows taps so nothing reaches the tabs underneath during the transition.
                Box(Modifier.fillMaxSize().background(ZcTokens.background).pointerInput(Unit) { detectTapGestures { } }) {
                    ChatScreen(
                        vm, onBack = { pt.tiago.zerodroid.Perf.navigate(tabNames.getOrElse(tab) { "config" }); chatId = null }, onFirstSend = askNotifications,
                        onOpenChat = openChat,
                        onOpenAgent = { agentAlias = it },
                        onOpenCanvas = { c, f -> canvasRoute = pt.tiago.zerodroid.ui.canvas.CanvasRoute.View(c, f, fromChat = true) },
                        onOpenChatCanvases = { sid, ids ->
                            canvasRoute = ids.singleOrNull()?.let { pt.tiago.zerodroid.ui.canvas.CanvasRoute.View(it, fromChat = true) }
                                ?: pt.tiago.zerodroid.ui.canvas.CanvasRoute.ChatList(sid)
                        },
                    )
                }
            } else {
                Box(Modifier.fillMaxSize())
            }
        }
    }

    agentAlias?.let { alias ->
        // pointerInput consumes taps so they never reach the chat top bar underneath.
        Box(Modifier.fillMaxSize().background(ZcTokens.background).pointerInput(Unit) { detectTapGestures { } }) {
            pt.tiago.zerodroid.ui.agent.AgentScreen(
                alias = alias,
                onBack = { agentAlias = null },
                onOpenChat = { id, a -> agentAlias = null; openChat(id, a) },
                onOpenConfig = { a ->
                    agentAlias = null
                    chatId = null
                    tab = TAB_CONFIG
                    configJump = pt.tiago.zerodroid.ui.config.ConfigRoute.Entry("agents", "agents.$a", a)
                },
            )
        }
    }

    if (sopStack.isNotEmpty()) {
        pt.tiago.zerodroid.ui.sop.SopHost(
            stack = sopStack,
            onStack = { sopStack = it },
            onEnableEngine = {
                sopStack = emptyList()
                chatId = null
                tab = TAB_CONFIG
                configJump = pt.tiago.zerodroid.ui.config.ConfigRoute.Field("sop", "", "sop.sops_dir")
            },
        )
    }

    if (automationStack.isNotEmpty()) {
        pt.tiago.zerodroid.ui.cron.AutomationHost(automationStack, onStack = { automationStack = it })
    }

    if (doctorOpen) {
        BackHandler { doctorOpen = false }
        // Opaque, and swallows taps so nothing reaches the screen underneath.
        Box(Modifier.fillMaxSize().background(ZcTokens.background).pointerInput(Unit) { detectTapGestures { } }) {
            pt.tiago.zerodroid.ui.doctor.DoctorScreen(
                onBack = { doctorOpen = false },
                onFix = { fix ->
                    doctorOpen = false
                    chatId = null
                    tab = TAB_CONFIG
                    configJump = (fix as? pt.tiago.zerodroid.ui.doctor.DoctorFix.Open)?.route
                },
            )
        }
    }

    canvasRoute?.let { route ->
        pt.tiago.zerodroid.ui.canvas.CanvasHost(route, onRoute = { canvasRoute = it }, onOpenChat = openChat)
    }

    if (gallery && debug) {
        Box(Modifier.fillMaxSize().background(ZcTokens.background).windowInsetsPadding(WindowInsets.statusBars)) {
            KitGallery(onClose = { gallery = false })
        }
    }

    if (notifRationale) {
        AlertDialog(
            onDismissRequest = { notifRationale = false },
            containerColor = ZcTokens.raised,
            title = { Text("Stay in the loop", style = ZcType.heading) },
            text = { Text("Allow notifications so agents can ask for your approval while you are in another app.", style = ZcType.body.copy(color = ZcTokens.muted)) },
            confirmButton = {
                TextButton(onClick = {
                    notifRationale = false
                    if (Build.VERSION.SDK_INT >= 33) notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }) { Text("Allow", style = ZcType.button.copy(color = ZcTokens.accentStart)) }
            },
            dismissButton = { TextButton(onClick = { notifRationale = false }) { Text("Not now", style = ZcType.button.copy(color = ZcTokens.muted)) } },
        )
    }
}

/** Not measured nor placed (so not drawn) while [hidden] is true; composition is kept. */
private fun Modifier.skipWhen(hidden: () -> Boolean): Modifier = layout { measurable, constraints ->
    if (hidden()) {
        layout(0, 0) {}
    } else {
        val p = measurable.measure(constraints)
        layout(p.width, p.height) { p.place(0, 0) }
    }
}

/**
 * Composes a tab the first time it is selected and keeps it composed afterwards; only the selected
 * one is measured and drawn. [content] gets the tab index and whether it is the selected one, so
 * screens can pause polling and Back handling while hidden.
 */
@Composable
private fun KeepAliveTabs(selected: Int, modifier: Modifier = Modifier, content: @Composable (index: Int, selected: Boolean) -> Unit) {
    val visited = remember { mutableStateListOf<Int>() }
    if (selected !in visited) visited.add(selected)
    Layout(
        modifier = modifier,
        content = {
            visited.forEach { i ->
                key(i) { Box(Modifier.fillMaxSize(), propagateMinConstraints = true) { content(i, i == selected) } }
            }
        },
    ) { measurables, constraints ->
        val index = visited.indexOf(selected)
        val p = measurables.getOrNull(index)?.measure(constraints)
        layout(constraints.maxWidth, constraints.maxHeight) { p?.place(0, 0) }
    }
}

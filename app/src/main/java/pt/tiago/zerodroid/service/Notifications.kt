package pt.tiago.zerodroid.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import pt.tiago.zerodroid.data.SignInStatus
import kotlinx.coroutines.launch
import pt.tiago.zerodroid.MainActivity
import pt.tiago.zerodroid.R
import pt.tiago.zerodroid.data.PendingApproval
import pt.tiago.zerodroid.data.ZeroDroidConnection
import pt.tiago.zerodroid.rpc.proto.ApprovalDecision

object Notifications {
    const val CHANNEL_TURNS = "turns"
    const val CHANNEL_APPROVALS = "approvals"
    const val CHANNEL_FINISHED = "finished"
    const val CHANNEL_SIGNIN = "signin"
    const val EXTRA_RENEW = "pt.tiago.zerodroid.RENEW"
    private const val SIGNIN_ID = 2
    const val ONGOING_ID = 1
    const val EXTRA_SESSION = "pt.tiago.zerodroid.SESSION_ID"
    const val EXTRA_ALIAS = "pt.tiago.zerodroid.AGENT_ALIAS"
    private const val ACCENT = 0xFF22D3EE.toInt()

    fun createChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_TURNS, "Running turns", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while an agent works on something you started"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_APPROVALS, "Approvals", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "An agent needs your permission to run a tool"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_FINISHED, "Finished turns", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "An agent finished something you started while the app was in the background"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_SIGNIN, "Sign-in", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Your OIDC sign-in is about to expire while an agent is working"
            },
        )
    }

    private fun canPost(context: Context) = Build.VERSION.SDK_INT < 33 ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun openAppIntent(context: Context, sessionId: String? = null, alias: String? = null, requestCode: Int = 0): PendingIntent {
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (sessionId != null) putExtra(EXTRA_SESSION, sessionId)
            if (alias != null) putExtra(EXTRA_ALIAS, alias)
        }
        return PendingIntent.getActivity(context, requestCode, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun ongoing(context: Context, agents: List<String>): android.app.Notification = ongoing(context, ongoingText(agents))

    fun ongoing(context: Context, text: String): android.app.Notification {
        val stop = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, ApprovalActionReceiver::class.java).setAction(ApprovalActionReceiver.ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_TURNS)
            .setSmallIcon(R.drawable.ic_stat_zerodroid)
            .setColor(ACCENT)
            .setContentTitle("ZeroDroid")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setContentIntent(openAppIntent(context))
            .addAction(0, "Stop", stop)
            .build()
    }

    private fun approvalId(requestId: String) = 1000 + (requestId.hashCode() and 0x0fffffff)

    fun showApproval(context: Context, a: PendingApproval) {
        if (!canPost(context)) return
        fun action(label: String, decision: String, code: Int): NotificationCompat.Action {
            val pi = PendingIntent.getBroadcast(
                context, approvalId(a.requestId) + code,
                Intent(context, ApprovalActionReceiver::class.java)
                    .setAction(ApprovalActionReceiver.ACTION_DECIDE)
                    .putExtra(ApprovalActionReceiver.EXTRA_REQUEST, a.requestId)
                    .putExtra(ApprovalActionReceiver.EXTRA_DECISION, decision),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            // Approving from the lock screen must require unlocking the device.
            return NotificationCompat.Action.Builder(0, label, pi).setAuthenticationRequired(true).build()
        }
        val seconds = ((a.deadlineAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
        val n = NotificationCompat.Builder(context, CHANNEL_APPROVALS)
            .setSmallIcon(R.drawable.ic_stat_zerodroid)
            .setColor(0xFFF5A524.toInt())
            .setContentTitle("${a.agentAlias} wants to run ${a.toolName}")
            .setContentText(a.summary.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(a.summary.take(600)))
            .setSubText("${seconds / 60}:%02d left".format(seconds % 60))
            .setWhen(a.deadlineAt)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setTimeoutAfter((a.deadlineAt - System.currentTimeMillis()).coerceAtLeast(1_000))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, a.sessionId, a.agentAlias, approvalId(a.requestId)))
            .addAction(action("Deny", ApprovalDecision.DENY, 1))
            .addAction(action("Approve", ApprovalDecision.APPROVE, 2))
            .build()
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED || Build.VERSION.SDK_INT < 33) {
            runCatching { NotificationManagerCompat.from(context).notify(approvalId(a.requestId), n) }
        }
    }

    fun cancelApproval(context: Context, requestId: String) {
        NotificationManagerCompat.from(context).cancel(approvalId(requestId))
    }

    /**
     * Raises a notification for each approval that arrives while the app is not on screen,
     * and removes it once the approval is answered anywhere, expires or is lost.
     */
    fun watchApprovals(context: Context, scope: CoroutineScope) {
        val app = context.applicationContext
        val shown = mutableSetOf<String>()
        scope.launch {
            ZeroDroidConnection.approvals.pending.collect { list ->
                val ids = list.map { it.requestId }.toSet()
                (shown - ids).forEach { cancelApproval(app, it); shown -= it }
                if (!ZeroDroidConnection.isForeground) {
                    list.filter { it.requestId !in shown }.forEach { showApproval(app, it); shown += it.requestId }
                }
            }
        }
        scope.launch {
            ZeroDroidConnection.approvals.resolutions.collect { r -> cancelApproval(app, r.requestId); shown -= r.requestId }
        }
    }

    private fun finishedId(sessionId: String) = 500_000_000 + (sessionId.hashCode() and 0x0fffffff)

    fun showFinished(context: Context, sessionId: String, alias: String, chatTitle: String?, f: FinishedTurn) {
        if (!canPost(context)) return
        val preview = plainPreview(f.text)
        val n = NotificationCompat.Builder(context, CHANNEL_FINISHED)
            .setSmallIcon(R.drawable.ic_stat_zerodroid)
            .setColor(if (f.failed) 0xFFF87171.toInt() else ACCENT)
            .setContentTitle(f.title)
            .setContentText(preview.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview.take(600)))
            .setSubText(chatTitle)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(context, sessionId, alias, finishedId(sessionId)))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(finishedId(sessionId), n) }
    }

    fun cancelFinished(context: Context, sessionId: String) {
        NotificationManagerCompat.from(context).cancel(finishedId(sessionId))
    }

    /**
     * Raises a notification when a turn started on this phone ends while the app is not on
     * screen. A new turn in the same chat replaces the previous one's notification.
     */
    fun watchFinishedTurns(context: Context, scope: CoroutineScope) {
        val app = context.applicationContext
        scope.launch {
            var before = emptyMap<String, String>()
            ZeroDroidConnection.chats.running.collect { now ->
                (now.keys - before.keys).forEach { cancelFinished(app, it) }
                if (!ZeroDroidConnection.isForeground) {
                    (before.keys - now.keys).forEach { id ->
                        val session = ZeroDroidConnection.chats.existing(id) ?: return@forEach
                        val alias = before[id] ?: session.agentAlias
                        val f = finishedTurn(alias, session.state.value) ?: return@forEach
                        showFinished(app, id, alias, session.title.value, f)
                    }
                }
                before = now
            }
        }
    }

    fun showSignIn(context: Context, notice: SignInNotice) {
        if (!canPost(context)) return
        val i = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_RENEW, true)
        }
        val pi = PendingIntent.getActivity(context, SIGNIN_ID, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val text = when (notice) {
            SignInNotice.Expiring -> "Sign in again to keep your agent running"
            SignInNotice.Expired -> "Your sign-in expired. Sign in to continue."
        }
        val n = NotificationCompat.Builder(context, CHANNEL_SIGNIN)
            .setSmallIcon(R.drawable.ic_stat_zerodroid)
            .setColor(ACCENT)
            .setContentTitle("ZeroDroid")
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(SIGNIN_ID, n) }
    }

    fun cancelSignIn(context: Context) = NotificationManagerCompat.from(context).cancel(SIGNIN_ID)

    /** Spec §5.6: ask to renew only while a turn or approval is pending and the app is not on screen. */
    fun watchSignIn(context: Context, scope: CoroutineScope) {
        val app = context.applicationContext
        scope.launch {
            var shown: SignInNotice? = null
            kotlinx.coroutines.flow.combine(
                ZeroDroidConnection.signIn, ZeroDroidConnection.holdForeground, ZeroDroidConnection.foregroundState,
                ZeroDroidConnection.renewRequested,
            ) { status, holding, fg, renewing -> SignInInputs(status, holding, fg, renewing) }
                .collect { (status, holding, fg, renewing) ->
                    val notice = signInNotification(status, holding, fg, shown, renewing)
                    if (notice == null) {
                        if (shown != null) cancelSignIn(app)
                    } else if (notice != shown) {
                        showSignIn(app, notice)
                    }
                    shown = notice
                }
        }
    }
}

enum class SignInNotice { Expiring, Expired }

private data class SignInInputs(val status: SignInStatus, val holding: Boolean, val foreground: Boolean, val renewing: Boolean)

/**
 * Expiry stops the client, which drops running turns, so `holding` goes false at the very moment the
 * sign-in expires: once an Expiring notice was shown, the Expired one is posted regardless.
 */
internal fun signInNotification(
    status: SignInStatus,
    holding: Boolean,
    foreground: Boolean,
    shown: SignInNotice? = null,
    renewing: Boolean = false,
): SignInNotice? = when {
    foreground -> null
    status == SignInStatus.Expired && (holding || shown != null) -> SignInNotice.Expired
    // The user is already in the renew sheet (approving in the browser puts the app in the background).
    !holding || renewing -> null
    status is SignInStatus.Expiring -> SignInNotice.Expiring
    else -> null
}

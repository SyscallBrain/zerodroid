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
import kotlinx.coroutines.launch
import pt.tiago.zerodroid.MainActivity
import pt.tiago.zerodroid.R
import pt.tiago.zerodroid.data.PendingApproval
import pt.tiago.zerodroid.data.ZeroDroidConnection
import pt.tiago.zerodroid.rpc.proto.ApprovalDecision

object Notifications {
    const val CHANNEL_TURNS = "turns"
    const val CHANNEL_APPROVALS = "approvals"
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

    fun ongoing(context: Context, agents: List<String>): android.app.Notification {
        val stop = PendingIntent.getBroadcast(
            context, 1,
            Intent(context, ApprovalActionReceiver::class.java).setAction(ApprovalActionReceiver.ACTION_STOP_ALL),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(context, CHANNEL_TURNS)
            .setSmallIcon(R.drawable.ic_stat_zerodroid)
            .setColor(ACCENT)
            .setContentTitle("ZeroDroid")
            .setContentText(ongoingText(agents))
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
}

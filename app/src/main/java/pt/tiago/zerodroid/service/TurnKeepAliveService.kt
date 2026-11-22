package pt.tiago.zerodroid.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import pt.tiago.zerodroid.data.ZeroDroidConnection

/**
 * Keeps the process, and so the daemon connection, alive while a turn started on this
 * phone runs or one of its approvals waits. The daemon cancels a turn when its
 * connection closes, so without this a turn dies as soon as Samsung freezes the app.
 *
 * Type: `dataSync`. The work is a user-initiated network exchange (the user sent a
 * prompt and the app receives its streamed result), which is what `dataSync` covers on
 * Android 14+. It needs no Play-declared special use, and the service stops itself as
 * soon as no turn or approval is in flight, so it stays far below Android 15's
 * six-hour daily allowance; [onTimeout] stops it cleanly if that is ever reached.
 */
class TurnKeepAliveService : Service() {
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(
            this, Notifications.ONGOING_ID,
            Notifications.ongoing(this, ZeroDroidConnection.chats.running.value.values.toList()),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        scope.launch {
            combine(ZeroDroidConnection.chats.running, ZeroDroidConnection.approvals.pendingCount) { running, pending ->
                ongoingUpdate(running.values.toList(), pending)
            }.collect { text ->
                // Nothing in flight: the service is stopping, so leave the notification alone.
                if (text == null) return@collect
                // Updating our own foreground notification; without the permission it is simply not shown.
                getSystemService(android.app.NotificationManager::class.java)
                    ?.notify(Notifications.ONGOING_ID, Notifications.ongoing(this@TurnKeepAliveService, text))
            }
        }
        scope.launch {
            ZeroDroidConnection.holdForeground.collect { hold -> if (!hold) stopSelf() }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        // Remove the ongoing notification explicitly: an update posted just before the stop
        // would otherwise outlive the service as a plain, undismissable notification.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        NotificationManagerCompat.from(this).cancel(Notifications.ONGOING_ID)
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            runCatching { ContextCompat.startForegroundService(context, Intent(context, TurnKeepAliveService::class.java)) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TurnKeepAliveService::class.java))
        }
    }
}

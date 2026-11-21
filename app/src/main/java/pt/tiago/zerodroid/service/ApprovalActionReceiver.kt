package pt.tiago.zerodroid.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import pt.tiago.zerodroid.data.ZeroDroidConnection

/** Handles notification actions: Approve/Deny an approval, Stop all running turns. */
class ApprovalActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                when (intent.action) {
                    ACTION_DECIDE -> {
                        val id = intent.getStringExtra(EXTRA_REQUEST) ?: return@launch
                        val decision = intent.getStringExtra(EXTRA_DECISION) ?: return@launch
                        withTimeoutOrNull(8_000) { ZeroDroidConnection.approvals.decide(id, decision) }
                        Notifications.cancelApproval(context, id)
                    }
                    ACTION_STOP_ALL -> ZeroDroidConnection.chats.stopAll()
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_DECIDE = "pt.tiago.zerodroid.action.DECIDE"
        const val ACTION_STOP_ALL = "pt.tiago.zerodroid.action.STOP_ALL"
        const val EXTRA_REQUEST = "request_id"
        const val EXTRA_DECISION = "decision"
    }
}

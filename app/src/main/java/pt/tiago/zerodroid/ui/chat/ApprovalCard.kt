package pt.tiago.zerodroid.ui.chat

import android.os.Build
import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import pt.tiago.zerodroid.data.ApprovalStatus
import pt.tiago.zerodroid.rpc.proto.ApprovalDecision
import pt.tiago.zerodroid.ui.kit.CodeBlock
import pt.tiago.zerodroid.ui.kit.ZcCard
import pt.tiago.zerodroid.ui.kit.ZcDangerButton
import pt.tiago.zerodroid.ui.kit.ZcGhostButton
import pt.tiago.zerodroid.ui.kit.ZcPrimaryButton
import pt.tiago.zerodroid.ui.theme.ZcIcons
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val HM = DateTimeFormatter.ofPattern("HH:mm")

fun approvalOutcome(status: ApprovalStatus): String = when (status) {
    ApprovalStatus.Pending -> "Waiting"
    ApprovalStatus.Approved -> "Approved"
    ApprovalStatus.AlwaysApproved -> "Always allowed"
    ApprovalStatus.Denied -> "Denied"
    ApprovalStatus.Expired -> "Expired, denied by the daemon"
    ApprovalStatus.ConnectionLost -> "Connection lost, denied by the daemon"
}

fun formatCountdown(ms: Long): String {
    val s = (ms.coerceAtLeast(0) + 999) / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/**
 * Inline approval. Buttons arm 600 ms after the card first appears, so a tap that was
 * meant for something else can never approve. No swipe gestures.
 */
@Composable
fun ApprovalCard(
    toolName: String,
    summary: String,
    deadlineAt: Long,
    status: ApprovalStatus,
    answeredAt: Long?,
    onDecide: (String) -> Unit,
    modifier: Modifier = Modifier,
    agentAlias: String? = null,
    onOpen: (() -> Unit)? = null,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var armed by remember { mutableStateOf(false) }
    var sent by remember(status) { mutableStateOf(false) }
    val view = LocalView.current
    LaunchedEffect(Unit) {
        delay(600)
        armed = true
    }
    LaunchedEffect(sent) {
        // A decision that failed to send (e.g. offline) re-arms the buttons; a delivered one changes [status].
        if (sent) {
            delay(4_000)
            sent = false
        }
    }
    LaunchedEffect(status) {
        while (status == ApprovalStatus.Pending) {
            now = System.currentTimeMillis()
            delay(250)
        }
    }
    val expired = status == ApprovalStatus.Pending && now >= deadlineAt
    val effective = if (expired) ApprovalStatus.Expired else status

    AnimatedContent(
        targetState = effective == ApprovalStatus.Pending,
        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
        label = "approval",
        modifier = modifier,
    ) { pending ->
        if (pending) {
            ZcCard(
                Modifier.fillMaxWidth(),
                onClick = onOpen,
                border = ZcTokens.attention.copy(alpha = 0.35f),
                background = Brush.verticalGradient(listOf(ZcTokens.attention.copy(alpha = 0.10f), ZcTokens.surface)),
                padding = PaddingValues(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(ZcIcons.Alert, null, tint = ZcTokens.attention, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        buildString {
                            append("Approval needed · ")
                            append(toolName)
                            if (agentAlias != null) append(" · ").append(agentAlias)
                        },
                        style = ZcType.caption.copy(color = ZcTokens.attention),
                        modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    val left = deadlineAt - now
                    Text(
                        formatCountdown(left),
                        style = ZcType.monoSmall.copy(color = if (left < 20_000) ZcTokens.danger else ZcTokens.attention),
                    )
                }
                Spacer(Modifier.height(10.dp))
                CodeBlock(summary.ifBlank { toolName }, maxLines = 12)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val enabled = armed && !sent
                    ZcDangerButton("Deny", enabled = enabled, compact = true, modifier = Modifier.weight(1f)) {
                        sent = true
                        haptic(view, reject = true)
                        onDecide(ApprovalDecision.DENY)
                    }
                    ZcGhostButton("Always", enabled = enabled, compact = true, modifier = Modifier.weight(1f)) {
                        sent = true
                        haptic(view, reject = false)
                        onDecide(ApprovalDecision.ALWAYS)
                    }
                    ZcPrimaryButton("Approve", enabled = enabled, compact = true, modifier = Modifier.weight(1.2f)) {
                        sent = true
                        haptic(view, reject = false)
                        onDecide(ApprovalDecision.APPROVE)
                    }
                }
            }
        } else {
            val good = effective == ApprovalStatus.Approved || effective == ApprovalStatus.AlwaysApproved
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (good) ZcIcons.Check else ZcIcons.Close, null,
                    tint = if (good) ZcTokens.success else if (effective == ApprovalStatus.Denied) ZcTokens.danger else ZcTokens.faint,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    buildString {
                        append(approvalOutcome(effective))
                        append(" · ")
                        append(toolName)
                        val at = answeredAt ?: if (expired) deadlineAt else null
                        if (at != null) append(" · ").append(HM.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())))
                    },
                    style = ZcType.caption,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun haptic(view: android.view.View, reject: Boolean) {
    val c = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        if (reject) HapticFeedbackConstants.REJECT else HapticFeedbackConstants.CONFIRM
    } else HapticFeedbackConstants.VIRTUAL_KEY
    view.performHapticFeedback(c)
}

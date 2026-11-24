package pt.tiago.zerodroid.ui.chat

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import pt.tiago.zerodroid.data.QuestionStatus
import pt.tiago.zerodroid.ui.kit.ZcCard
import pt.tiago.zerodroid.ui.kit.ZcGhostButton
import pt.tiago.zerodroid.ui.kit.ZcPrimaryButton
import pt.tiago.zerodroid.ui.theme.ZcIcons
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val HM = DateTimeFormatter.ofPattern("HH:mm")

fun questionOutcome(status: QuestionStatus): String = when (status) {
    QuestionStatus.Pending -> "Waiting"
    QuestionStatus.Answered -> "Answered"
    QuestionStatus.Declined -> "Declined"
    QuestionStatus.Cancelled -> "Dismissed"
    QuestionStatus.Expired -> "Expired, cancelled"
    QuestionStatus.ConnectionLost -> "Connection lost, cancelled by the daemon"
}

/**
 * A question the agent asked via `elicitation/create` (`ask_user` with choices on the RPC
 * channel). Single-select renders one button per option; multi-select toggles options and
 * confirms with Send. Dismiss answers `cancel`.
 */
@Composable
fun ElicitationCard(
    message: String,
    options: List<String>,
    consts: List<String>,
    multi: Boolean,
    status: QuestionStatus,
    answer: List<String>,
    answeredAt: Long?,
    onAnswer: (List<String>) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    agentAlias: String? = null,
) {
    var selected by remember(consts) { mutableStateOf(setOf<String>()) }
    var sent by remember(status) { mutableStateOf(false) }

    AnimatedContent(
        targetState = status == QuestionStatus.Pending,
        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
        label = "question",
        modifier = modifier,
    ) { pending ->
        if (pending) {
            ZcCard(
                Modifier.fillMaxWidth(),
                border = ZcTokens.accentStart.copy(alpha = 0.35f),
                background = Brush.verticalGradient(listOf(ZcTokens.accentStart.copy(alpha = 0.10f), ZcTokens.surface)),
                padding = PaddingValues(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(ZcIcons.Sparkle, null, tint = ZcTokens.accentStart, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        buildString {
                            append("Question from the agent")
                            if (agentAlias != null) append(" · ").append(agentAlias)
                        },
                        style = ZcType.caption.copy(color = ZcTokens.accentStart),
                        modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(message.ifBlank { "Pick an option" }, style = ZcType.body, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                if (multi) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        options.forEachIndexed { i, label ->
                            val c = consts.getOrNull(i) ?: return@forEachIndexed
                            val on = c in selected
                            ZcGhostButton(
                                text = label,
                                icon = if (on) ZcIcons.Check else null,
                                enabled = !sent,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                selected = if (on) selected - c else selected + c
                            }
                        }
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ZcGhostButton("Dismiss", enabled = !sent, compact = true, modifier = Modifier.weight(1f)) {
                            sent = true
                            onDismiss()
                        }
                        ZcPrimaryButton(
                            "Send",
                            enabled = !sent && selected.isNotEmpty(),
                            compact = true,
                            modifier = Modifier.weight(1f),
                        ) {
                            sent = true
                            onAnswer(selected.toList())
                        }
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        options.forEachIndexed { i, label ->
                            val c = consts.getOrNull(i) ?: return@forEachIndexed
                            ZcGhostButton(label, enabled = !sent, modifier = Modifier.fillMaxWidth()) {
                                sent = true
                                onAnswer(listOf(c))
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    ZcGhostButton("Dismiss", enabled = !sent, compact = true, onClick = {
                        sent = true
                        onDismiss()
                    })
                }
            }
        } else {
            val good = status == QuestionStatus.Answered
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    if (good) ZcIcons.Check else ZcIcons.Close, null,
                    tint = if (good) ZcTokens.success else ZcTokens.faint,
                    modifier = Modifier.size(14.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    buildString {
                        append(questionOutcome(status))
                        if (answer.isNotEmpty()) append(": ").append(answer.joinToString(", "))
                        val at = answeredAt
                        if (at != null) append(" · ").append(HM.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault())))
                    },
                    style = ZcType.caption,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

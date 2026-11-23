package pt.tiago.zerodroid.ui.chat

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.flow.MutableStateFlow
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import pt.tiago.zerodroid.data.MAX_IMAGES
import pt.tiago.zerodroid.data.OutgoingImage
import pt.tiago.zerodroid.data.checkImages
import pt.tiago.zerodroid.data.mergePicked
import pt.tiago.zerodroid.ui.kit.rememberSyncedTextFieldState
import pt.tiago.zerodroid.ui.kit.Skeleton
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import pt.tiago.zerodroid.ui.theme.ZcIcons
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

/**
 * Multiline input. Idle: Send prompts. Running: Send steers the current turn, and with
 * an empty field the button becomes Stop.
 */
@Composable
fun Composer(
    agentAlias: String,
    running: Boolean,
    enabled: Boolean,
    draftText: MutableStateFlow<String>,
    draftImages: MutableStateFlow<List<OutgoingImage>>,
    onSend: (String, List<OutgoingImage>) -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    model: pt.tiago.zerodroid.data.ModelLabel? = null,
    contextFraction: Float? = null,
    onModelClick: () -> Unit = {},
) {
    // The draft lives in the session, so leaving the chat keeps what was typed and picked.
    val text by draftText.collectAsState()
    val field = rememberSyncedTextFieldState(text) { draftText.value = it }
    val images by draftImages.collectAsState()
    var imageError by remember { mutableStateOf<String?>(null) }
    // Informational only (never blocks Send), unlike imageError.
    var imageNotice by remember { mutableStateOf<String?>(null) }
    var loadingImages by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(MAX_IMAGES)) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        loadingImages = true
        scope.launch {
            val loaded = uris.mapNotNull { uri -> runCatching { loadForUpload(context, uri) }.getOrNull() }
            val (merged, notice) = mergePicked(draftImages.value, loaded)
            draftImages.value = merged
            imageError = checkImages(merged)
            imageNotice = notice ?: if (loaded.size < uris.size) "Some images could not be read" else null
            loadingImages = false
        }
    }
    val shape = RoundedCornerShape(22.dp)
    Column(modifier.fillMaxWidth()) {
    model?.let { ModelStrip(it, onModelClick) }
    if (images.isNotEmpty() || loadingImages || imageError != null || imageNotice != null) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            images.forEach { img ->
                Box {
                    val bmp by produceState<android.graphics.Bitmap?>(null, img) { value = thumbnail(img.bytes, 168) }
                    Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(ZcTokens.raised).border(1.dp, ZcTokens.hairline, RoundedCornerShape(10.dp))) {
                        bmp?.let { Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    }
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(2.dp).size(20.dp).clip(CircleShape).background(ZcTokens.scrim)
                            .clickable { draftImages.value = draftImages.value - img; imageError = checkImages(draftImages.value); imageNotice = null },
                        contentAlignment = Alignment.Center,
                    ) { Icon(ZcIcons.Close, "Remove image", tint = ZcTokens.text, modifier = Modifier.size(12.dp)) }
                }
            }
            if (loadingImages) Skeleton(Modifier.size(56.dp))
            imageError?.let { Text(it, style = ZcType.caption.copy(color = ZcTokens.danger)) }
            if (imageError == null) imageNotice?.let { Text(it, style = ZcType.caption.copy(color = ZcTokens.attention)) }
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .background(ZcTokens.surface, shape)
            .border(1.dp, if (running) ZcTokens.accentStart.copy(alpha = 0.3f) else ZcTokens.hairlineStrong, shape)
            .padding(start = 16.dp, end = 6.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(
            Modifier.padding(end = 4.dp, top = 3.dp, bottom = 3.dp).size(40.dp).clip(CircleShape)
                .clickable(enabled = enabled && !running && images.size < MAX_IMAGES, role = Role.Button) {
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(ZcIcons.Image, "Attach images", tint = if (enabled && !running) ZcTokens.muted else ZcTokens.faint, modifier = Modifier.size(20.dp))
        }
        Box(Modifier.weight(1f).heightIn(min = 46.dp).padding(vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
            if (text.isEmpty()) {
                Text(
                    when {
                        !enabled -> "Waiting for the daemon…"
                        running -> "Steer $agentAlias…"
                        else -> "Message $agentAlias…"
                    },
                    style = ZcType.body.copy(color = ZcTokens.faint),
                    maxLines = 1,
                )
            }
            BasicTextField(
                state = field,
                enabled = enabled,
                textStyle = ZcType.body,
                cursorBrush = SolidColor(ZcTokens.accentStart),
                lineLimits = TextFieldLineLimits.MultiLine(maxHeightInLines = 6),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.width(8.dp))
        val showStop = running && text.isBlank()
        val canSend = enabled && !loadingImages && imageError == null && (text.isNotBlank() || (images.isNotEmpty() && !running))
        // The context ring adds 3dp around the 40dp button; top/bottom padding drops from 6dp to 3dp so the composer keeps its height.
        ContextRing(contextFraction, Modifier.size(46.dp)) {
        AnimatedContent(
            targetState = showStop,
            transitionSpec = { (fadeIn(tween(150)) + scaleIn(initialScale = 0.8f)) togetherWith fadeOut(tween(100)) },
            label = "send",
        ) { stop ->
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .then(
                        when {
                            stop -> Modifier.background(ZcTokens.raised).border(1.dp, ZcTokens.hairlineStrong, CircleShape)
                            canSend -> Modifier.background(ZcTokens.accent)
                            else -> Modifier.background(ZcTokens.raised)
                        },
                    )
                    .clickable(enabled = stop || canSend, role = Role.Button) {
                        if (stop) onStop() else onSend(text, if (running) emptyList() else images)
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (stop) ZcIcons.Stop else ZcIcons.Send,
                    if (stop) "Stop" else if (running) "Steer" else "Send",
                    tint = when {
                        stop -> ZcTokens.text
                        canSend -> ZcTokens.onAccent
                        else -> ZcTokens.faint
                    },
                    modifier = Modifier.size(if (stop) 18.dp else 20.dp),
                )
            }
        }
        }
    }
    }
}

package pt.tiago.zerodroid.ui.skills

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pt.tiago.zerodroid.data.ZeroDroidConnection
import pt.tiago.zerodroid.rpc.ConnState
import pt.tiago.zerodroid.ui.kit.ConnectionBanner
import pt.tiago.zerodroid.ui.kit.SectionHeader
import pt.tiago.zerodroid.ui.kit.SkeletonLines
import pt.tiago.zerodroid.ui.kit.StatusPill
import pt.tiago.zerodroid.ui.kit.Tone
import pt.tiago.zerodroid.ui.kit.ZcIconButton
import pt.tiago.zerodroid.ui.kit.ZcPrimaryButton
import pt.tiago.zerodroid.ui.kit.ZcSwitch
import pt.tiago.zerodroid.ui.kit.ZcTextField
import pt.tiago.zerodroid.ui.kit.ZcTopBar
import pt.tiago.zerodroid.ui.sop.ConfirmDialog
import pt.tiago.zerodroid.ui.sop.FieldLabel
import pt.tiago.zerodroid.ui.theme.ZcIcons
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillEditorScreen(vm: SkillEditorViewModel, onBack: () -> Unit, onDeleted: () -> Unit) {
    val s by vm.state.collectAsStateWithLifecycle()
    val conn by ZeroDroidConnection.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }
    LaunchedEffect(s.deleted) { if (s.deleted) onDeleted() }
    var discardAsk by remember { mutableStateOf(false) }
    var deleteAsk by remember { mutableStateOf(false) }
    val back = { if (s.dirty) discardAsk = true else onBack() }
    BackHandler(onBack = back)
    val set = vm::update

    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.statusBars).windowInsetsPadding(WindowInsets.ime)) {
        ZcTopBar(
            "Edit skill",
            subtitle = { Text("${s.bundle} / ${s.name}", style = ZcType.caption.copy(color = ZcTokens.faint), maxLines = 1) },
            navigation = { ZcIconButton(ZcIcons.Close, "Close", onClick = back) },
            actions = {
                ZcIconButton(ZcIcons.Trash, "Delete skill", tint = ZcTokens.danger, onClick = { deleteAsk = true })
                Spacer(Modifier.width(4.dp))
                ZcPrimaryButton(
                    when {
                        s.saving -> "Saving…"
                        s.saved && !s.dirty -> "Saved"
                        else -> "Save"
                    },
                    compact = true,
                    modifier = Modifier.padding(end = 12.dp),
                    enabled = s.dirty && !s.saving && !s.deleted && conn is ConnState.Ready,
                    onClick = vm::save,
                )
            },
        )
        ConnectionBanner(s.error != null, s.error ?: "", Tone.Danger, action = "OK", onAction = vm::consumeError)
        if (s.loading) {
            SkeletonLines(5, Modifier.padding(ZcTokens.gutter))
            return@Column
        }
        if (s.forbidden && s.originalSnapshot.isEmpty()) return@Column
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = ZcTokens.gutter).padding(bottom = 48.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(s.name, style = ZcType.heading, modifier = Modifier.weight(1f))
                StatusPill(s.bundle, Tone.Neutral)
            }

            SectionHeader("Frontmatter", Modifier.padding(top = 10.dp))
            FieldLabel("Description")
            ZcTextField(s.description, { v -> set { it.copy(description = v, saved = false) } }, "What this skill does and when to use it", modifier = Modifier.fillMaxWidth())
            FieldLabel("License")
            ZcTextField(s.license, { v -> set { it.copy(license = v, saved = false) } }, "Optional", modifier = Modifier.fillMaxWidth())
            FieldLabel("Author")
            ZcTextField(s.author, { v -> set { it.copy(author = v, saved = false) } }, "Optional", modifier = Modifier.fillMaxWidth())
            FieldLabel("Version")
            ZcTextField(s.version, { v -> set { it.copy(version = v, saved = false) } }, "Optional", modifier = Modifier.fillMaxWidth())
            FieldLabel("Category")
            ZcTextField(s.category, { v -> set { it.copy(category = v, saved = false) } }, "Optional", modifier = Modifier.fillMaxWidth())
            FieldLabel("Tags (comma separated)")
            ZcTextField(s.tags, { v -> set { it.copy(tags = v, saved = false) } }, "e.g. research, web", modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Always in system prompt", style = ZcType.body)
                    Text("Keep this skill's instructions inlined even in compact mode", style = ZcType.caption.copy(color = ZcTokens.faint))
                }
                ZcSwitch(s.always, { v -> set { it.copy(always = v, saved = false) } })
            }

            SectionHeader("SKILL.md body", Modifier.padding(top = 16.dp))
            ZcTextField(
                s.body, { v -> set { it.copy(body = v, saved = false) } }, "Markdown instructions…",
                singleLine = false, maxLines = 24, mono = true, modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    if (discardAsk) {
        ConfirmDialog(
            "Discard changes?", "Your edits to ${s.name} are not saved.", "Discard", danger = true,
            onConfirm = { discardAsk = false; onBack() }, onDismiss = { discardAsk = false },
        )
    }
    if (deleteAsk) {
        ConfirmDialog(
            "Delete ${s.name}?", "The skill is archived on the daemon (recoverable from its archive), not purged.", "Delete", danger = true,
            onConfirm = { deleteAsk = false; vm.delete() }, onDismiss = { deleteAsk = false },
        )
    }
}

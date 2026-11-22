package pt.tiago.zerodroid.ui.sop

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import pt.tiago.zerodroid.data.sop.SopDraft
import pt.tiago.zerodroid.rpc.proto.GraphNodeDto
import pt.tiago.zerodroid.rpc.proto.GraphWireDto
import pt.tiago.zerodroid.rpc.proto.SopGraphDto
import pt.tiago.zerodroid.ui.theme.ZcIcons
import pt.tiago.zerodroid.ui.theme.ZcTokens
import pt.tiago.zerodroid.ui.theme.ZcType

private val RowGap = 46.dp
private val TopPad = 10.dp
private val ChipShape = RoundedCornerShape(9.dp)
private val CardShape = RoundedCornerShape(16.dp)

fun roleColor(r: WireRole): Color = when (r) {
    WireRole.Sequence -> ZcTokens.success
    WireRole.Dependency -> ZcTokens.attention
    WireRole.Failure -> ZcTokens.danger
    WireRole.Switch -> ZcTokens.accentEnd
    WireRole.Trigger -> ZcTokens.accentStart
    WireRole.Data -> ZcTokens.accentEnd.copy(alpha = 0.5f)
}

fun portColor(r: PortRole): Color = when (r) {
    PortRole.Next -> ZcTokens.success
    PortRole.OnFailure -> ZcTokens.danger
    PortRole.WaitsFor -> ZcTokens.attention
    PortRole.Branch -> ZcTokens.accentEnd
}

private fun portGlyph(r: PortRole) = when (r) {
    PortRole.Next -> "↓"
    PortRole.OnFailure -> "⚠"
    PortRole.WaitsFor -> "⧗"
    PortRole.Branch -> "⑂"
}

/** Run state of a step for the live run view: `pending`, `active`, `completed`, `failed`, `skipped`. */
typealias RunStates = Map<Int, String>

private sealed interface FlowId {
    data class Node(val step: Int) : FlowId
    data object NewSlot : FlowId
}

/** Card bounds from the last layout pass, read by the wire painter in the same frame. */
private class Bounds { val map = HashMap<Int, Rect>() }

/**
 * The vertical flow: trigger pills on top, step cards below by depth, lanes side by side, wires
 * painted behind. Pinch zoom (0.6–1.0×) and horizontal scroll only when the lanes don't fit.
 * Read-only when [run] is not null (live run view).
 */
@Composable
fun FlowGraph(
    graph: SopGraphDto,
    draft: JsonObject,
    mode: EditorMode,
    modifier: Modifier = Modifier,
    run: RunStates? = null,
    scroll: ScrollState = rememberScrollState(),
    bottomPadding: Dp = 96.dp,
    onCard: (Int) -> Unit = {},
    onCardLongPress: (Int) -> Unit = {},
    onPort: (Int, PortChip) -> Unit = { _, _ -> },
    onTarget: (Int) -> Unit = {},
    onNewStepTarget: () -> Unit = {},
    onDiagnostic: (String) -> Unit = {},
    cardExtra: (@Composable (Int) -> Unit)? = null,
) {
    BoxWithConstraints(modifier) {
        val plan = remember(graph, maxWidth) { planFlow(graph, maxWidth.value) }
        var zoom by remember { mutableFloatStateOf(1f) }
        val base = LocalDensity.current
        val pinch = if (!plan.zoomable) Modifier else Modifier.pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                do {
                    val e = awaitPointerEvent(PointerEventPass.Initial)
                    if (e.changes.count { it.pressed } >= 2) {
                        zoom = (zoom * e.calculateZoom()).coerceIn(0.6f, 1f)
                        e.changes.forEach { if (it.positionChanged()) it.consume() }
                    }
                } while (e.changes.any { it.pressed })
            }
        }
        val hScroll = rememberScrollState()
        Box(
            Modifier.fillMaxSize().then(pinch).verticalScroll(scroll)
                .then(if (plan.zoomable) Modifier.horizontalScroll(hScroll) else Modifier),
        ) {
            CompositionLocalProvider(LocalDensity provides Density(base.density * zoom, base.fontScale)) {
                FlowCanvas(plan, graph, draft, mode, run, bottomPadding, onCard, onCardLongPress, onPort, onTarget, onNewStepTarget, onDiagnostic, cardExtra)
            }
        }
    }
}

@Composable
private fun FlowCanvas(
    plan: FlowPlan,
    graph: SopGraphDto,
    draft: JsonObject,
    mode: EditorMode,
    run: RunStates?,
    bottomPadding: Dp,
    onCard: (Int) -> Unit,
    onCardLongPress: (Int) -> Unit,
    onPort: (Int, PortChip) -> Unit,
    onTarget: (Int) -> Unit,
    onNewStepTarget: () -> Unit,
    onDiagnostic: (String) -> Unit,
    cardExtra: (@Composable (Int) -> Unit)?,
) {
    val bounds = remember { Bounds() }
    val nodes = remember(graph) { graph.nodes.associateBy { it.step } }
    val diags = remember(graph) { graph.diagnostics.groupBy { it.step } }
    val connect = mode as? EditorMode.Connect
    val measurer = rememberTextMeasurer()
    val dim by animateFloatAsState(if (connect != null) 0.35f else 1f, tween(150), label = "dim")
    val switchNames = remember(draft) {
        SopDraft.steps(draft).associate { s ->
            ((s["number"] as? JsonPrimitive)?.content?.toIntOrNull() ?: -1) to
                SopDraft.switchRules(s).map { (it["name"] as? JsonPrimitive)?.content.orEmpty() }
        }
    }

    Layout(
        modifier = Modifier.drawBehind {
            graph.wires.forEach { w ->
                val a = bounds.map[w.fromStep] ?: return@forEach
                val b = bounds.map[w.toStep] ?: return@forEach
                val label = if (w.flowRole == "switch") w.fromPin else null
                drawWire(w, a, b, size.width, dim, label, measurer, switchNames[w.fromStep])
            }
        },
        content = {
            plan.slots.forEach { slot ->
                val node = nodes[slot.step] ?: return@forEach
                Box(Modifier.layoutId(FlowId.Node(slot.step))) {
                    if (slot.isTrigger) {
                        TriggerPill(node, Modifier.alpha(dim))
                    } else {
                        val isTarget = connect != null && slot.step != connect.from
                        StepCard(
                            node = node,
                            step = SopDraft.step(draft, slot.step),
                            chips = if (run == null) portsFor(slot.step, draft, graph) else emptyList(),
                            diagnostics = diags[slot.step].orEmpty().map { it.severity to it.message },
                            connect = connect,
                            isTarget = isTarget,
                            runState = run?.get(slot.step),
                            onClick = { if (connect != null) { if (isTarget) onTarget(slot.step) } else onCard(slot.step) },
                            onLongClick = { if (connect == null && run == null) onCardLongPress(slot.step) },
                            onPort = { onPort(slot.step, it) },
                            onDiagnostic = onDiagnostic,
                            extra = cardExtra?.let { { it(slot.step) } },
                        )
                    }
                }
            }
            if (connect != null) {
                Box(
                    Modifier.layoutId(FlowId.NewSlot).clip(CardShape)
                        .dashed(ZcTokens.accentStart.copy(alpha = 0.55f), 16.dp)
                        .combinedClickable(onClick = onNewStepTarget),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("+  New step, connected here", style = ZcType.button.copy(color = ZcTokens.accentStart), modifier = Modifier.padding(vertical = 15.dp))
                }
            }
        },
    ) { measurables, constraints ->
        val canvasW = plan.canvasWidth.dp.roundToPx().coerceAtMost(if (constraints.hasBoundedWidth) maxOf(constraints.maxWidth, plan.canvasWidth.dp.roundToPx()) else Int.MAX_VALUE)
        val cardW = plan.cardWidth.dp.roundToPx()
        val margin = FLOW_MARGIN.dp.roundToPx()
        val laneGap = FLOW_LANE_GAP.dp.roundToPx()
        val rowGap = RowGap.roundToPx()
        val loose = androidx.compose.ui.unit.Constraints()
        val placeables = measurables.associate { m ->
            val id = m.layoutId as FlowId
            id to when (id) {
                is FlowId.Node -> if (plan.slots.first { it.step == id.step }.isTrigger) m.measure(loose)
                else m.measure(androidx.compose.ui.unit.Constraints.fixedWidth(cardW))
                FlowId.NewSlot -> m.measure(androidx.compose.ui.unit.Constraints.fixedWidth(canvasW - 2 * margin))
            }
        }
        bounds.map.clear()
        var y = TopPad.roundToPx()
        val byDepth = plan.slots.filter { placeables.containsKey(FlowId.Node(it.step)) }.groupBy { it.depth }.toSortedMap()
        val placed = mutableListOf<Triple<androidx.compose.ui.layout.Placeable, Int, Int>>()
        byDepth.forEach { (_, row) ->
            val items = row.sortedBy { it.lane }.map { it to placeables.getValue(FlowId.Node(it.step)) }
            val gap = if (row.first().isTrigger) 10.dp.roundToPx() else laneGap
            val total = items.sumOf { it.second.width } + gap * (items.size - 1)
            var x = ((canvasW - total) / 2).coerceAtLeast(margin)
            val h = items.maxOf { it.second.height }
            items.forEach { (slot, p) ->
                placed += Triple(p, x, y)
                bounds.map[slot.step] = Rect(x.toFloat(), y.toFloat(), (x + p.width).toFloat(), (y + p.height).toFloat())
                x += p.width + gap
            }
            y += h + rowGap
        }
        placeables[FlowId.NewSlot]?.let { p -> placed += Triple(p, margin, y); y += p.height + rowGap }
        val height = y - rowGap + bottomPadding.roundToPx()
        layout(canvasW, height) { placed.forEach { (p, x, py) -> p.place(x, py) } }
    }
}

private fun DrawScope.drawWire(
    w: GraphWireDto,
    a: Rect,
    b: Rect,
    canvasW: Float,
    dim: Float,
    label: String?,
    measurer: androidx.compose.ui.text.TextMeasurer,
    switchNames: List<String>?,
) {
    val role = wireRole(w)
    val color = roleColor(role).let { it.copy(alpha = it.alpha * dim) }
    val stroke = when (role) {
        WireRole.Data -> Stroke(1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx())))
        WireRole.Dependency, WireRole.Failure, WireRole.Trigger ->
            Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx())))
        else -> Stroke(2.dp.toPx())
    }
    // Spread the start points so wires leaving one card don't overlap.
    val sx = when (role) {
        WireRole.Switch -> {
            val n = (switchNames?.size ?: 1).coerceAtLeast(1)
            val i = switchNames?.indexOf(label)?.takeIf { it >= 0 } ?: 0
            a.left + a.width * (i + 1) / (n + 1)
        }
        WireRole.Failure -> a.right - a.width * 0.18f
        WireRole.Dependency -> a.left + a.width * 0.18f
        WireRole.Data -> a.center.x + 10.dp.toPx()
        else -> a.center.x
    }
    val path = Path()
    val end: Offset
    val forward = b.top >= a.bottom - 1f
    if (forward) {
        val ex = when (role) {
            WireRole.Dependency -> b.left + b.width * 0.18f
            WireRole.Failure -> b.right - b.width * 0.18f
            WireRole.Data -> b.center.x + 10.dp.toPx()
            else -> b.center.x
        }
        val start = Offset(sx, a.bottom)
        end = Offset(ex, b.top)
        val my = (start.y + end.y) / 2
        path.moveTo(start.x, start.y)
        path.cubicTo(start.x, my, end.x, my, end.x, end.y)
        drawPath(path, color, style = stroke)
        if (role != WireRole.Data) arrow(end, down = true, color)
    } else {
        // Back edge (loop or retry): out the right side, along the canvas edge, back in on the right.
        val edge = canvasW - 7.dp.toPx()
        val sy = a.center.y
        val ty = b.center.y
        end = Offset(b.right, ty)
        path.moveTo(a.right, sy)
        path.cubicTo(edge, sy, edge, sy, edge, (sy + ty) / 2)
        path.cubicTo(edge, ty, edge, ty, end.x, end.y)
        drawPath(path, color, style = stroke)
        arrow(end, down = false, color)
    }
    if (role == WireRole.Switch && !label.isNullOrBlank()) {
        val t = measurer.measure(label, TextStyle(fontSize = 10.5.sp, color = ZcTokens.text.copy(alpha = dim)))
        val pad = 6.dp.toPx()
        val cx = if (forward) (sx + end.x) / 2 else canvasW - 7.dp.toPx()
        val cy = if (forward) (a.bottom + end.y) / 2 else (a.center.y + end.y) / 2
        val box = Rect(cx - t.size.width / 2f - pad, cy - t.size.height / 2f - 2.dp.toPx(), cx + t.size.width / 2f + pad, cy + t.size.height / 2f + 2.dp.toPx())
        drawRoundRect(ZcTokens.background, box.topLeft, box.size, CornerRadius(box.height / 2))
        drawRoundRect(roleColor(WireRole.Switch).copy(alpha = 0.6f * dim), box.topLeft, box.size, CornerRadius(box.height / 2), style = Stroke(1.dp.toPx()))
        drawText(t, topLeft = Offset(cx - t.size.width / 2f, cy - t.size.height / 2f))
    }
}

private fun DrawScope.arrow(tip: Offset, down: Boolean, color: Color) {
    val s = 5.dp.toPx()
    val p = Path()
    if (down) {
        p.moveTo(tip.x - s, tip.y - s * 1.3f); p.lineTo(tip.x, tip.y); p.lineTo(tip.x + s, tip.y - s * 1.3f)
    } else {
        p.moveTo(tip.x + s * 1.3f, tip.y - s); p.lineTo(tip.x, tip.y); p.lineTo(tip.x + s * 1.3f, tip.y + s)
    }
    drawPath(p, color, style = Stroke(2.dp.toPx(), cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
}

/** A dashed rounded outline, for things not wired yet. */
fun Modifier.dashed(color: Color, radius: Dp): Modifier = drawBehind {
    val r = radius.toPx()
    drawRoundRect(
        color, cornerRadius = CornerRadius(r),
        style = Stroke(1.2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
    )
}

@Composable
private fun TriggerPill(node: GraphNodeDto, modifier: Modifier = Modifier) {
    Row(
        modifier.height(34.dp).clip(RoundedCornerShape(50))
            .background(ZcTokens.accentStart.copy(alpha = 0.07f))
            .dashed(ZcTokens.accentStart.copy(alpha = 0.6f), 17.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(ZcIcons.Bolt, null, tint = ZcTokens.accentStart, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        val text = listOfNotNull(node.title.ifBlank { null }?.let(::triggerTypeLabel), node.subtitle?.takeIf { it.isNotBlank() && it != node.title })
            .joinToString(" · ")
        Text(text.ifBlank { "Trigger" }, style = ZcType.button.copy(color = ZcTokens.accentStart), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private fun kindLabel(step: JsonObject?): String {
    if (step == null) return ""
    if (SopDraft.switchRules(step).isNotEmpty()) return "BRANCHES"
    return when ((step["kind"] as? JsonPrimitive)?.content) {
        "checkpoint" -> "CHECKPOINT"
        "capability" -> "CAPABILITY"
        else -> "EXECUTE"
    }
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
private fun StepCard(
    node: GraphNodeDto,
    step: JsonObject?,
    chips: List<PortChip>,
    diagnostics: List<Pair<String, String>>,
    connect: EditorMode.Connect?,
    isTarget: Boolean,
    runState: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onPort: (PortChip) -> Unit,
    onDiagnostic: (String) -> Unit,
    extra: (@Composable () -> Unit)?,
) {
    val isSource = connect?.from == node.step
    val pulse = if (runState == "active") {
        rememberInfiniteTransition(label = "pulse").animateFloat(0.25f, 0.9f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a").value
    } else 0f
    val border = when {
        isSource -> portColor(connect!!.role)
        isTarget -> ZcTokens.accentStart
        runState == "completed" -> ZcTokens.success.copy(alpha = 0.45f)
        runState == "active" -> ZcTokens.accentStart.copy(alpha = pulse)
        runState == "failed" -> ZcTokens.danger
        runState == "waiting" -> ZcTokens.attention
        else -> ZcTokens.hairlineStrong
    }
    val faded = runState == "pending" || runState == "skipped"
    val bg = if (runState == "waiting") ZcTokens.attention.copy(alpha = 0.06f) else ZcTokens.surface
    Column(
        Modifier
            .then(if (isTarget) Modifier.shadow(14.dp, CardShape, ambientColor = ZcTokens.accentStart, spotColor = ZcTokens.accentStart) else Modifier)
            .clip(CardShape)
            .background(bg)
            .border(if (isSource || isTarget || runState == "active" || runState == "waiting") 1.5.dp else 1.dp, border, CardShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .alpha(if (faded) 0.5f else 1f)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepBadge(node.step, runState)
            Spacer(Modifier.width(8.dp))
            Text(
                node.title.ifBlank { (step?.get("title") as? JsonPrimitive)?.content?.ifBlank { null } ?: "Untitled step" },
                style = ZcType.heading.copy(fontSize = 14.sp),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (isTarget) {
                Text("Tap to connect", style = ZcType.label.copy(color = ZcTokens.accentStart))
            } else if (runState == null) {
                Text(kindLabel(step), style = ZcType.label)
            }
            diagnostics.firstOrNull()?.let { (sev, _) ->
                Spacer(Modifier.width(6.dp))
                val c = if (sev == "error") ZcTokens.danger else ZcTokens.attention
                Box(
                    Modifier.size(22.dp).clip(RoundedCornerShape(6.dp)).background(c.copy(alpha = 0.16f))
                        .combinedClickable { onDiagnostic(diagnostics.joinToString("\n") { it.second }) },
                    contentAlignment = Alignment.Center,
                ) { Text("!", style = ZcType.label.copy(color = c, fontSize = 12.sp)) }
            }
        }
        val sub = node.subtitle?.takeIf { it.isNotBlank() }
            ?: if (runState == "skipped") "Skipped" else null
        if (sub != null) {
            Text(sub, style = ZcType.small.copy(fontSize = 12.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
        }
        if (chips.isNotEmpty()) {
            FlowRow(
                Modifier.padding(top = 6.dp).alpha(if (connect != null && !isSource) 0.4f else 1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                chips.forEach { c -> PortChipView(c, active = isSource && connect?.role == c.role && connect.port == c.port, enabled = connect == null) { onPort(c) } }
            }
        }
        extra?.invoke()
    }
}

@Composable
private fun StepBadge(step: Int, runState: String?) {
    val (bg, fg, text) = when (runState) {
        "completed" -> Triple(ZcTokens.success.copy(alpha = 0.14f), ZcTokens.success, "✓")
        "failed" -> Triple(ZcTokens.danger.copy(alpha = 0.14f), ZcTokens.danger, "✕")
        "active" -> Triple(ZcTokens.accentStart.copy(alpha = 0.14f), ZcTokens.accentStart, "$step")
        "waiting" -> Triple(ZcTokens.attention.copy(alpha = 0.16f), ZcTokens.attention, "$step")
        else -> Triple(ZcTokens.raised, ZcTokens.muted, "$step")
    }
    Box(
        Modifier.size(22.dp).clip(RoundedCornerShape(7.dp)).background(bg).border(1.dp, ZcTokens.hairlineStrong, RoundedCornerShape(7.dp)),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = ZcType.label.copy(color = fg, fontSize = 11.5.sp)) }
}

/** A port chip: 30 dp pill inside a 40 dp touch row. Dashed when not wired. */
@Composable
private fun PortChipView(c: PortChip, active: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val color = portColor(c.role)
    Box(Modifier.heightIn(min = 40.dp).combinedClickable(enabled = enabled, onClick = onClick), contentAlignment = Alignment.Center) {
        Row(
            Modifier.height(30.dp).clip(ChipShape)
                .then(
                    when {
                        active -> Modifier.background(color.copy(alpha = 0.2f)).border(1.5.dp, color, ChipShape)
                        c.connected || c.role == PortRole.OnFailure && c.label.startsWith("retries") -> Modifier.background(color.copy(alpha = 0.11f))
                        else -> Modifier.dashed(color.copy(alpha = 0.5f), 9.dp)
                    },
                )
                .padding(horizontal = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${portGlyph(c.role)} ${c.label}", style = ZcType.caption.copy(color = color, fontSize = 12.sp), maxLines = 1)
        }
    }
}

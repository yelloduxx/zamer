package dev.bezh.zamer.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.HorizontalRule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bezh.zamer.R
import dev.bezh.zamer.domain.Formatter
import dev.bezh.zamer.domain.GRID_MM
import dev.bezh.zamer.domain.Pt
import dev.bezh.zamer.domain.Rect
import dev.bezh.zamer.domain.Segment
import dev.bezh.zamer.domain.SideRef
import dev.bezh.zamer.domain.Sketch
import dev.bezh.zamer.domain.SketchShape
import dev.bezh.zamer.domain.Snap
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor

/** On-screen spacing of the dots, [GRID_MM] apart, at zoom 1. */
private val GridSpacing = 24.dp

/** Dots closer than this on screen are thinned out to every few. */
private val MinDotGap = 12.dp

/** How many dot spacings apart the shown dots may be, from 0.5 m up to 10 m. */
private val DotSteps = listOf(1, 2, 10, 20)

private const val MIN_SCALE = 0.1f
private const val MAX_SCALE = 4f

/** Fitting a small sketch stops zooming in here, so a single short line doesn't fill the screen. */
private const val FIT_MAX_SCALE = 2f
private val FitPadding = 40.dp

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SketchScreen(
    vm: SketchViewModel,
    onBack: () -> Unit,
    onPair: () -> Unit,
    onRequestPermission: () -> Unit,
    onEnableBluetooth: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val formatter = remember(ui.settings.units, ui.settings.decimals) {
        Formatter(ui.settings.units, ui.settings.decimals)
    }
    val haptics = LocalHapticFeedback.current
    // The view model outlives this screen; a reading it took before the screen reopened must not buzz.
    val countOnEntry = remember { ui.assignedCount }
    LaunchedEffect(ui.assignedCount) {
        if (ui.assignedCount > countOnEntry && ui.settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
    }
    var tool by rememberSaveable { mutableStateOf(SketchTool.LINE) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var fits by remember { mutableIntStateOf(0) }
    val sketch = ui.sketch ?: Sketch()
    // The side whose length is being typed; it is also the target, so the typed value goes where a reading would.
    var editing by remember { mutableStateOf<SideRef?>(null) }
    LaunchedEffect(sketch.target) { if (sketch.target != editing) editing = null }
    BackHandler(enabled = editing != null) { editing = null }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val inset = Modifier.padding(horizontal = ScreenPadding)
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = ScreenPadding, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            Text(
                stringResource(R.string.sketch_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            IconButton(onClick = { fits++ }, enabled = sketch.shapes.isNotEmpty()) {
                Icon(Icons.Rounded.FitScreen, contentDescription = stringResource(R.string.sketch_fit))
            }
            ConnectionPill(
                link = ui.link,
                onRequestPermission = onRequestPermission,
                onEnableBluetooth = onEnableBluetooth,
                onPair = onPair,
                compact = true,
            )
        }
        Box(inset.weight(1f).fillMaxWidth()) {
        SketchCanvas(
            sketch = sketch,
            tool = tool,
            formatter = formatter,
            fits = fits,
            onDraw = { a, b -> vm.draw(tool, a, b) },
            onSelect = vm::select,
            onEdit = {
                vm.select(it)
                editing = it
            },
            onMove = vm::move,
            onPickUp = { if (ui.settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.LongPress) },
            onTouch = { editing = null },
            modifier = Modifier.fillMaxSize(),
        )
            sketch.totalArea?.let {
                AreaBadge(
                    stringResource(R.string.sketch_total_area, formatter.area(it).toString()),
                    Modifier.align(Alignment.TopStart).padding(12.dp),
                )
            }
        }
        val typing = editing
        if (typing != null) {
            val measured = sketch.shapes.firstOrNull { it.id == typing.shapeId }?.sides?.getOrNull(typing.side)
            val shown = measured?.let { formatter.length(it).number }.orEmpty()
            key(typing) {
                LengthField(
                    initial = shown,
                    unit = formatter.length(0).unit,
                    valid = { formatter.parseLength(it) != null },
                    onDone = { text ->
                        // A shown value is rounded; putting it back unchanged would nudge the drawing.
                        if (text.isNotBlank() && text.trim() == shown) {
                            editing = null
                        } else {
                            formatter.parseLength(text)?.let {
                                editing = null
                                vm.put(it)
                            }
                        }
                    },
                    onCancel = { editing = null },
                    modifier = inset,
                )
            }
        } else {
            Text(
                stringResource(
                    when {
                        sketch.target != null -> R.string.sketch_prompt_measure
                        sketch.shapes.isEmpty() -> R.string.sketch_prompt_draw
                        else -> R.string.sketch_prompt_select
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = inset,
            )
        }
        if (ui.recent.isNotEmpty()) {
            RecentReadings(ui.recent, formatter, inset) { vm.put(it) }
        }
        ToolSelector(tool, { tool = it }, inset)
        Row(inset.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val action = Modifier.weight(1f).height(72.dp)
            FilledTonalButton(
                onClick = vm::undo,
                enabled = sketch.shapes.isNotEmpty() || sketch.past.isNotEmpty(),
                modifier = action,
                contentPadding = ActionPadding,
                shapes = ButtonDefaults.shapes(),
            ) { ActionLabel(Icons.AutoMirrored.Rounded.Undo, R.string.action_undo) }
            FilledTonalButton(
                onClick = vm::deleteSelected,
                enabled = sketch.target != null,
                modifier = action,
                contentPadding = ActionPadding,
                shapes = ButtonDefaults.shapes(),
            ) { ActionLabel(Icons.Rounded.Delete, R.string.action_delete) }
            Button(
                onClick = { confirmClear = true },
                enabled = sketch.shapes.isNotEmpty(),
                modifier = action,
                contentPadding = ActionPadding,
                shapes = ButtonDefaults.shapes(),
            ) { ActionLabel(Icons.Rounded.DeleteSweep, R.string.action_clear) }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            text = { Text(stringResource(R.string.sketch_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    vm.clear()
                }) { Text(stringResource(R.string.action_clear)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private val ActionPadding = PaddingValues(horizontal = 8.dp)

/** A side's length typed in [unit], opened with the keyboard up; [onDone] gets the text when the user confirms it. */
@Composable
internal fun LengthField(
    initial: String,
    unit: String,
    valid: (String) -> Boolean,
    onDone: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial, TextRange(0, initial.length)))
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(R.string.slot_length)) },
            suffix = { Text(unit) },
            isError = text.text.isNotBlank() && !valid(text.text),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { onDone(text.text) }),
            modifier = Modifier.weight(1f).focusRequester(focus),
        )
        IconButton(onClick = onCancel) {
            Icon(Icons.Rounded.Close, contentDescription = stringResource(R.string.action_cancel))
        }
        FilledIconButton(onClick = { onDone(text.text) }) {
            Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.action_apply))
        }
    }
}

/** Icon over label, so three actions fit side by side on a narrow phone. */
/** The sketch's total area, over the canvas's corner where the top bar has no room for it in every language. */
@Composable
internal fun AreaBadge(text: String, modifier: Modifier = Modifier) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = modifier) {
        Text(text, style = MaterialTheme.typography.titleMedium, maxLines = 1, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
    }
}

@Composable
internal fun ActionLabel(icon: ImageVector, label: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        FitText(stringResource(label), style = MaterialTheme.typography.labelLarge)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun ToolSelector(selected: SketchTool, onSelect: (SketchTool) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween),
    ) {
        SketchTool.entries.forEachIndexed { i, t ->
            ToggleButton(
                checked = t == selected,
                onCheckedChange = { onSelect(t) },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .semantics { role = Role.RadioButton },
                shapes = if (i == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes(),
            ) {
                Icon(
                    if (t == SketchTool.LINE) Icons.Rounded.HorizontalRule else Icons.Rounded.CropSquare,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(8.dp))
                FitText(
                    stringResource(if (t == SketchTool.LINE) R.string.sketch_tool_line else R.string.sketch_tool_rect),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

// ----------------------------------------------------------------- canvas

/** What one finger on the sketch turned out to be doing. */
private sealed interface Touch {
    /** Down and still: not yet a stroke or a hold, and a tap if it lifts now. */
    data object Pending : Touch

    data class Drawing(val start: Pt, val end: Snap) : Touch

    /** Holding shape [id] after a long press; once [moved], [shift] is where it would drop. */
    data class Carrying(val id: Long, val shift: Pt = Pt(0, 0), val moved: Boolean = false) : Touch

    /** Two fingers: pan and zoom only. */
    data object Viewing : Touch
}

/**
 * The dot grid with the sketch on it. One finger draws with [tool] or, without moving, taps a side to select it and
 * double-taps it to [onEdit] it; holding a shape picks it up to drag it. Two fingers pan and zoom. Each increment of
 * [fits] frames the whole sketch.
 */
@Composable
private fun SketchCanvas(
    sketch: Sketch,
    tool: SketchTool,
    formatter: Formatter,
    fits: Int,
    onDraw: (Pt, Pt) -> Unit,
    onSelect: (SideRef) -> Unit,
    onEdit: (SideRef) -> Unit,
    onMove: (Long, Pt) -> Unit,
    onPickUp: () -> Unit,
    onTouch: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val spacing = with(density) { GridSpacing.toPx() }
    val snapReach = with(density) { 20.dp.toPx() }
    val alignReach = with(density) { 15.dp.toPx() }
    val tapReach = with(density) { 24.dp.toPx() }
    val minDotGap = with(density) { MinDotGap.toPx() }
    var scale by rememberSaveable { mutableFloatStateOf(1f) }
    // Where the sketch's (0, 0) sits on the canvas, in pixels.
    var originX by rememberSaveable { mutableFloatStateOf(spacing) }
    var originY by rememberSaveable { mutableFloatStateOf(spacing) }
    var gesture by remember { mutableStateOf<Touch?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val padding = with(density) { FitPadding.toPx() }

    fun onScreen(p: Pt): Boolean {
        val pxPerMm = spacing * scale / GRID_MM
        val x = originX + p.x * pxPerMm
        val y = originY + p.y * pxPerMm
        return x in 0f..canvasSize.width.toFloat() && y in 0f..canvasSize.height.toFloat()
    }

    fun fit() {
        val points = sketch.shapes.flatMap { it.points }
        if (points.isEmpty() || canvasSize == IntSize.Zero) return
        val left = points.minOf { it.x }
        val right = points.maxOf { it.x }
        val top = points.minOf { it.y }
        val bottom = points.maxOf { it.y }
        // A straight line has no extent across; the other direction alone decides the zoom.
        val across = (canvasSize.width - 2 * padding) * GRID_MM / (spacing * (right - left).coerceAtLeast(1))
        val down = (canvasSize.height - 2 * padding) * GRID_MM / (spacing * (bottom - top).coerceAtLeast(1))
        scale = minOf(across, down).coerceIn(MIN_SCALE, FIT_MAX_SCALE)
        val pxPerMm = spacing * scale / GRID_MM
        originX = canvasSize.width / 2f - (left + right) / 2f * pxPerMm
        originY = canvasSize.height / 2f - (top + bottom) / 2f * pxPerMm
    }

    LaunchedEffect(fits) { if (fits > 0) fit() }
    // Only a reading redraws shapes in place, and a long one can push the sketch past the edge of the view.
    var shown by remember { mutableStateOf(sketch.shapes) }
    LaunchedEffect(sketch.shapes) {
        val before = shown
        shown = sketch.shapes
        val measured = before.map { it.id } == sketch.shapes.map { it.id } && before.map { it.sides } != sketch.shapes.map { it.sides }
        val old = before.flatMapTo(HashSet()) { it.points }
        if (measured && sketch.shapes.any { s -> s.points.any { it !in old && !onScreen(it) } }) fit()
    }
    val current by rememberUpdatedState(sketch)
    val draw by rememberUpdatedState(onDraw)
    val currentTool by rememberUpdatedState(tool)
    val select by rememberUpdatedState(onSelect)
    val edit by rememberUpdatedState(onEdit)
    val move by rememberUpdatedState(onMove)
    val pickUp by rememberUpdatedState(onPickUp)
    val touch by rememberUpdatedState(onTouch)

    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelLarge

    Canvas(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(scheme.surfaceContainerLow)
            .onSizeChanged { canvasSize = it }
            .pointerInput(Unit) {
                // When and where the last tap lifted, so a second tap soon after nearby makes a double tap.
                var lastTap: Pair<Long, Offset>? = null
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    touch()
                    val second = lastTap?.let { (at, p) ->
                        down.uptimeMillis - at <= viewConfiguration.doubleTapTimeoutMillis && (down.position - p).getDistance() <= tapReach
                    } == true
                    lastTap = null
                    fun mmPerPx() = GRID_MM / (spacing * scale)
                    fun world(p: Offset) = Offset(p.x - originX, p.y - originY) * mmPerPx()
                    fun step() = GRID_MM * dotStep(spacing * scale, minDotGap)
                    fun snap(p: Offset, from: Pt? = null) =
                        world(p).let { current.snap(it.x, it.y, snapReach * mmPerPx(), alignReach * mmPerPx(), step(), from) }
                    // Lining a rectangle's far corner up with its first would flatten it.
                    fun drawFrom(start: Pt) = start.takeIf { currentTool == SketchTool.LINE }
                    fun sideAtDown() = world(down.position).let { current.sideAt(it.x, it.y, tapReach * mmPerPx()) }

                    var state: Touch = Touch.Pending
                    // Past the long-press wait, or a double tap, which never picks a shape up.
                    var held = second
                    var lastAt = down.uptimeMillis
                    while (true) {
                        val event = if (state == Touch.Pending && !held) {
                            val wait = down.uptimeMillis + viewConfiguration.longPressTimeoutMillis - lastAt
                            withTimeoutOrNull(wait.coerceAtLeast(0)) { awaitPointerEvent() }
                        } else {
                            awaitPointerEvent()
                        }
                        if (event == null) {
                            held = true
                            world(down.position).let { current.shapeAt(it.x, it.y, tapReach * mmPerPx()) }?.let {
                                state = Touch.Carrying(it)
                                gesture = state
                                pickUp()
                            }
                            continue
                        }
                        lastAt = event.changes.first().uptimeMillis
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) break
                        // Once a second finger lands, the rest of the gesture only moves the view.
                        if (state == Touch.Viewing || pressed > 1) {
                            state = Touch.Viewing
                            gesture = null
                            // A finger landing or lifting shifts the centroid; skip that frame instead of jumping.
                            if (event.changes.all { it.pressed == it.previousPressed }) {
                                val centroid = event.calculateCentroid()
                                val pan = event.calculatePan()
                                val next = (scale * event.calculateZoom()).coerceIn(MIN_SCALE, MAX_SCALE)
                                val k = next / scale
                                originX = centroid.x - (centroid.x - pan.x - originX) * k
                                originY = centroid.y - (centroid.y - pan.y - originY) * k
                                scale = next
                            }
                            event.changes.forEach { it.consume() }
                            continue
                        }
                        val change = event.changes.firstOrNull { it.id == down.id } ?: continue
                        val moved = (change.position - down.position).getDistance() > viewConfiguration.touchSlop
                        state = when (val now = state) {
                            Touch.Pending ->
                                if (moved && !second) {
                                    val start = snap(down.position).at
                                    Touch.Drawing(start, snap(change.position, drawFrom(start)))
                                } else {
                                    now
                                }
                            is Touch.Drawing -> now.copy(end = snap(change.position, drawFrom(now.start)))
                            is Touch.Carrying ->
                                if (!moved && !now.moved) {
                                    now
                                } else {
                                    val drag = (change.position - down.position) * mmPerPx()
                                    now.copy(shift = current.dropShift(now.id, drag.x, drag.y, snapReach * mmPerPx(), step()), moved = true)
                                }
                            Touch.Viewing -> now
                        }
                        if (state != Touch.Pending) {
                            gesture = state
                            change.consume()
                        }
                    }
                    gesture = null
                    when (val end = state) {
                        Touch.Viewing -> Unit
                        is Touch.Drawing -> draw(end.start, end.end.at)
                        is Touch.Carrying -> if (end.moved) move(end.id, end.shift) else select(sideAtDown() ?: SideRef(end.id, 0))
                        Touch.Pending -> {
                            val side = sideAtDown()
                            if (second) {
                                side?.let(edit)
                            } else {
                                side?.let(select)
                                if (!held) lastTap = lastAt to down.position
                            }
                        }
                    }
                }
            },
    ) {
        val pxPerMm = spacing * scale / GRID_MM
        fun at(p: Pt) = Offset(originX + p.x * pxPerMm, originY + p.y * pxPerMm)

        val unit = spacing * scale * dotStep(spacing * scale, minDotGap)
        val columns = ceil(-originX / unit).toInt()..floor((size.width - originX) / unit).toInt()
        val rows = ceil(-originY / unit).toInt()..floor((size.height - originY) / unit).toInt()
        val dots = buildList {
            for (x in columns) for (y in rows) add(Offset(originX + x * unit, originY + y * unit))
        }
        drawPoints(dots, PointMode.Points, scheme.outlineVariant, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)

        val target = current.target
        val carried = gesture as? Touch.Carrying
        val shapes = current.shapes.map { s -> if (s.id == carried?.id) s.mapPoints { it + carried.shift } else s }
        val dash = PathEffect.dashPathEffect(floatArrayOf(10.dp.toPx(), 7.dp.toPx()))
        // The selected side and a shape being carried go last so they stay on top where shapes share an edge.
        shapes
            .flatMap { s ->
                s.edges.map { e -> Triple(e, SideRef(s.id, e.side) == target || s.id == carried?.id, s.sides[e.side] != null) }
            }
            .sortedBy { it.second }
            .forEach { (e, raised, measured) ->
                drawLine(
                    color = if (raised) scheme.primary else scheme.onSurface,
                    start = at(e.a),
                    end = at(e.b),
                    strokeWidth = (if (raised) 5.dp else 3.dp).toPx(),
                    cap = StrokeCap.Round,
                    pathEffect = if (measured) null else dash,
                )
            }
        (gesture as? Touch.Drawing)?.let { (a, end) ->
            val guide = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
            end.guides.forEach { g ->
                val p = at(g.through)
                val (from, to) = if (g.vertical) Offset(p.x, 0f) to Offset(p.x, size.height) else Offset(0f, p.y) to Offset(size.width, p.y)
                drawLine(scheme.primary.copy(alpha = 0.4f), from, to, 1.dp.toPx(), pathEffect = guide)
            }
            val b = end.at
            val shape: SketchShape = if (tool == SketchTool.LINE) Segment(0, a, b) else Rect(0, a, b)
            shape.edges.forEach {
                drawLine(scheme.primary.copy(alpha = 0.6f), at(it.a), at(it.b), 3.dp.toPx(), StrokeCap.Round)
            }
        }

        fun label(text: String, center: Offset, degrees: Float, background: Color, color: Color) {
            pill(measurer.measure(text, labelStyle.copy(color = color)), center, degrees, background)
        }
        shapes.forEach { s ->
            s.sides.forEachIndexed { side, mm ->
                val e = s.edges.first { it.side == side }
                val a = at(e.a)
                val b = at(e.b)
                val selected = SideRef(s.id, side) == target
                label(
                    text = mm?.let { formatter.length(it).toString() } ?: "?",
                    center = (a + b) / 2f,
                    degrees = upright(Math.toDegrees(atan2(b.y - a.y, b.x - a.x).toDouble()).toFloat()),
                    background = if (selected) scheme.primary else scheme.surfaceContainerHigh,
                    color = if (selected) scheme.onPrimary else scheme.onSurface,
                )
            }
            if (s is Rect) {
                s.area?.let { area ->
                    label(
                        formatter.area(area).toString(),
                        (at(s.a) + at(s.b)) / 2f,
                        0f,
                        scheme.secondaryContainer,
                        scheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

/** How many dot spacings apart to show dots [dotPx] apart at full density, so they stay at least [minGap] apart. */
private fun dotStep(dotPx: Float, minGap: Float) = DotSteps.firstOrNull { dotPx * it >= minGap } ?: DotSteps.last()

/** Turns a line's angle so text along it never reads upside down; vertical text reads bottom to top. */
private fun upright(degrees: Float) = when {
    degrees >= 90f -> degrees - 180f
    degrees < -90f -> degrees + 180f
    else -> degrees
}

private fun DrawScope.pill(text: TextLayoutResult, center: Offset, degrees: Float, background: Color) {
    val w = text.size.width + 12.dp.toPx()
    val h = text.size.height + 4.dp.toPx()
    rotate(degrees, pivot = center) {
        drawRoundRect(background, Offset(center.x - w / 2, center.y - h / 2), Size(w, h), CornerRadius(h / 2))
        drawText(text, topLeft = Offset(center.x - text.size.width / 2f, center.y - text.size.height / 2f))
    }
}

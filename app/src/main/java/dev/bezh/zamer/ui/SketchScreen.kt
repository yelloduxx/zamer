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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bezh.zamer.R
import dev.bezh.zamer.domain.Background
import dev.bezh.zamer.domain.Formatter
import dev.bezh.zamer.domain.GRID_MM
import dev.bezh.zamer.domain.Pt
import dev.bezh.zamer.domain.Rect
import dev.bezh.zamer.domain.Segment
import dev.bezh.zamer.domain.SideRef
import dev.bezh.zamer.domain.Sketch
import dev.bezh.zamer.domain.SketchShape
import dev.bezh.zamer.domain.Snap
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

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

/** How far past fitting the whole photo it can be zoomed in. */
private const val MAX_PHOTO_ZOOM = 8f

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
    // A sketch just started is stored a moment after it is opened.
    val doc = ui.open ?: return
    var tool by rememberSaveable { mutableStateOf(SketchTool.LINE) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var fits by remember { mutableIntStateOf(0) }
    val sketch = doc.sketch
    val photo by produceState<ImageBitmap?>(null, doc.background) {
        value = (doc.background as? Background.Photo)?.let { vm.photo(it.file, Int.MAX_VALUE) }
    }
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
                doc.title(LocalContext.current),
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
            )
            IconButton(onClick = { fits++ }, enabled = sketch.shapes.isNotEmpty() || doc.background is Background.Photo) {
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
            // A view panned and zoomed for one sketch means nothing for another.
            key(doc.id) {
                SketchCanvas(
                    sketch = sketch,
                    background = doc.background,
                    photo = photo,
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
            }
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
 * The sketch over its [background]: a plan's dot grid, or [photo] once it has loaded. One finger draws with [tool] or,
 * without moving, taps a side to select it and double-taps it to [onEdit] it; holding a shape picks it up to drag it.
 * Two fingers pan and zoom, never past a photo's edges. Each increment of [fits] frames the whole sketch, or the whole
 * photo.
 */
@Composable
internal fun SketchCanvas(
    sketch: Sketch,
    background: Background,
    photo: ImageBitmap?,
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
    val padding = with(density) { FitPadding.toPx() }
    // Screen pixels per unit of the sketch, a plan's millimetre or a photo's pixel.
    var pxPerUnit by rememberSaveable { mutableFloatStateOf(spacing / GRID_MM) }
    // Where the sketch's (0, 0) sits on the canvas, in pixels.
    var originX by rememberSaveable { mutableFloatStateOf(spacing) }
    var originY by rememberSaveable { mutableFloatStateOf(spacing) }
    var framed by rememberSaveable { mutableStateOf(background == Background.Plan) }
    var gesture by remember { mutableStateOf<Touch?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }

    fun zoomRange(): ClosedFloatingPointRange<Float> = when (background) {
        Background.Plan -> spacing * MIN_SCALE / GRID_MM..spacing * MAX_SCALE / GRID_MM
        is Background.Photo -> {
            val whole = minOf(canvasSize.width.toFloat() / background.width, canvasSize.height.toFloat() / background.height)
            whole..whole * MAX_PHOTO_ZOOM
        }
    }

    /** Keeps a photo filling the view along each side it is longer than the view, and centred along the others. */
    fun keepOnPhoto() {
        if (background !is Background.Photo) return
        fun clamp(origin: Float, extent: Float, room: Float) =
            if (extent <= room) (room - extent) / 2 else origin.coerceIn(room - extent, 0f)
        originX = clamp(originX, background.width * pxPerUnit, canvasSize.width.toFloat())
        originY = clamp(originY, background.height * pxPerUnit, canvasSize.height.toFloat())
    }

    fun fit() {
        if (canvasSize == IntSize.Zero) return
        val w = canvasSize.width.toFloat()
        val h = canvasSize.height.toFloat()
        val view = when (background) {
            Background.Plan -> fitView(
                sketch.shapes.flatMap { it.points }, w, h, padding,
                zoomRange().start..spacing * FIT_MAX_SCALE / GRID_MM,
            )
            is Background.Photo -> fitView(listOf(Pt(0, 0), Pt(background.width, background.height)), w, h, 0f, zoomRange())
        } ?: return
        pxPerUnit = view.pxPerUnit
        originX = view.originX
        originY = view.originY
    }

    fun onScreen(p: Pt): Boolean {
        val at = SketchView(originX, originY, pxPerUnit).at(p)
        return at.x in 0f..canvasSize.width.toFloat() && at.y in 0f..canvasSize.height.toFloat()
    }

    LaunchedEffect(fits) { if (fits > 0) fit() }
    LaunchedEffect(canvasSize) {
        if (canvasSize == IntSize.Zero) return@LaunchedEffect
        if (!framed) {
            fit()
            framed = true
        } else if (background is Background.Photo) {
            pxPerUnit = pxPerUnit.coerceIn(zoomRange())
            keepOnPhoto()
        }
    }
    // Only a reading redraws shapes in place, and a long one can push a plan past the edge of the view.
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
    val labels = remember(measurer, labelStyle, formatter) { SketchLabels(measurer, labelStyle, formatter) }
    val colors = when (background) {
        Background.Plan -> planColors(scheme)
        is Background.Photo -> photoColors(scheme)
    }
    val grid = when (background) {
        Background.Plan -> GRID_MM
        is Background.Photo -> null
    }

    Canvas(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(if (grid != null) scheme.surfaceContainerLow else Color.Black)
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
                    fun unitsPerPx() = 1 / pxPerUnit
                    fun world(p: Offset) = Offset(p.x - originX, p.y - originY) * unitsPerPx()
                    fun step() = grid?.let { it * dotStep(it * pxPerUnit, minDotGap) }
                    fun snap(p: Offset, from: Pt? = null) =
                        world(p).let { current.snap(it.x, it.y, snapReach * unitsPerPx(), alignReach * unitsPerPx(), step(), from) }
                    // Lining a rectangle's far corner up with its first would flatten it.
                    fun drawFrom(start: Pt) = start.takeIf { currentTool == SketchTool.LINE }
                    fun sideAtDown() = world(down.position).let { current.sideAt(it.x, it.y, tapReach * unitsPerPx()) }

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
                            world(down.position).let { current.shapeAt(it.x, it.y, tapReach * unitsPerPx()) }?.let {
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
                                val next = (pxPerUnit * event.calculateZoom()).coerceIn(zoomRange())
                                val k = next / pxPerUnit
                                originX = centroid.x - (centroid.x - pan.x - originX) * k
                                originY = centroid.y - (centroid.y - pan.y - originY) * k
                                pxPerUnit = next
                                keepOnPhoto()
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
                                    val drag = (change.position - down.position) * unitsPerPx()
                                    now.copy(shift = current.dropShift(now.id, drag.x, drag.y, snapReach * unitsPerPx(), step()), moved = true)
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
        val view = SketchView(originX, originY, pxPerUnit)
        when (background) {
            Background.Plan -> {
                val unit = GRID_MM * pxPerUnit * dotStep(GRID_MM * pxPerUnit, minDotGap)
                val columns = ceil(-originX / unit).toInt()..floor((size.width - originX) / unit).toInt()
                val rows = ceil(-originY / unit).toInt()..floor((size.height - originY) / unit).toInt()
                val dots = buildList {
                    for (x in columns) for (y in rows) add(Offset(originX + x * unit, originY + y * unit))
                }
                drawPoints(dots, PointMode.Points, scheme.outlineVariant, strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
            }
            is Background.Photo -> photo?.let {
                drawImage(
                    it,
                    dstOffset = IntOffset(originX.roundToInt(), originY.roundToInt()),
                    dstSize = IntSize((background.width * pxPerUnit).roundToInt(), (background.height * pxPerUnit).roundToInt()),
                    filterQuality = FilterQuality.Medium,
                )
            }
        }

        val carried = gesture as? Touch.Carrying
        val shapes = current.shapes.map { s -> if (s.id == carried?.id) s.mapPoints { it + carried.shift } else s }
        drawShapes(shapes, view, colors, labels, target = current.target, carried = carried?.id)

        (gesture as? Touch.Drawing)?.let { (a, end) ->
            val guide = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))
            end.guides.forEach { g ->
                val p = view.at(g.through)
                val (from, to) = if (g.vertical) Offset(p.x, 0f) to Offset(p.x, size.height) else Offset(0f, p.y) to Offset(size.width, p.y)
                drawLine(colors.raised.copy(alpha = 0.5f), from, to, 1.dp.toPx(), pathEffect = guide)
            }
            val b = end.at
            val shape: SketchShape = if (tool == SketchTool.LINE) Segment(0, a, b) else Rect(0, a, b)
            shape.edges.forEach {
                drawLine(colors.raised.copy(alpha = 0.7f), view.at(it.a), view.at(it.b), 3.dp.toPx(), StrokeCap.Round)
            }
        }
    }
}

/** How many dot spacings apart to show dots [dotPx] apart at full density, so they stay at least [minGap] apart. */
private fun dotStep(dotPx: Float, minGap: Float) = DotSteps.firstOrNull { dotPx * it >= minGap } ?: DotSteps.last()

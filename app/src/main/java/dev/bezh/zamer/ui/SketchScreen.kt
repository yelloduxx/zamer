package dev.bezh.zamer.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FitScreen
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bezh.zamer.R
import dev.bezh.zamer.data.imageName
import dev.bezh.zamer.data.saveToGallery
import dev.bezh.zamer.data.shareImage
import dev.bezh.zamer.domain.Background
import dev.bezh.zamer.domain.Formatter
import dev.bezh.zamer.domain.GRID_MM
import dev.bezh.zamer.domain.Note
import dev.bezh.zamer.domain.Pt
import dev.bezh.zamer.domain.Rect
import dev.bezh.zamer.domain.Segment
import dev.bezh.zamer.domain.SideRef
import dev.bezh.zamer.domain.Sketch
import dev.bezh.zamer.domain.SketchShape
import dev.bezh.zamer.domain.Snap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private val GridSpacing = 24.dp

private val MinDotGap = 12.dp

private val DotSteps = listOf(1, 2, 10, 20)

private const val MIN_SCALE = 0.1f
private const val MAX_SCALE = 4f

private const val FIT_MAX_SCALE = 2f
private val FitPadding = 40.dp

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
    val countOnEntry = remember { ui.assignedCount }
    LaunchedEffect(ui.assignedCount) {
        if (ui.assignedCount > countOnEntry && ui.settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.Confirm)
    }
    val doc = ui.open ?: return
    var tool by rememberSaveable { mutableStateOf(SketchTool.LINE) }
    var confirmClear by rememberSaveable { mutableStateOf(false) }
    var fits by remember { mutableIntStateOf(0) }
    val sketch = doc.sketch
    val photo by produceState<ImageBitmap?>(null, doc.background) {
        value = (doc.background as? Background.Photo)?.let { vm.photo(it.file, Int.MAX_VALUE) }
    }
    var editing by remember { mutableStateOf<Typing?>(null) }
    LaunchedEffect(sketch.target) {
        val typing = editing
        if (typing is Typing.Length && typing.side != sketch.target) editing = null
    }
    BackHandler(enabled = editing != null) { editing = null }
    var picked by remember { mutableStateOf<Long?>(null) }
    val pickedNote = picked?.takeIf { id -> sketch.shapes.any { it.id == id } }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val saved = stringResource(R.string.sketch_saved)
    val saveFailed = stringResource(R.string.sketch_save_failed)
    // Its own measurer, so rendering off the main thread never shares the canvas's text cache.
    val exportMeasurer = rememberTextMeasurer(cacheSize = 0)
    val labelStyle = MaterialTheme.typography.labelLarge
    val overPhoto = photoColors(MaterialTheme.colorScheme)
    val caption = listOfNotNull(
        doc.name,
        sketch.totalArea?.let { stringResource(R.string.sketch_total_area, formatter.area(it).toString()) },
    ).joinToString(" · ").ifEmpty { null }
    var exporting by remember { mutableStateOf(false) }
    fun export(use: suspend (Bitmap, String) -> Unit) {
        exporting = true
        scope.launch {
            try {
                val full = (doc.background as? Background.Photo)?.let { vm.exportPhoto(it.file) }
                val image = withContext(Dispatchers.Default) {
                    renderSketch(doc, full, caption, formatter, exportMeasurer, labelStyle, overPhoto)
                }
                if (image != null) use(image, imageName(doc.title(context)))
            } finally {
                exporting = false
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val inset = Modifier.padding(horizontal = ScreenPadding)
        val drawn = sketch.shapes.isNotEmpty() || doc.background is Background.Photo
        SketchTopBar(
            title = doc.title(context),
            name = doc.name,
            onRename = { vm.rename(doc.id, it) },
            onBack = onBack,
            onSave = {
                export { image, name ->
                    snackbar.showSnackbar(if (saveToGallery(context, image, name)) saved else saveFailed)
                }
            },
            onShare = { export { image, name -> if (!shareImage(context, image, name)) snackbar.showSnackbar(saveFailed) } },
            canExport = drawn && !exporting,
        ) {
            ConnectionPill(
                link = ui.link,
                onRequestPermission = onRequestPermission,
                onEnableBluetooth = onEnableBluetooth,
                onPair = onPair,
                compact = true,
            )
        }
        Box(inset.weight(1f).fillMaxWidth()) {
            key(doc.id) {
                SketchCanvas(
                    sketch = sketch,
                    background = doc.background,
                    photo = photo,
                    tool = tool,
                    formatter = formatter,
                    fits = fits,
                    onDraw = { a, b -> vm.draw(tool, a, b) },
                    picked = pickedNote,
                    marker = (editing as? Typing.Text)?.takeIf { it.id == null }?.at,
                    onSelect = {
                        picked = null
                        vm.select(it)
                    },
                    onEdit = {
                        picked = null
                        vm.select(it)
                        editing = Typing.Length(it)
                    },
                    onPick = { picked = it },
                    onPlace = { editing = Typing.Text(it) },
                    onWrite = {
                        picked = it.id
                        editing = Typing.Text(it.at, it.id, it.text)
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
            FilledTonalIconButton(
                onClick = { fits++ },
                enabled = drawn,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
            ) {
                Icon(Icons.Rounded.FitScreen, contentDescription = stringResource(R.string.sketch_fit))
            }
            SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
        }
        when (val typing = editing) {
            is Typing.Length -> {
                val measured = sketch.shapes.firstOrNull { it.id == typing.side.shapeId }?.sides?.getOrNull(typing.side.side)
                val shown = measured?.let { formatter.length(it).number }.orEmpty()
                key(typing) {
                    LengthField(
                        initial = shown,
                        unit = formatter.length(0).unit,
                        valid = { formatter.parseLength(it) != null },
                        onDone = { text ->
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
            }
            is Typing.Text -> key(typing) {
                NoteField(
                    initial = typing.initial,
                    onDone = { text ->
                        editing = null
                        if (text.isNotBlank()) {
                            if (typing.id == null) vm.note(typing.at, text) else vm.write(typing.id, text)
                            tool = SketchTool.LINE
                        }
                    },
                    onCancel = { editing = null },
                    modifier = inset,
                )
            }
            null -> Text(
                stringResource(
                    when {
                        tool == SketchTool.TEXT -> R.string.sketch_prompt_note
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
        if (editing == null) {
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
                    onClick = { if (pickedNote != null) vm.erase(pickedNote) else vm.deleteSelected() },
                    enabled = sketch.target != null || pickedNote != null,
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

private sealed interface Typing {
    data class Length(val side: SideRef) : Typing

    data class Text(val at: Pt, val id: Long? = null, val initial: String = "") : Typing
}

@Composable
internal fun SketchTopBar(
    title: String,
    name: String?,
    onRename: (String) -> Unit,
    onBack: () -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
    canExport: Boolean,
    renaming: Boolean = false,
    pill: @Composable () -> Unit,
) {
    var editing by rememberSaveable { mutableStateOf(renaming) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = ScreenPadding, top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (editing) {
            NameField(
                initial = name,
                onCommit = {
                    editing = false
                    onRename(it)
                },
                modifier = Modifier.weight(1f).padding(start = ScreenPadding - 4.dp),
            )
        } else {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            Text(
                title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .clickable(onClickLabel = stringResource(R.string.action_rename)) { editing = true }
                    .padding(start = 4.dp, top = 8.dp, bottom = 8.dp),
            )
            ExportMenu(onSave, onShare, enabled = canExport)
            pill()
        }
    }
}

@Composable
private fun ExportMenu(onSave: () -> Unit, onShare: () -> Unit, enabled: Boolean) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, enabled = enabled) {
            Icon(Icons.Rounded.IosShare, contentDescription = stringResource(R.string.sketch_export))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.sketch_save_gallery)) },
                leadingIcon = { Icon(Icons.Rounded.Download, contentDescription = null) },
                onClick = {
                    open = false
                    onSave()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_share)) },
                leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                onClick = {
                    open = false
                    onShare()
                },
            )
        }
    }
}

@Composable
internal fun LengthField(
    initial: String,
    unit: String,
    valid: (String) -> Boolean,
    onDone: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    InlineField(
        initial = TextFieldValue(initial, TextRange(0, initial.length)),
        label = R.string.slot_length,
        keyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal, imeAction = ImeAction.Done),
        onDone = onDone,
        onCancel = onCancel,
        modifier = modifier,
        valid = valid,
        suffix = unit,
    )
}

@Composable
internal fun NoteField(initial: String, onDone: (String) -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    InlineField(
        initial = TextFieldValue(initial, TextRange(initial.length)),
        label = R.string.sketch_note,
        keyboard = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        onDone = onDone,
        onCancel = onCancel,
        modifier = modifier,
    )
}

@Composable
private fun InlineField(
    initial: TextFieldValue,
    label: Int,
    keyboard: KeyboardOptions,
    onDone: (String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier,
    valid: (String) -> Boolean = { true },
    suffix: String? = null,
) {
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(stringResource(label)) },
            suffix = suffix?.let { { Text(it) } },
            isError = text.text.isNotBlank() && !valid(text.text),
            singleLine = true,
            keyboardOptions = keyboard,
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
        val last = SketchTool.entries.lastIndex
        SketchTool.entries.forEachIndexed { i, t ->
            ToggleButton(
                checked = t == selected,
                onCheckedChange = { onSelect(t) },
                modifier = Modifier
                    .weight(if (t == SketchTool.RECT) 1.4f else 1f)
                    .height(48.dp)
                    .semantics { role = Role.RadioButton },
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    last -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
                contentPadding = PaddingValues(horizontal = 12.dp),
            ) {
                val label = when (t) {
                    SketchTool.LINE -> R.string.sketch_tool_line
                    SketchTool.RECT -> R.string.sketch_tool_rect
                    SketchTool.TEXT -> R.string.sketch_tool_text
                }
                FitText(stringResource(label), style = MaterialTheme.typography.labelLarge)
            }
        }
    }
}

private sealed interface Touch {
    data object Pending : Touch

    data class Drawing(val start: Pt, val end: Snap) : Touch

    data class Carrying(val id: Long, val shift: Pt = Pt(0, 0), val moved: Boolean = false) : Touch

    data object Viewing : Touch
}

@Composable
internal fun SketchCanvas(
    sketch: Sketch,
    background: Background,
    photo: ImageBitmap?,
    tool: SketchTool,
    formatter: Formatter,
    fits: Int,
    picked: Long?,
    marker: Pt?,
    onDraw: (Pt, Pt) -> Unit,
    onSelect: (SideRef) -> Unit,
    onEdit: (SideRef) -> Unit,
    onPick: (Long?) -> Unit,
    onPlace: (Pt) -> Unit,
    onWrite: (Note) -> Unit,
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
    val noteReach = with(density) { 8.dp.toPx() }
    val minDotGap = with(density) { MinDotGap.toPx() }
    val padding = with(density) { FitPadding.toPx() }
    var pxPerUnit by rememberSaveable { mutableFloatStateOf(spacing / GRID_MM) }
    var originX by rememberSaveable { mutableFloatStateOf(spacing) }
    var originY by rememberSaveable { mutableFloatStateOf(spacing) }
    var framed by rememberSaveable { mutableStateOf(background == Background.Plan) }
    var gesture by remember { mutableStateOf<Touch?>(null) }
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    val scheme = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelLarge
    val labels = remember(measurer, labelStyle, formatter) { SketchLabels(measurer, labelStyle, formatter) }

    fun zoomRange(): ClosedFloatingPointRange<Float> = when (background) {
        Background.Plan -> spacing * MIN_SCALE / GRID_MM..spacing * MAX_SCALE / GRID_MM
        is Background.Photo -> {
            val whole = minOf(canvasSize.width.toFloat() / background.width, canvasSize.height.toFloat() / background.height)
            whole..whole * MAX_PHOTO_ZOOM
        }
    }

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
                with(density) { noteBoxes(sketch.shapes, labels) },
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
    val pick by rememberUpdatedState(onPick)
    val place by rememberUpdatedState(onPlace)
    val write by rememberUpdatedState(onWrite)
    val currentLabels by rememberUpdatedState(labels)

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
                    fun drawFrom(start: Pt) = start.takeIf { currentTool == SketchTool.LINE }
                    fun sideAtDown() = world(down.position).let { current.sideAt(it.x, it.y, tapReach * unitsPerPx()) }
                    fun noteAtDown(): Note? {
                        val view = SketchView(originX, originY, pxPerUnit)
                        return current.shapes.filterIsInstance<Note>().lastOrNull {
                            noteBox(it, view, noteText(it, currentLabels)).inflate(noteReach).contains(down.position)
                        }
                    }

                    var state: Touch = Touch.Pending
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
                            (noteAtDown()?.id ?: world(down.position).let { current.shapeAt(it.x, it.y, tapReach * unitsPerPx()) })?.let {
                                state = Touch.Carrying(it)
                                gesture = state
                                pickUp()
                            }
                            continue
                        }
                        lastAt = event.changes.first().uptimeMillis
                        val pressed = event.changes.count { it.pressed }
                        if (pressed == 0) break
                        if (state == Touch.Viewing || pressed > 1) {
                            state = Touch.Viewing
                            gesture = null
                            val centroid = event.calculateCentroid()
                            if (centroid.isSpecified) {
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
                            Touch.Pending -> when {
                                !moved || second -> now
                                currentTool == SketchTool.TEXT -> Touch.Viewing
                                else -> {
                                    val start = snap(down.position).at
                                    Touch.Drawing(start, snap(change.position, drawFrom(start)))
                                }
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
                        is Touch.Carrying -> when {
                            end.moved -> move(end.id, end.shift)
                            current.shapes.any { it.id == end.id && it is Note } -> pick(end.id)
                            else -> select(sideAtDown() ?: SideRef(end.id, 0))
                        }
                        Touch.Pending -> {
                            val note = noteAtDown()
                            when {
                                second -> note?.let(write) ?: sideAtDown()?.let(edit)
                                currentTool == SketchTool.TEXT -> note?.let(write) ?: world(down.position).let {
                                    place(Pt(it.x.roundToInt(), it.y.roundToInt()))
                                }
                                else -> {
                                    if (note != null) pick(note.id) else sideAtDown()?.let(select) ?: pick(null)
                                    if (!held) lastTap = lastAt to down.position
                                }
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
        drawShapes(shapes, view, colors, labels, target = current.target, picked = picked, carried = carried?.id)
        marker?.let {
            colors.halo?.let { halo -> drawCircle(halo, 8.dp.toPx(), view.at(it)) }
            drawCircle(colors.raised, 6.dp.toPx(), view.at(it))
        }

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

private fun dotStep(dotPx: Float, minGap: Float) = DotSteps.firstOrNull { dotPx * it >= minGap } ?: DotSteps.last()

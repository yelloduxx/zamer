package dev.bezh.zamer.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.text.format.DateUtils
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.AddAPhoto
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.GridOn
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PhotoCamera
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bezh.zamer.R
import dev.bezh.zamer.domain.Background
import dev.bezh.zamer.domain.Formatter
import dev.bezh.zamer.domain.SketchDoc
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SketchListScreen(vm: SketchViewModel, onBack: () -> Unit, onOpen: (Long) -> Unit) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val formatter = remember(ui.settings.units, ui.settings.decimals) { Formatter(ui.settings.units, ui.settings.decimals) }
    var deleting by rememberSaveable { mutableStateOf<Long?>(null) }
    val context = LocalContext.current
    var importing by remember { mutableStateOf(false) }
    fun usePhoto(uri: Uri) {
        importing = true
        vm.newPhoto(
            uri,
            onCreated = {
                importing = false
                onOpen(it)
            },
            onFailed = {
                importing = false
                Toast.makeText(context, R.string.sketch_photo_failed, Toast.LENGTH_SHORT).show()
            },
        )
    }
    var shot by rememberSaveable { mutableStateOf<Uri?>(null) }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        shot?.takeIf { taken }?.let(::usePhoto)
    }
    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { picked -> picked?.let(::usePhoto) }
    val sketches = ui.sketches ?: return

    Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, end = ScreenPadding, top = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = stringResource(R.string.action_back))
            }
            Text(stringResource(R.string.sketches_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))
        }
        LazyColumn(
            contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.weight(1f),
        ) {
            item(key = "new") {
                NewSketchActions(
                    onPlan = { onOpen(vm.newPlan()) },
                    onCamera = {
                        val target = vm.newCameraShot()
                        shot = target
                        try {
                            camera.launch(target)
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(context, R.string.sketch_no_camera, Toast.LENGTH_SHORT).show()
                        }
                    },
                    onGallery = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    importing = importing,
                    modifier = Modifier.padding(bottom = 14.dp),
                )
            }
            if (sketches.isEmpty()) {
                item(key = "empty") {
                    Text(
                        stringResource(R.string.sketches_empty),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(top = 32.dp),
                    )
                }
            }
            items(sketches, key = { it.id }) { doc ->
                val photo by produceState<ImageBitmap?>(null, doc.background) {
                    value = (doc.background as? Background.Photo)?.let { vm.photo(it.file, THUMBNAIL_PX) }
                }
                SketchCard(
                    doc = doc,
                    photo = photo,
                    formatter = formatter,
                    onOpen = { onOpen(doc.id) },
                    onRename = { vm.rename(doc.id, it) },
                    onDelete = { deleting = doc.id },
                )
            }
        }
    }

    deleting?.let { id ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            text = { Text(stringResource(R.string.sketch_delete_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    deleting = null
                    vm.delete(id)
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

private const val THUMBNAIL_PX = 320

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun NewSketchActions(
    onPlan: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
    importing: Boolean,
    modifier: Modifier = Modifier,
) {
    var choosing by remember { mutableStateOf(false) }
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        NewSketchTile(
            icon = { Icon(Icons.Rounded.GridOn, contentDescription = null, modifier = Modifier.size(32.dp)) },
            label = R.string.sketch_new_plan,
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            onClick = onPlan,
            modifier = Modifier.weight(1f),
        )
        Box(Modifier.weight(1f)) {
            NewSketchTile(
                icon = {
                    if (importing) {
                        LoadingIndicator(Modifier.size(32.dp), color = MaterialTheme.colorScheme.onTertiaryContainer)
                    } else {
                        Icon(Icons.Rounded.AddAPhoto, contentDescription = null, modifier = Modifier.size(32.dp))
                    }
                },
                label = R.string.sketch_new_photo,
                color = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                onClick = { if (!importing) choosing = true },
                modifier = Modifier.fillMaxWidth(),
            )
            DropdownMenu(expanded = choosing, onDismissRequest = { choosing = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sketch_photo_camera)) },
                    leadingIcon = { Icon(Icons.Rounded.PhotoCamera, contentDescription = null) },
                    onClick = {
                        choosing = false
                        onCamera()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.sketch_photo_gallery)) },
                    leadingIcon = { Icon(Icons.Rounded.PhotoLibrary, contentDescription = null) },
                    onClick = {
                        choosing = false
                        onGallery()
                    },
                )
            }
        }
    }
}

@Composable
private fun NewSketchTile(
    icon: @Composable () -> Unit,
    label: Int,
    color: Color,
    contentColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(onClick = onClick, shape = RoundedCornerShape(28.dp), color = color, contentColor = contentColor, modifier = modifier.height(112.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            icon()
            FitText(stringResource(label), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
internal fun SketchCard(
    doc: SketchDoc,
    photo: ImageBitmap?,
    formatter: Formatter,
    onOpen: () -> Unit,
    onRename: (String) -> Unit,
    onDelete: () -> Unit,
) {
    val context = LocalContext.current
    var renaming by rememberSaveable(doc.id) { mutableStateOf(false) }
    Surface(onClick = onOpen, shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(Modifier.padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                SketchThumbnail(doc, photo, Modifier.size(88.dp))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(doc.title(context), style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    doc.sketch.totalArea?.let {
                        Text(formatter.area(it).toString(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    if (doc.name != null) {
                        Text(created(context, doc, withTime = false), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                SketchMenu(onRename = { renaming = true }, onDelete = onDelete)
            }
            if (renaming) {
                NameField(
                    initial = doc.name,
                    onCommit = {
                        onRename(it)
                        renaming = false
                    },
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                )
            }
        }
    }
}

@Composable
private fun SketchThumbnail(doc: SketchDoc, photo: ImageBitmap?, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val background = doc.background
    Box(modifier.clip(RoundedCornerShape(20.dp)).background(scheme.surfaceContainerLow), contentAlignment = Alignment.Center) {
        val points = doc.sketch.shapes.flatMap { it.points }
        when (background) {
            Background.Plan -> if (points.isEmpty()) {
                Icon(Icons.Rounded.Draw, contentDescription = null, tint = scheme.onSurfaceVariant)
            } else {
                Canvas(Modifier.fillMaxSize()) {
                    val view = fitView(points, size.width, size.height, 12.dp.toPx()) ?: return@Canvas
                    drawShapes(doc.sketch.shapes, view, planColors(scheme), labels = null, stroke = 1.5.dp.toPx())
                }
            }
            is Background.Photo -> Canvas(Modifier.fillMaxSize()) {
                val k = maxOf(size.width / background.width, size.height / background.height)
                val view = SketchView((size.width - background.width * k) / 2, (size.height - background.height * k) / 2, k)
                photo?.let {
                    drawImage(
                        it,
                        dstOffset = IntOffset(view.originX.roundToInt(), view.originY.roundToInt()),
                        dstSize = IntSize((background.width * k).roundToInt(), (background.height * k).roundToInt()),
                    )
                }
                drawShapes(doc.sketch.shapes, view, photoColors(scheme), labels = null, stroke = 1.5.dp.toPx())
            }
        }
    }
}

@Composable
internal fun SketchMenu(onRename: () -> Unit, onDelete: () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Rounded.MoreVert, contentDescription = stringResource(R.string.sketch_more))
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_rename)) },
                leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                onClick = {
                    expanded = false
                    onRename()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_delete)) },
                leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null) },
                onClick = {
                    expanded = false
                    onDelete()
                },
            )
        }
    }
}

fun SketchDoc.title(context: Context): String = name ?: created(context, this)

private fun created(context: Context, doc: SketchDoc, withTime: Boolean = true): String = DateUtils.formatDateTime(
    context,
    doc.created,
    DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or if (withTime) DateUtils.FORMAT_SHOW_TIME else 0,
)

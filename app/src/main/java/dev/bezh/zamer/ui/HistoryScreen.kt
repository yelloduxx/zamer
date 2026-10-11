package dev.bezh.zamer.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.RoundedPolygon
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bezh.zamer.AppContainer
import dev.bezh.zamer.R
import dev.bezh.zamer.data.HistoryEntry
import dev.bezh.zamer.data.Settings
import dev.bezh.zamer.domain.Formatter
import dev.bezh.zamer.domain.MeasureMode
import dev.bezh.zamer.domain.MeasureResult
import dev.bezh.zamer.domain.Reading
import kotlinx.coroutines.launch

private enum class HistoryTab(val label: Int, val clearConfirm: Int) {
    Results(R.string.history_tab_results, R.string.history_clear_confirm),
    Readings(R.string.history_tab_readings, R.string.readings_clear_confirm),
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HistoryScreen(container: AppContainer, contentPadding: PaddingValues) {
    val entries by container.store.history.collectAsStateWithLifecycle(emptyList())
    val readings by container.store.readings.collectAsStateWithLifecycle(emptyList())
    val settings by container.store.settings.collectAsStateWithLifecycle(Settings())
    val formatter = remember(settings.units, settings.decimals) { Formatter(settings.units, settings.decimals) }
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableStateOf(HistoryTab.Results) }
    var confirmClear by remember { mutableStateOf(false) }
    val tabEmpty = when (tab) {
        HistoryTab.Results -> entries.isEmpty()
        HistoryTab.Readings -> readings.isEmpty()
    }

    // imePadding keeps a card being renamed above the keyboard.
    Column(Modifier.fillMaxSize().padding(contentPadding).consumeWindowInsets(contentPadding).imePadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.history_title), style = MaterialTheme.typography.displaySmall, modifier = Modifier.weight(1f))
            if (!tabEmpty) {
                TextButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.history_clear)) }
            }
        }
        TabSelector(tab, onSelect = { tab = it }, Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp))
        when (tab) {
            HistoryTab.Results -> ResultsTab(
                entries = entries,
                formatter = formatter,
                onRename = { entry, name -> scope.launch { container.store.renameHistory(entry.id, name) } },
                onDelete = { scope.launch { container.store.deleteHistory(it.id) } },
            )
            HistoryTab.Readings -> ReadingsTab(
                readings = readings,
                formatter = formatter,
                onDelete = { scope.launch { container.store.deleteReading(it) } },
            )
        }
    }

    if (confirmClear) {
        val clearing = tab
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            text = { Text(stringResource(clearing.clearConfirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch {
                        when (clearing) {
                            HistoryTab.Results -> container.store.clearHistory()
                            HistoryTab.Readings -> container.store.clearReadings()
                        }
                    }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun TabSelector(selected: HistoryTab, onSelect: (HistoryTab) -> Unit, modifier: Modifier = Modifier) {
    val tabs = HistoryTab.entries
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        tabs.forEachIndexed { i, t ->
            ToggleButton(
                checked = t == selected,
                onCheckedChange = { onSelect(t) },
                modifier = Modifier.weight(1f).height(48.dp).semantics { role = Role.Tab },
                shapes = if (i == 0) ButtonGroupDefaults.connectedLeadingButtonShapes() else ButtonGroupDefaults.connectedTrailingButtonShapes(),
            ) { Text(stringResource(t.label), maxLines = 1) }
        }
    }
}

// ---------------------------------------------------------------- results

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ResultsTab(
    entries: List<HistoryEntry>,
    formatter: Formatter,
    onRename: (HistoryEntry, String) -> Unit,
    onDelete: (HistoryEntry) -> Unit,
) {
    if (entries.isEmpty()) {
        EmptyState(Icons.Rounded.History, MaterialShapes.Sunny, R.string.history_empty)
        return
    }
    var query by rememberSaveable { mutableStateOf("") }
    val modeNames = MeasureMode.entries.associateWith { stringResource(it.label) }
    val shown = entries.filter { it.matches(query, modeNames.getValue(it.mode)) }

    SearchField(query, onChange = { query = it }, Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp))
    if (shown.isEmpty()) {
        Text(
            stringResource(R.string.history_search_empty),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
        )
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(shown, key = { it.id }) { entry ->
            SwipeToDelete(onDelete = { onDelete(entry) }) { HistoryCard(entry, formatter, onRename = { onRename(entry, it) }) }
        }
    }
}

/** Matches the name the user gave, or the kind of measurement. */
private fun HistoryEntry.matches(query: String, modeName: String): Boolean {
    val q = query.trim()
    return q.isEmpty() || title?.contains(q, ignoreCase = true) == true || modeName.contains(q, ignoreCase = true)
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        modifier = modifier.fillMaxWidth(),
        singleLine = true,
        shape = CircleShape,
        placeholder = { Text(stringResource(R.string.history_search)) },
        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, stringResource(R.string.action_clear)) }
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
    )
}

private fun describe(entry: HistoryEntry, formatter: Formatter, context: Context): Pair<String, String> {
    val total = MeasureResult.of(entry.mode, entry.valuesMm)?.let(formatter::primary)?.toString() ?: "—"
    val unit = formatter.length(0).unit
    val sides = when (entry.mode) {
        MeasureMode.DISTANCE, MeasureMode.AREA, MeasureMode.VOLUME ->
            entry.valuesMm.joinToString(" × ") { formatter.length(it).number } + " $unit"
        MeasureMode.HEIGHT -> entry.valuesMm.zip(entry.mode.sides).joinToString(" · ") { (mm, side) ->
            "${context.getString(side.label)} ${formatter.length(mm)}"
        }
    }
    return total to sides
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun HistoryCard(entry: HistoryEntry, formatter: Formatter, onRename: (String) -> Unit) {
    val context = LocalContext.current
    val (total, sides) = describe(entry, formatter, context)
    val value = if (entry.mode == MeasureMode.DISTANCE) total else "$total  ($sides)"
    var editing by rememberSaveable(entry.id) { mutableStateOf(false) }
    Surface(
        onClick = { editing = true },
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            Row(Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(52.dp).clip(MaterialShapes.Cookie6Sided.toShape()).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(entry.mode.icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer)
                }
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    if (entry.title != null) {
                        Text(entry.title, style = MaterialTheme.typography.titleLarge)
                        Text(total, style = MaterialTheme.typography.titleMedium)
                    } else {
                        Text(total, style = MaterialTheme.typography.headlineSmall)
                    }
                    if (entry.mode != MeasureMode.DISTANCE) {
                        Text(sides, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                    }
                    Text(relativeTime(context, entry.timestamp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { copy(context, total) }) {
                    Icon(Icons.Rounded.ContentCopy, stringResource(R.string.action_copy))
                }
                IconButton(onClick = { share(context, listOfNotNull(entry.title, value).joinToString(": ")) }) {
                    Icon(Icons.Rounded.IosShare, stringResource(R.string.action_share))
                }
            }
            if (editing) {
                NameField(
                    initial = entry.title,
                    onCommit = {
                        onRename(it)
                        editing = false
                    },
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                )
            }
        }
    }
}

// --------------------------------------------------------------- readings

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ReadingsTab(readings: List<Reading>, formatter: Formatter, onDelete: (Reading) -> Unit) {
    if (readings.isEmpty()) {
        EmptyState(Icons.Rounded.Straighten, MaterialShapes.Cookie9Sided, R.string.readings_empty)
        return
    }
    val context = LocalContext.current
    LazyColumn(
        contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(readings, key = { "${it.at}:${it.mm}" }) { reading ->
            SwipeToDelete(onDelete = { onDelete(reading) }) {
                val value = formatter.length(reading.mm).toString()
                Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(start = 20.dp, top = 10.dp, bottom = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(value, style = MaterialTheme.typography.titleLarge)
                            Text(relativeTime(context, reading.at), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { copy(context, value) }) {
                            Icon(Icons.Rounded.ContentCopy, stringResource(R.string.action_copy))
                        }
                    }
                }
            }
        }
    }
}

// ----------------------------------------------------------------- shared

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeToDelete(onDelete: () -> Unit, content: @Composable () -> Unit) {
    SwipeToDismissBox(
        state = rememberSwipeToDismissBoxState(),
        enableDismissFromStartToEnd = false,
        onDismiss = { if (it == SwipeToDismissBoxValue.EndToStart) onDelete() },
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(end = 24.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Rounded.Delete, stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) { content() }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun EmptyState(icon: ImageVector, shape: RoundedPolygon, text: Int) {
    Box(Modifier.fillMaxSize().padding(horizontal = 32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                Modifier.size(112.dp).clip(shape.toShape()).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            Text(
                stringResource(text),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun relativeTime(context: Context, at: Long): String =
    DateUtils.getRelativeDateTimeString(context, at, DateUtils.MINUTE_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0).toString()

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", text))
    Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
}

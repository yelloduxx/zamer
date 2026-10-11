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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bezh.zamer.AppContainer
import dev.bezh.zamer.R
import dev.bezh.zamer.data.HistoryEntry
import dev.bezh.zamer.data.Settings
import dev.bezh.zamer.domain.Formatter
import dev.bezh.zamer.domain.MeasureMode
import dev.bezh.zamer.domain.MeasureResult
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun HistoryScreen(container: AppContainer, contentPadding: PaddingValues) {
    val entries by container.store.history.collectAsStateWithLifecycle(emptyList())
    val settings by container.store.settings.collectAsStateWithLifecycle(Settings())
    val formatter = remember(settings.units, settings.decimals) { Formatter(settings.units, settings.decimals) }
    val scope = rememberCoroutineScope()
    var confirmClear by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var renaming by rememberSaveable { mutableStateOf<Long?>(null) }
    val modeNames = MeasureMode.entries.associateWith { stringResource(it.label) }
    val shown = entries.filter { it.matches(query, modeNames.getValue(it.mode)) }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.history_title), style = MaterialTheme.typography.displaySmall, modifier = Modifier.weight(1f))
            if (entries.isNotEmpty()) {
                TextButton(onClick = { confirmClear = true }) { Text(stringResource(R.string.history_clear)) }
            }
        }
        if (entries.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(
                        Modifier.size(112.dp).clip(MaterialShapes.Sunny.toShape()).background(MaterialTheme.colorScheme.secondaryContainer),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Rounded.History, null, Modifier.size(44.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                    Text(
                        stringResource(R.string.history_empty),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            SearchField(query, onChange = { query = it }, Modifier.padding(horizontal = ScreenPadding, vertical = 4.dp))
            if (shown.isEmpty()) {
                Text(
                    stringResource(R.string.history_search_empty),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(top = 48.dp),
                    textAlign = TextAlign.Center,
                )
            }
            LazyColumn(
                contentPadding = PaddingValues(horizontal = ScreenPadding, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(shown, key = { it.id }) { entry ->
                    val dismiss = rememberSwipeToDismissBoxState(
                        confirmValueChange = { value ->
                            if (value == SwipeToDismissBoxValue.EndToStart) {
                                scope.launch { container.store.deleteHistory(entry.id) }
                                true
                            } else false
                        },
                    )
                    SwipeToDismissBox(
                        state = dismiss,
                        enableDismissFromStartToEnd = false,
                        backgroundContent = {
                            Box(
                                Modifier.fillMaxSize().clip(RoundedCornerShape(28.dp)).background(MaterialTheme.colorScheme.errorContainer).padding(end = 24.dp),
                                contentAlignment = Alignment.CenterEnd,
                            ) {
                                Icon(Icons.Rounded.Delete, stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.onErrorContainer)
                            }
                        },
                    ) { HistoryCard(entry, formatter, onRename = { renaming = entry.id }) }
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            text = { Text(stringResource(R.string.history_clear_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    scope.launch { container.store.clearHistory() }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    renaming?.let { id -> entries.find { it.id == id } }?.let { entry ->
        NameDialog(
            initial = entry.title,
            onDismiss = { renaming = null },
            onSave = {
                scope.launch { container.store.renameHistory(entry.id, it) }
                renaming = null
            },
        )
    }
}

/** Matches the name the user gave, or the kind of measurement. */
private fun HistoryEntry.matches(query: String, modeName: String): Boolean {
    val q = query.trim()
    return q.isEmpty() || title?.contains(q, ignoreCase = true) == true || modeName.contains(q, ignoreCase = true)
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, modifier: Modifier = Modifier) {
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
private fun HistoryCard(entry: HistoryEntry, formatter: Formatter, onRename: () -> Unit) {
    val context = LocalContext.current
    val (total, sides) = describe(entry, formatter, context)
    val whenText = DateUtils.getRelativeDateTimeString(
        context, entry.timestamp, DateUtils.MINUTE_IN_MILLIS, DateUtils.WEEK_IN_MILLIS, 0,
    ).toString()
    val value = if (entry.mode == MeasureMode.DISTANCE) total else "$total  ($sides)"
    Surface(
        onClick = onRename,
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
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
                Text(whenText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { share(context, listOfNotNull(entry.title, value).joinToString(": ")) }) {
                Icon(Icons.Rounded.IosShare, stringResource(R.string.action_share))
            }
        }
    }
}

private fun share(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Suppress("unused")
private fun copy(context: Context, text: String) {
    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("", text))
    Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
}

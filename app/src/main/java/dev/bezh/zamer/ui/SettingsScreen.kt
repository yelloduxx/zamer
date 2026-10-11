package dev.bezh.zamer.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bezh.zamer.AppContainer
import dev.bezh.zamer.R
import dev.bezh.zamer.ble.LinkState
import dev.bezh.zamer.data.Settings
import dev.bezh.zamer.data.ThemeMode
import dev.bezh.zamer.domain.UnitSystem
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(container: AppContainer, contentPadding: PaddingValues, onRepair: () -> Unit) {
    val settings by container.store.settings.collectAsStateWithLifecycle(Settings())
    val paired by container.store.pairedDevice.collectAsStateWithLifecycle(null)
    val link by container.repository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirmForget by remember { mutableStateOf(false) }
    fun update(transform: (Settings) -> Settings) = scope.launch { container.store.updateSettings(transform) }

    Column(
        Modifier.fillMaxSize().padding(contentPadding).verticalScroll(rememberScrollState()).padding(horizontal = ScreenPadding, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            stringResource(R.string.settings_title),
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.padding(start = 8.dp, bottom = 4.dp),
        )

        SettingsCard(stringResource(R.string.settings_device)) {
            paired?.let { d ->
                Text(d.name.ifEmpty { "HOTO" }, style = MaterialTheme.typography.titleLarge)
                Text(
                    "${d.address} · " + stringResource(
                        when (link) {
                            LinkState.Connected -> R.string.status_connected
                            LinkState.NeedsRepair -> R.string.status_repair
                            LinkState.Connecting -> R.string.status_connecting
                            else -> R.string.status_searching
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                FilledTonalButton(onClick = onRepair, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.settings_repair)) }
                TextButton(onClick = { confirmForget = true }) { Text(stringResource(R.string.settings_forget)) }
            }
        }

        SettingsCard(stringResource(R.string.settings_units)) {
            Segmented(
                options = listOf(R.string.units_metric to UnitSystem.METRIC, R.string.units_imperial to UnitSystem.IMPERIAL),
                selected = settings.units,
                onSelect = { u -> update { it.copy(units = u) } },
            )
            Text(
                stringResource(R.string.settings_precision),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
                (1..3).forEach { n ->
                    ToggleButton(
                        checked = settings.decimals == n,
                        onCheckedChange = { update { it.copy(decimals = n) } },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shapes = when (n) {
                            1 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                            3 -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                            else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                        },
                    ) { Text("$n") }
                }
            }
        }

        SettingsCard(stringResource(R.string.settings_theme)) {
            Segmented(
                options = listOf(
                    R.string.theme_system to ThemeMode.SYSTEM,
                    R.string.theme_light to ThemeMode.LIGHT,
                    R.string.theme_dark to ThemeMode.DARK,
                ),
                selected = settings.theme,
                onSelect = { t -> update { it.copy(theme = t) } },
            )
            SwitchRow(R.string.settings_dynamic, settings.dynamicColor) { v -> update { it.copy(dynamicColor = v) } }
        }

        SettingsCard(null) {
            SwitchRow(R.string.settings_keep_on, settings.keepScreenOn) { v -> update { it.copy(keepScreenOn = v) } }
            SwitchRow(R.string.settings_haptics, settings.haptics) { v -> update { it.copy(haptics = v) } }
        }

        Text(
            stringResource(R.string.settings_about),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }

    if (confirmForget) {
        AlertDialog(
            onDismissRequest = { confirmForget = false },
            title = { Text(stringResource(R.string.settings_forget)) },
            text = { Text(stringResource(R.string.settings_forget_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmForget = false
                    scope.launch { container.repository.forget() }
                }) { Text(stringResource(R.string.settings_forget)) }
            },
            dismissButton = { TextButton(onClick = { confirmForget = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun SettingsCard(title: String?, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            content()
        }
    }
}

@Composable
private fun SwitchRow(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun <T> Segmented(options: List<Pair<Int, T>>, selected: T, onSelect: (T) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { i, (label, value) ->
            ToggleButton(
                checked = selected == value,
                onCheckedChange = { onSelect(value) },
                modifier = Modifier.weight(1f).height(52.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
            ) { Text(stringResource(label), maxLines = 1, softWrap = false) }
        }
    }
}

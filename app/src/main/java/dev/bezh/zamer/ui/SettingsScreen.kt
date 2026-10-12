package dev.bezh.zamer.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Translate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bezh.zamer.AppContainer
import dev.bezh.zamer.R
import dev.bezh.zamer.ble.LinkState
import dev.bezh.zamer.ble.ReferenceEdge
import dev.bezh.zamer.ble.SettingWrite
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
    val device by container.repository.deviceSettings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
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

        LanguageCard()

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
            DeviceActions(onRepair = onRepair, onForget = { confirmForget = true })
        }

        SettingsCard(stringResource(R.string.settings_rangefinder)) {
            val connected = link == LinkState.Connected
            Text(stringResource(R.string.device_reference), style = MaterialTheme.typography.titleSmall)
            Segmented(
                options = listOf(R.string.reference_rear to ReferenceEdge.REAR, R.string.reference_front to ReferenceEdge.FRONT),
                selected = device.reference,
                enabled = connected && device.reference != null,
                onSelect = { e ->
                    scope.launch {
                        when (container.repository.setReference(e)) {
                            SettingWrite.WRITTEN, SettingWrite.NOT_CONNECTED -> Unit
                            SettingWrite.FAILED -> Toast.makeText(context, R.string.reference_change_failed, Toast.LENGTH_SHORT).show()
                        }
                    }
                },
            )
            Text(stringResource(R.string.settings_units), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
            Segmented(
                options = listOf(R.string.units_metric to UnitSystem.METRIC, R.string.units_imperial to UnitSystem.IMPERIAL),
                selected = settings.units,
                onSelect = { u ->
                    update { it.copy(units = u) }
                    if (connected) scope.launch { container.repository.setUnits(u) }
                },
            )
            Text(
                stringResource(if (connected) R.string.device_settings_hint else R.string.device_settings_offline),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        SettingsCard(stringResource(R.string.settings_precision)) {
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

private sealed interface Language {
    data object System : Language

    data class Locale(val tag: String, val endonym: String) : Language
}

private val Languages = listOf(
    Language.System,
    Language.Locale("en", "English"),
    Language.Locale("ru", "Русский"),
    Language.Locale("es", "Español"),
    Language.Locale("de", "Deutsch"),
    Language.Locale("zh-CN", "简体中文"),
)

@Composable
private fun Language.label() = when (this) {
    Language.System -> stringResource(R.string.language_system)
    is Language.Locale -> endonym
}

/** Android stores the per-app language and restarts the screen in the new language. */
@Composable
internal fun LanguageCard() {
    var open by remember { mutableStateOf(false) }
    val current = AppCompatDelegate.getApplicationLocales()[0]?.language
    val selected = Languages.firstOrNull { it is Language.Locale && it.tag.substringBefore('-') == current } ?: Language.System
    SettingsCard(stringResource(R.string.settings_language), icon = { Icon(Icons.Rounded.Translate, null, Modifier.padding(end = 6.dp).size(18.dp)) }) {
        Box {
            Row(
                Modifier.fillMaxWidth().height(56.dp).clickable { open = true },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(selected.label(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.ExpandMore, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                Languages.forEach { language ->
                    DropdownMenuItem(
                        text = { Text(language.label()) },
                        trailingIcon = { if (language == selected) Icon(Icons.Rounded.Check, null) },
                        onClick = {
                            open = false
                            AppCompatDelegate.setApplicationLocales(
                                when (language) {
                                    Language.System -> LocaleListCompat.getEmptyLocaleList()
                                    is Language.Locale -> LocaleListCompat.forLanguageTags(language.tag)
                                },
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
internal fun SettingsCard(title: String?, icon: (@Composable () -> Unit)? = null, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(32.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.primary) { icon?.invoke() }
                    Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
            content()
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun DeviceActions(onRepair: () -> Unit, onForget: () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(top = 8.dp),
    ) {
        FilledTonalButton(onClick = onRepair, shapes = ButtonDefaults.shapes()) { FitText(stringResource(R.string.settings_repair)) }
        TextButton(onClick = onForget) { FitText(stringResource(R.string.settings_forget)) }
    }
}

@Composable
internal fun SwitchRow(label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(label), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun <T> Segmented(options: List<Pair<Int, T>>, selected: T?, onSelect: (T) -> Unit, enabled: Boolean = true) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)) {
        options.forEachIndexed { i, (label, value) ->
            ToggleButton(
                checked = selected == value,
                onCheckedChange = { onSelect(value) },
                enabled = enabled,
                modifier = Modifier.weight(1f).height(52.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
                shapes = when (i) {
                    0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                    options.lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                    else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                },
            ) { FitText(stringResource(label)) }
        }
    }
}

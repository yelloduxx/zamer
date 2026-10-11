package dev.bezh.zamer.ui

import android.view.WindowManager
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ShortNavigationBar
import androidx.compose.material3.ShortNavigationBarItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.bezh.zamer.AppContainer
import dev.bezh.zamer.R
import dev.bezh.zamer.data.PairedDevice
import dev.bezh.zamer.data.Settings
import dev.bezh.zamer.ui.theme.ZamerTheme

private enum class Tab(val label: Int) {
    Measure(R.string.tab_measure),
    History(R.string.tab_history),
    Settings(R.string.tab_settings),
}

/** Distinguishes "still reading the store" from "nothing paired". */
private sealed interface PairedState {
    data object Loading : PairedState
    data class Loaded(val device: PairedDevice?) : PairedState
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ZamerRoot(container: AppContainer) {
    val factory = remember { ViewModelFactory(container) }
    val settings by container.store.settings.collectAsStateWithLifecycle(Settings())
    val pairedState by produceState<PairedState>(PairedState.Loading) {
        container.store.pairedDevice.collect { value = PairedState.Loaded(it) }
    }

    ZamerTheme(dynamicColor = settings.dynamicColor) {
        KeepScreenOn(settings.keepScreenOn)
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val state = pairedState
            if (state is PairedState.Loading) return@Surface

            val device = (state as PairedState.Loaded).device
            var repairing by rememberSaveable { mutableStateOf(false) }
            // Pairing again yields a new token; leave the pairing screen once it arrives.
            LaunchedEffect(device?.tokenHex) { repairing = false }

            if (device == null || repairing) {
                val pairingVm: PairingViewModel = viewModel(factory = factory)
                PairingScreen(container, pairingVm, canCancel = device != null, onCancel = { repairing = false })
            } else {
                MainScaffold(container, factory, onRepair = { repairing = true })
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun MainScaffold(container: AppContainer, factory: ViewModelFactory, onRepair: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.Measure) }
    val measureVm: MeasureViewModel = viewModel(factory = factory)
    val requestPermission = rememberPermissionRequest()
    val enableBluetooth = rememberEnableBluetooth()

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            ShortNavigationBar {
                Tab.entries.forEach { t ->
                    ShortNavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = {
                            Icon(
                                when (t) {
                                    Tab.Measure -> Icons.Rounded.Straighten
                                    Tab.History -> Icons.Rounded.History
                                    Tab.Settings -> Icons.Rounded.Settings
                                },
                                contentDescription = null,
                            )
                        },
                        label = { Text(stringResource(t.label)) },
                    )
                }
            }
        },
    ) { padding ->
        when (tab) {
            Tab.Measure -> MeasureScreen(measureVm, padding, onRepair, requestPermission, enableBluetooth)
            Tab.History -> HistoryScreen(container, padding)
            Tab.Settings -> SettingsScreen(container, padding, onRepair)
        }
    }
}

@Composable
private fun KeepScreenOn(enabled: Boolean) {
    val activity = LocalActivity.current
    DisposableEffect(enabled, activity) {
        val window = activity?.window
        if (enabled) window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
}

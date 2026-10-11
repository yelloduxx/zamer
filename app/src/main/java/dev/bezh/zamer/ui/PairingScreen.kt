package dev.bezh.zamer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.bezh.zamer.AppContainer
import dev.bezh.zamer.R
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun PairingScreen(
    container: AppContainer,
    vm: PairingViewModel,
    canCancel: Boolean,
    onCancel: () -> Unit,
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val readiness by rememberBtReadiness(container)
    val requestPermission = rememberPermissionRequest()
    val enableBluetooth = rememberEnableBluetooth()
    var waitedLong by remember { mutableStateOf(false) }

    LaunchedEffect(readiness) {
        if (readiness == BtReadiness.Ready) vm.startScan() else vm.stopScan()
    }
    LaunchedEffect(Unit) {
        delay(9_000)
        waitedLong = true
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 16.dp),
    ) {
        Spacer(Modifier.height(24.dp))
        Box(
            modifier = Modifier
                .size(132.dp)
                .clip(MaterialShapes.Cookie9Sided.toShape())
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Rounded.Straighten,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
        Spacer(Modifier.height(28.dp))
        Text(stringResource(R.string.pairing_title), style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(12.dp))
        Text(
            stringResource(R.string.pairing_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(28.dp))

        Box(Modifier.weight(1f).fillMaxWidth()) {
            val phase = ui.phase
            when {
                readiness == BtReadiness.NeedsPermission ->
                    Prompt(R.string.pairing_need_permission, R.string.action_allow, requestPermission)
                readiness == BtReadiness.BluetoothOff ->
                    Prompt(R.string.pairing_need_bluetooth, R.string.action_enable_bt, enableBluetooth)
                phase is PairPhase.Pairing -> Busy(stringResource(R.string.pairing_in_progress, phase.device.name))
                phase is PairPhase.Failed -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        stringResource(R.string.pairing_failed, phase.message),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                    )
                    Button(onClick = vm::retry, shapes = ButtonDefaults.shapes()) { Text(stringResource(R.string.pairing_retry)) }
                }
                ui.found.isEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Busy(stringResource(R.string.pairing_searching))
                    if (waitedLong) {
                        Text(
                            stringResource(R.string.pairing_none_found),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(ui.found, key = { it.address }) { d ->
                        Surface(
                            onClick = { vm.pair(d) },
                            shape = RoundedCornerShape(28.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.Bluetooth, contentDescription = null, modifier = Modifier.size(32.dp))
                                Spacer(Modifier.width(16.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(d.name, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "${d.address} · ${d.rssi} dBm",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                                    )
                                }
                                Text(stringResource(R.string.action_pair), style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
        }
        if (canCancel) {
            TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Busy(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LoadingIndicator(modifier = Modifier.size(48.dp))
        Spacer(Modifier.width(16.dp))
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun Prompt(message: Int, action: Int, onClick: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(message), style = MaterialTheme.typography.bodyLarge)
        Button(onClick = onClick, shapes = ButtonDefaults.shapes()) { Text(stringResource(action)) }
    }
}

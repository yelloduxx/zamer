package dev.bezh.zamer.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.BluetoothConnected
import androidx.compose.material.icons.rounded.BluetoothDisabled
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CropSquare
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Height
import androidx.compose.material.icons.rounded.Straighten
import androidx.compose.material.icons.rounded.ViewInAr
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import dev.bezh.zamer.AppContainer
import dev.bezh.zamer.R
import dev.bezh.zamer.ble.LinkState
import dev.bezh.zamer.ble.RangefinderRepository
import dev.bezh.zamer.domain.MeasureMode
import dev.bezh.zamer.domain.Side
import kotlinx.coroutines.delay

enum class BtReadiness { NeedsPermission, BluetoothOff, Ready }

/** Polls the things the user can fix in settings, so screens react as soon as they're fixed. */
@Composable
fun rememberBtReadiness(container: AppContainer): State<BtReadiness> {
    fun compute() = when {
        !container.repository.hasPermissions() -> BtReadiness.NeedsPermission
        !container.scanner.isBluetoothOn -> BtReadiness.BluetoothOff
        else -> BtReadiness.Ready
    }
    return produceState(compute()) {
        while (true) {
            value = compute()
            delay(700)
        }
    }
}

@Composable
fun rememberPermissionRequest(): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}
    return { launcher.launch(RangefinderRepository.REQUIRED_PERMISSIONS) }
}

@Composable
fun rememberEnableBluetooth(): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
    return { launcher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
}

/** One-line connection status, with a button when the user can do something about it. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConnectionPill(
    link: LinkState,
    onRequestPermission: () -> Unit,
    onEnableBluetooth: () -> Unit,
    onPair: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val busy = link == LinkState.Searching || link == LinkState.Connecting
    val (container, content) = when (link) {
        LinkState.Connected -> scheme.primaryContainer to scheme.onPrimaryContainer
        LinkState.Searching, LinkState.Connecting -> scheme.secondaryContainer to scheme.onSecondaryContainer
        else -> scheme.errorContainer to scheme.onErrorContainer
    }
    val label = when (link) {
        LinkState.Connected -> R.string.status_connected
        LinkState.Searching -> R.string.status_searching
        LinkState.Connecting -> R.string.status_connecting
        LinkState.BluetoothOff -> R.string.status_bt_off
        LinkState.NeedsPermission -> R.string.status_permission
        LinkState.NeedsRepair -> R.string.status_repair
        LinkState.NotPaired -> R.string.status_not_paired
    }
    val action: Pair<Int, () -> Unit>? = when (link) {
        LinkState.NeedsPermission -> R.string.action_allow to onRequestPermission
        LinkState.BluetoothOff -> R.string.action_enable_bt to onEnableBluetooth
        LinkState.NeedsRepair, LinkState.NotPaired -> R.string.action_pair to onPair
        else -> null
    }
    Surface(modifier = modifier, shape = CircleShape, color = container, contentColor = content) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = if (action != null) 4.dp else 18.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (busy) {
                LoadingIndicator(modifier = Modifier.size(28.dp), color = content)
            } else {
                Icon(
                    imageVector = when (link) {
                        LinkState.Connected -> Icons.Rounded.BluetoothConnected
                        LinkState.BluetoothOff -> Icons.Rounded.BluetoothDisabled
                        LinkState.NeedsPermission, LinkState.NeedsRepair -> Icons.Rounded.ErrorOutline
                        else -> Icons.Rounded.Bluetooth
                    },
                    contentDescription = null,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Text(stringResource(label), style = MaterialTheme.typography.labelLarge)
            if (action != null) {
                Spacer(Modifier.width(4.dp))
                TextButton(onClick = action.second) { Text(stringResource(action.first)) }
            }
        }
    }
}

/**
 * Edits a result's name in place. Done, the check button, or tapping elsewhere all save via [onCommit];
 * a blank name removes it.
 */
@Composable
fun NameField(initial: String?, onCommit: (String) -> Unit, modifier: Modifier = Modifier) {
    var text by rememberSaveable(stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(initial.orEmpty(), TextRange(initial.orEmpty().length)))
    }
    val focus = remember { FocusRequester() }
    var focused by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    fun commit() {
        if (done) return
        done = true
        onCommit(text.text)
    }
    LaunchedEffect(Unit) { focus.requestFocus() }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        shape = CircleShape,
        placeholder = { Text(stringResource(R.string.name_hint)) },
        trailingIcon = {
            IconButton(onClick = ::commit) {
                Icon(Icons.Rounded.Check, contentDescription = stringResource(R.string.action_save))
            }
        },
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = modifier
            .fillMaxWidth()
            .focusRequester(focus)
            .onFocusChanged {
                if (focused && !it.isFocused) commit()
                focused = it.isFocused
            },
    )
}

/** Equal-width, softly separated tiles use the same spacing everywhere. */
val ScreenPadding = 16.dp
val TileSpacing = Arrangement.spacedBy(12.dp)

val MeasureMode.label: Int
    get() = when (this) {
        MeasureMode.DISTANCE -> R.string.mode_distance
        MeasureMode.AREA -> R.string.mode_area
        MeasureMode.VOLUME -> R.string.mode_volume
        MeasureMode.HEIGHT -> R.string.mode_height
    }

val MeasureMode.icon: ImageVector
    get() = when (this) {
        MeasureMode.DISTANCE -> Icons.Rounded.Straighten
        MeasureMode.AREA -> Icons.Rounded.CropSquare
        MeasureMode.VOLUME -> Icons.Rounded.ViewInAr
        MeasureMode.HEIGHT -> Icons.Rounded.Height
    }

val Side.label: Int
    get() = when (this) {
        Side.LENGTH -> R.string.slot_length
        Side.WIDTH -> R.string.slot_width
        Side.HEIGHT -> R.string.slot_height
        Side.BASE -> R.string.slot_base
        Side.HYPOTENUSE -> R.string.slot_hypotenuse
    }

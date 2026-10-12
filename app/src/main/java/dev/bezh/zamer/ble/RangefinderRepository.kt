package dev.bezh.zamer.ble

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import dev.bezh.zamer.data.AppStore
import dev.bezh.zamer.data.PairedDevice
import dev.bezh.zamer.domain.Reading
import dev.bezh.zamer.domain.UnitSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

sealed interface LinkState {
    data object NotPaired : LinkState
    data object NeedsPermission : LinkState
    data object BluetoothOff : LinkState
    data object Searching : LinkState
    data object Connecting : LinkState
    data object Connected : LinkState

    data object NeedsRepair : LinkState
}

sealed interface PairOutcome {
    data object Success : PairOutcome
    data class Failure(val message: String) : PairOutcome
}

class RangefinderRepository(
    private val context: Context,
    private val store: AppStore,
    val scanner: RangefinderScanner,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<LinkState>(LinkState.NotPaired)
    val state: StateFlow<LinkState> = _state.asStateFlow()

    private val _readings = MutableSharedFlow<Reading>(extraBufferCapacity = 16)
    val readings: SharedFlow<Reading> = _readings.asSharedFlow()

    private val _device = MutableStateFlow(DeviceSettings())
    val deviceSettings: StateFlow<DeviceSettings> = _device.asStateFlow()

    private val pairingLock = Mutex()
    @Volatile private var session: Session? = null
    private var loop: Job? = null
    private var foreground = false

    fun hasPermissions(): Boolean = REQUIRED_PERMISSIONS.all {
        context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (value) startLoop() else stopLoop()
    }

    suspend fun pair(found: FoundDevice): PairOutcome = pairingLock.withLock {
        stopLoop()
        try {
            val link = GattLink(context)
            try {
                link.open(found.device)
                val token = MiAuth(link).register()
                store.savePairedDevice(PairedDevice(found.address, found.name, token.toHex()))
                PairOutcome.Success
            } finally {
                link.close()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            PairOutcome.Failure(e.message ?: "Pairing failed")
        } finally {
            if (foreground) startLoop()
        }
    }

    suspend fun forget() {
        stopLoop()
        store.clearPairedDevice()
        _state.value = LinkState.NotPaired
        if (foreground) startLoop()
    }

    private fun startLoop() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            store.pairedDevice.collectLatest { paired ->
                if (paired == null) _state.value = LinkState.NotPaired else connectionLoop(paired)
            }
        }
    }

    private fun stopLoop() {
        loop?.cancel()
        loop = null
    }

    private suspend fun connectionLoop(paired: PairedDevice) {
        val token = paired.tokenHex.hexToBytes()
        var failures = 0
        while (true) {
            if (!hasPermissions()) {
                _state.value = LinkState.NeedsPermission
                delay(1_000)
                continue
            }
            if (!scanner.isBluetoothOn) {
                _state.value = LinkState.BluetoothOff
                delay(1_000)
                continue
            }
            _state.value = LinkState.Searching
            val link = GattLink(context)
            try {
                val found = withTimeoutOrNull(SCAN_WINDOW_MS) {
                    scanner.scan().first { it.address == paired.address }
                } ?: continue
                _state.value = LinkState.Connecting
                link.open(found.device)
                val keys = MiAuth(link).login(token)
                val current = Session(link, keys).also { session = it }
                _state.value = LinkState.Connected
                failures = 0
                for (frame in link.sessionFrames) {
                    val report = keys.decrypt(frame) as? DeviceFrame.Report ?: continue
                    current.send(report.ack)
                    onReport(report)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PairingRejectedException) {
                _state.value = LinkState.NeedsRepair
                return
            } catch (e: Exception) {
                failures++
            } finally {
                session = null
                _device.value = DeviceSettings()
                link.close()
            }
            delay(minOf(500L * (failures + 1), 5_000L))
        }
    }

    private suspend fun onReport(report: DeviceFrame.Report) {
        report.valueOf(DeviceAttr.DISTANCE_TYPE, DeviceAttr.DISTANCE_ID)?.let {
            _readings.tryEmit(Reading(it.toInt(), System.currentTimeMillis()))
        }
        report.valueOf(DeviceAttr.SETTING_TYPE, DeviceAttr.REFERENCE_ID)?.let { v ->
            ReferenceEdge.entries.getOrNull(v.toInt())?.let { r -> _device.update { it.copy(reference = r) } }
        }
        report.valueOf(DeviceAttr.SETTING_TYPE, DeviceAttr.UNIT_ID)?.let { v ->
            DeviceUnit.entries.getOrNull(v.toInt())?.let { u ->
                _device.update { it.copy(unit = u) }
                val wanted = if (store.settings.first().units == UnitSystem.IMPERIAL) DeviceUnit.FEET else DeviceUnit.METRES
                if (u != wanted) setDeviceUnit(wanted)
            }
        }
    }

    suspend fun setReference(edge: ReferenceEdge): Boolean = sendSetting(DeviceAttr.REFERENCE_ID, edge.ordinal)

    suspend fun setDeviceUnit(unit: DeviceUnit): Boolean = sendSetting(DeviceAttr.UNIT_ID, unit.ordinal)

    private suspend fun sendSetting(id: Int, value: Int): Boolean {
        val s = session ?: return false
        return runCatching { s.send(DeviceAttr.set(s.nextMsgId(), id, value)) }.isSuccess
    }

    private class Session(private val link: GattLink, private val keys: SessionKeys) {
        private val lock = Mutex()
        private var counter = 0
        private var msgId = 0

        suspend fun send(plain: ByteArray) = lock.withLock { link.writeData(keys.encrypt(plain, ++counter)) }

        fun nextMsgId(): Int = synchronized(this) {
            msgId = msgId % 15 + 1
            msgId
        }
    }

    companion object {
        private const val SCAN_WINDOW_MS = 20_000L

        val REQUIRED_PERMISSIONS = arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
        )
    }
}

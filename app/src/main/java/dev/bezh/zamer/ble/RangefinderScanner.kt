package dev.bezh.zamer.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.ParcelUuid
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

data class FoundDevice(
    val address: String,
    val name: String,
    val rssi: Int,
    val device: BluetoothDevice,
)

/** Finds HOTO / Xiaomi laser rangefinders that are advertising (i.e. not connected to anything). */
class RangefinderScanner(private val context: Context) {

    private val adapter get() = context.getSystemService(BluetoothManager::class.java)?.adapter

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun scan(): Flow<FoundDevice> = callbackFlow {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            close(IllegalStateException("Bluetooth is unavailable"))
            return@callbackFlow
        }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                result.toRangefinder()?.let { trySend(it) }
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { r -> r.toRangefinder()?.let { trySend(it) } }
            }

            override fun onScanFailed(errorCode: Int) {
                close(IllegalStateException("Scan failed ($errorCode)"))
            }
        }
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        awaitClose { scanner.stopScan(callback) }
    }

    @SuppressLint("MissingPermission")
    private fun ScanResult.toRangefinder(): FoundDevice? {
        val record = scanRecord ?: return null
        val name = record.deviceName ?: device.name ?: ""
        val xiaomi = record.getServiceData(ParcelUuid(Uuids.XIAOMI_SERVICE))
        val isRangefinder = name.startsWith(NAME_PREFIX, ignoreCase = true) ||
            (xiaomi != null && xiaomi.size >= 4 && productId(xiaomi) in KNOWN_PRODUCT_IDS)
        if (!isRangefinder) return null
        return FoundDevice(device.address, name.ifEmpty { "HOTO rangefinder" }, rssi, device)
    }

    private fun productId(serviceData: ByteArray) =
        (serviceData[2].toInt() and 0xFF) or ((serviceData[3].toInt() and 0xFF) shl 8)

    private companion object {
        /** HOTO's Chinese brand name; the JY001 advertises as "xiaojiang.cc_xxx". */
        const val NAME_PREFIX = "xiaojiang"
        val KNOWN_PRODUCT_IDS = setOf(0x09E2)
    }
}

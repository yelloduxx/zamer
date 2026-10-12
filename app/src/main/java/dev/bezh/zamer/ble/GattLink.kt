package dev.bezh.zamer.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID

internal object Uuids {
    private fun short(v: Long) = UUID.fromString("%08x-0000-1000-8000-00805f9b34fb".format(v))

    val XIAOMI_SERVICE = short(0xFE95)
    val UPNP = short(0x0010)
    val AVDTP = short(0x0019)
    val DATA_SERVICE = short(0xFEB7)
    val DATA_WRITE = short(0xFEA7)
    val DATA_NOTIFY = short(0xFEA8)
    val CCCD = short(0x2902)
}

@SuppressLint("MissingPermission")
class GattLink(private val context: Context) : AuthLink {

    override val upnp = Channel<ByteArray>(Channel.UNLIMITED)
    override val avdtp = Channel<ByteArray>(Channel.UNLIMITED)
    val sessionFrames = Channel<ByteArray>(Channel.UNLIMITED)

    private val connected = CompletableDeferred<Unit>()
    private val servicesDiscovered = CompletableDeferred<Unit>()
    private val opLock = Mutex()
    @Volatile private var pendingOp: CompletableDeferred<Int>? = null
    private var gatt: BluetoothGatt? = null
    private var upnpChar: BluetoothGattCharacteristic? = null
    private var avdtpChar: BluetoothGattCharacteristic? = null
    private var dataWriteChar: BluetoothGattCharacteristic? = null

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                connected.complete(Unit)
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                val cause = IOException("Disconnected (status $status)")
                connected.completeExceptionally(cause)
                servicesDiscovered.completeExceptionally(cause)
                pendingOp?.complete(-1)
                closeChannels(cause)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) servicesDiscovered.complete(Unit)
            else servicesDiscovered.completeExceptionally(IOException("Service discovery failed ($status)"))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            pendingOp?.complete(status)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            pendingOp?.complete(status)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            dispatch(c.uuid, value)
        }

        @Deprecated("Used below API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) dispatch(c.uuid, c.value ?: return)
        }
    }

    private fun dispatch(uuid: UUID, value: ByteArray) {
        when (uuid) {
            Uuids.UPNP -> upnp.trySend(value)
            Uuids.AVDTP -> avdtp.trySend(value)
            Uuids.DATA_NOTIFY -> sessionFrames.trySend(value)
        }
    }

    suspend fun open(device: BluetoothDevice) {
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw IOException("Could not start a connection")
        try {
            withTimeout(CONNECT_TIMEOUT_MS) { connected.await() }
            if (gatt?.discoverServices() != true) throw IOException("Could not discover services")
            withTimeout(DISCOVER_TIMEOUT_MS) { servicesDiscovered.await() }

            val xiaomi = gatt?.getService(Uuids.XIAOMI_SERVICE) ?: throw IOException("Not a supported rangefinder")
            upnpChar = xiaomi.getCharacteristic(Uuids.UPNP)
            avdtpChar = xiaomi.getCharacteristic(Uuids.AVDTP)
            val dataService = gatt?.getService(Uuids.DATA_SERVICE)
            val data = dataService?.getCharacteristic(Uuids.DATA_NOTIFY)
            dataWriteChar = dataService?.getCharacteristic(Uuids.DATA_WRITE)
            enableNotifications(upnpChar ?: throw IOException("Missing auth characteristic"))
            enableNotifications(avdtpChar ?: throw IOException("Missing data characteristic"))
            enableNotifications(data ?: throw IOException("Missing measurement characteristic"))
        } catch (e: TimeoutCancellationException) {
            close()
            throw IOException("Connection timed out")
        } catch (e: Throwable) {
            close()
            throw e
        }
    }

    override suspend fun writeUpnp(data: ByteArray) = write(upnpChar, data)

    override suspend fun writeAvdtp(data: ByteArray) = write(avdtpChar, data)

    suspend fun writeData(frame: ByteArray) = write(dataWriteChar, frame)

    fun close() {
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        closeChannels(IOException("Closed"))
    }

    private fun closeChannels(cause: Throwable) {
        upnp.close(cause)
        avdtp.close(cause)
        sessionFrames.close()
    }

    @Suppress("DEPRECATION")
    private suspend fun enableNotifications(c: BluetoothGattCharacteristic) {
        val g = gatt ?: throw IOException("Not connected")
        g.setCharacteristicNotification(c, true)
        val cccd = c.getDescriptor(Uuids.CCCD) ?: throw IOException("Notifications unsupported")
        val status = operation {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
            } else {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(cccd)
            }
        }
        if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("Could not enable notifications ($status)")
    }

    @Suppress("DEPRECATION")
    private suspend fun write(c: BluetoothGattCharacteristic?, data: ByteArray) {
        val g = gatt ?: throw IOException("Not connected")
        c ?: throw IOException("Not connected")
        val status = operation {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, data, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) == BluetoothGatt.GATT_SUCCESS
            } else {
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                c.value = data
                g.writeCharacteristic(c)
            }
        }
        if (status != BluetoothGatt.GATT_SUCCESS) throw IOException("Write failed ($status)")
    }

    private suspend fun operation(start: () -> Boolean): Int = opLock.withLock {
        val done = CompletableDeferred<Int>()
        pendingOp = done
        try {
            if (!start()) throw IOException("The operation was rejected")
            try {
                withTimeout(OP_TIMEOUT_MS) { done.await() }
            } catch (e: TimeoutCancellationException) {
                BluetoothGatt.GATT_SUCCESS
            }
        } finally {
            pendingOp = null
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000L
        const val DISCOVER_TIMEOUT_MS = 10_000L
        const val OP_TIMEOUT_MS = 2_000L
    }
}

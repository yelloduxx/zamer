package dev.bezh.zamer.ble

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.withTimeout
import java.security.SecureRandom

interface AuthLink {
    suspend fun writeUpnp(data: ByteArray)
    suspend fun writeAvdtp(data: ByteArray)
    val upnp: ReceiveChannel<ByteArray>
    val avdtp: ReceiveChannel<ByteArray>
}

open class MiAuthException(message: String) : Exception(message)

class PairingRejectedException(message: String) : MiAuthException(message)

class SessionKeys(
    val devKey: ByteArray,
    val appKey: ByteArray,
    val devIv: ByteArray,
    val appIv: ByteArray,
) {
    fun decrypt(frame: ByteArray): DeviceFrame? {
        if (frame.size < 7) return null
        val counter = (frame[0].toInt() and 0xFF) or ((frame[1].toInt() and 0xFF) shl 8)
        val plain = MiCrypto.ccmDecrypt(devKey, nonce(devIv, counter), frame.copyOfRange(2, frame.size)) ?: return null
        return DeviceFrame.parse(plain)
    }

    fun encrypt(plain: ByteArray, counter: Int): ByteArray =
        byteArrayOf(counter.toByte(), (counter shr 8).toByte()) + MiCrypto.ccmEncrypt(appKey, nonce(appIv, counter), plain)

    private fun nonce(iv: ByteArray, counter: Int) =
        iv + ByteArray(4) + byteArrayOf(counter.toByte(), (counter shr 8).toByte(), (counter shr 16).toByte(), (counter shr 24).toByte())
}

class MiAuth(
    private val link: AuthLink,
    private val random: SecureRandom = SecureRandom(),
    private val stepTimeoutMs: Long = 8_000,
) {
    suspend fun register(): ByteArray {
        val own = MiCrypto.generateKeyPair()

        link.writeUpnp(CMD_GET_INFO)
        val info = receiveParcel().payload
        val deviceId = if (info.size >= 24) info.copyOfRange(4, info.size) else ByteArray(0)
        if (deviceId.size != 20) throw MiAuthException("Unexpected device info (${info.size} bytes)")

        link.writeUpnp(CMD_SET_KEY)
        sendParcel(HDR_SEND_KEY_64, own.publicRaw)
        val remoteKey = receiveParcel().payload
        if (remoteKey.size != 64) throw MiAuthException("Unexpected public key length ${remoteKey.size}")

        val derived = MiCrypto.hkdfSha256(MiCrypto.ecdh(own, remoteKey), null, "mible-setup-info".toByteArray(), 64)
        val token = derived.copyOfRange(0, 12)
        val a = derived.copyOfRange(28, 44)
        val deviceIdCt = MiCrypto.ccmEncrypt(a, ByteArray(12) { (16 + it).toByte() }, deviceId, "devID".toByteArray())
        sendParcel(HDR_SEND_DID, deviceIdCt)

        link.writeUpnp(CMD_AUTH)
        val result = receiveUpnp()
        if (!result.contentEquals(CFM_REGISTER_OK)) throw MiAuthException("Pairing rejected (${result.toHex()})")
        return token
    }

    suspend fun login(token: ByteArray): SessionKeys {
        val appRandom = ByteArray(16).also(random::nextBytes)

        link.writeUpnp(CMD_LOGIN)
        sendParcel(HDR_SEND_RANDOM, appRandom)

        val first = receiveAvdtp()
        val deviceRandom = if (first.size == 20 && first.copyOfRange(0, 4).contentEquals(INLINE_RANDOM)) {
            link.writeAvdtp(INLINE_RANDOM_ACK)
            first.copyOfRange(4, first.size)
        } else {
            receiveParcel(first).payload
        }
        if (deviceRandom.size != 16) throw MiAuthException("Unexpected device random length ${deviceRandom.size}")
        val deviceInfo = receiveParcel().payload

        val salt = appRandom + deviceRandom
        val saltInv = deviceRandom + appRandom
        val d = MiCrypto.hkdfSha256(token, salt, "mible-login-info".toByteArray(), 64)
        val keys = SessionKeys(
            devKey = d.copyOfRange(0, 16),
            appKey = d.copyOfRange(16, 32),
            devIv = d.copyOfRange(32, 36),
            appIv = d.copyOfRange(36, 40),
        )
        if (!MiCrypto.hmacSha256(keys.devKey, saltInv).contentEquals(deviceInfo)) {
            throw PairingRejectedException("The device doesn't recognise this pairing")
        }
        sendParcel(HDR_SEND_INFO, MiCrypto.hmacSha256(keys.appKey, salt))

        val result = receiveUpnp()
        if (!result.contentEquals(CFM_LOGIN_OK)) throw PairingRejectedException("Login rejected (${result.toHex()})")
        return keys
    }

    private class Parcel(val header: ByteArray, val payload: ByteArray)

    private suspend fun receiveParcel(first: ByteArray? = null): Parcel {
        val header = first ?: receiveAvdtp()
        if (header.size < 6 || header[0].toInt() != 0 || header[1].toInt() != 0) {
            throw MiAuthException("Expected a data header, got ${header.toHex()}")
        }
        val count = (header[4].toInt() and 0xFF) or ((header[5].toInt() and 0xFF) shl 8)
        link.writeAvdtp(RCV_RDY)
        val payload = java.io.ByteArrayOutputStream()
        var index = 0
        while (index < count) {
            val frame = receiveAvdtp()
            if (frame.size < 2) throw MiAuthException("Short frame")
            index = (frame[0].toInt() and 0xFF) or ((frame[1].toInt() and 0xFF) shl 8)
            payload.write(frame, 2, frame.size - 2)
        }
        link.writeAvdtp(RCV_OK)
        return Parcel(header, payload.toByteArray())
    }

    private suspend fun sendParcel(header: ByteArray, data: ByteArray) {
        link.writeAvdtp(header)
        val ready = receiveAvdtp()
        if (!ready.contentEquals(RCV_RDY)) throw MiAuthException("Device not ready (${ready.toHex()})")
        data.toList().chunked(FRAME_PAYLOAD).forEachIndexed { i, chunk ->
            val n = i + 1
            link.writeAvdtp(byteArrayOf(n.toByte(), (n shr 8).toByte()) + chunk.toByteArray())
        }
        val ok = receiveAvdtp()
        if (!ok.contentEquals(RCV_OK)) throw MiAuthException("Data not accepted (${ok.toHex()})")
    }

    private suspend fun receiveAvdtp() = receive(link.avdtp)

    private suspend fun receiveUpnp() = receive(link.upnp)

    private suspend fun receive(channel: ReceiveChannel<ByteArray>): ByteArray = try {
        withTimeout(stepTimeoutMs) { channel.receive() }
    } catch (e: TimeoutCancellationException) {
        throw MiAuthException("The rangefinder stopped responding")
    }

    private companion object {
        const val FRAME_PAYLOAD = 18

        fun b(hex: String) = hex.hexToBytes()

        val CMD_GET_INFO = b("a2000000")
        val CMD_SET_KEY = b("15000000")
        val CMD_LOGIN = b("24000000")
        val CMD_AUTH = b("13000000")
        val HDR_SEND_KEY_64 = b("000000030400")
        val HDR_SEND_DID = b("000000000200")
        val HDR_SEND_RANDOM = b("0000000b0100")
        val HDR_SEND_INFO = b("0000000a0200")
        val RCV_RDY = b("00000101")
        val RCV_OK = b("00000100")
        val INLINE_RANDOM = b("0000020d")
        val INLINE_RANDOM_ACK = b("00000300")
        val CFM_REGISTER_OK = b("11000000")
        val CFM_LOGIN_OK = b("21000000")
    }
}

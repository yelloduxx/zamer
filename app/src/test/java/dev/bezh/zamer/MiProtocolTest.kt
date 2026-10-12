package dev.bezh.zamer

import dev.bezh.zamer.ble.AuthLink
import dev.bezh.zamer.ble.MiAuth
import dev.bezh.zamer.ble.MiCrypto
import dev.bezh.zamer.ble.SessionKeys
import dev.bezh.zamer.ble.Attribute
import dev.bezh.zamer.ble.DeviceAttr
import dev.bezh.zamer.ble.DeviceFrame
import dev.bezh.zamer.ble.hexToBytes
import dev.bezh.zamer.ble.toHex
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.SecureRandom

class MiProtocolTest {

    private val ccmVectors = listOf(
        "0104070a0d101316191c1f2225282b2e 020d18232e39444f5a65707b - - 137734f5",
        "1215181b1e2124272a2d303336393c3f 07121d28333e49545f6a7580 - 0a1724313e4b58 b38ee3f85165fd170b9394",
        "2326292c2f3235383b3e4144474a4d50 0c17222d38434e59646f7a85 092643607d 111e2b3845525f6c798693a0adbac7d4e1eefb0815222f3c 5e60705aa6859f4777f8ea4dc91bb29fdeb5922db668e2a3afa7e3f7",
        "34373a3d404346494c4f5255585b5e61 111c27323d48535e69747f8a - 1825323f4c596673808d9aa7b4c1cedbe8f5020f 02202da58d75c4ad29f634aab1e8276d9a7b94ab1a20db19",
        "45484b4e5154575a5d606366696c6f72 16212c37424d58636e79848f 092643607d9ab7 1f2c394653606d7a8794a1aebbc8d5e2effc091623303d4a5764717e8b98a5b2bf 5c9939a5fb3de42077ccd380563b1d3c32fecdc54292c89d6aadad0772c32e2d602b1d092a",
    )

    private fun hex(s: String) = if (s == "-") ByteArray(0) else s.hexToBytes()

    @Test
    fun ccmMatchesReferenceImplementation() {
        for (line in ccmVectors) {
            val (key, nonce, aad, pt, ct) = line.split(" ").map(::hex)
            assertEquals(ct.toHex(), MiCrypto.ccmEncrypt(key, nonce, pt, aad).toHex())
            assertArrayEquals(pt, MiCrypto.ccmDecrypt(key, nonce, ct, aad))
        }
    }

    @Test
    fun ccmRejectsTamperedData() {
        val (key, nonce, aad, _, ct) = ccmVectors[2].split(" ").map(::hex)
        val bad = ct.copyOf().also { it[3] = (it[3].toInt() xor 1).toByte() }
        assertNull(MiCrypto.ccmDecrypt(key, nonce, bad, aad))
    }

    private val session = SessionKeys(
        devKey = "930ea6c77879cd9f1b28c3b62cf3ca65".hexToBytes(),
        appKey = "857e1e6258148b6a3b87710182e03a99".hexToBytes(),
        devIv = "df3b10e5".hexToBytes(),
        appIv = "2f9c872a".hexToBytes(),
    )

    @Test
    fun decryptsRealMeasurements() {
        val m1 = session.decrypt("0300e1a01fdf6c1986fd7023fb31".hexToBytes())
        val m2 = session.decrypt("040058d3299a37dda3eaa90a8b07".hexToBytes())
        assertEquals(listOf(Attribute(4, 0, 1463)), (m1 as DeviceFrame.Report).attributes)
        assertEquals(listOf(Attribute(4, 0, 434)), (m2 as DeviceFrame.Report).attributes)
    }

    @Test
    fun decryptsSettingsReports() {
        val reference = session.decrypt("01006d0926c408cb2248da3cd2".hexToBytes()) as DeviceFrame.Report
        val unit = session.decrypt("0200426e003d795463e97bcc39".hexToBytes()) as DeviceFrame.Report
        assertEquals(0L, reference.valueOf(DeviceAttr.SETTING_TYPE, DeviceAttr.REFERENCE_ID))
        assertEquals(0L, unit.valueOf(DeviceAttr.SETTING_TYPE, DeviceAttr.UNIT_ID))
        assertEquals("1000", reference.ack.toHex())
    }

    @Test
    fun parsesReportsAndSkipsOtherMethods() {
        val report = DeviceFrame.parse("0300068002020104000a5b".hexToBytes()) as DeviceFrame.Report
        assertEquals(listOf(Attribute(2, 2, 1), Attribute(4, 0, 2651)), report.attributes)
        assertEquals("1300", report.ack.toHex())
        assertTrue(DeviceFrame.parse("030001ff00".hexToBytes()) is DeviceFrame.Other)
    }

    @Test
    fun multiFrameReportIsAcknowledgedButNotParsed() {
        val frame = DeviceFrame.parse("02100580040005b7".hexToBytes())
        assertTrue(frame is DeviceFrame.MultiFrameReport)
        assertEquals("1210", (frame as DeviceFrame.MultiFrameReport).ack.toHex())
    }

    @Test
    fun encryptsCommandsWithTheAppKey() {
        val command = DeviceAttr.set(msgId = 3, id = DeviceAttr.UNIT_ID, value = 1)
        assertEquals("03000401020201", command.toHex())
        val frame = session.encrypt(command, counter = 5)
        assertEquals(13, frame.size)
        assertEquals("0500", frame.copyOfRange(0, 2).toHex())
        val nonce = session.appIv + ByteArray(4) + byteArrayOf(5, 0, 0, 0)
        assertArrayEquals(command, MiCrypto.ccmDecrypt(session.appKey, nonce, frame.copyOfRange(2, frame.size)))
    }

    @Test
    fun rejectsFramesFromAnotherSession() {
        val other = SessionKeys(session.appKey, session.devKey, session.appIv, session.devIv)
        assertNull(other.decrypt("0300e1a01fdf6c1986fd7023fb31".hexToBytes()))
    }

    @Test
    fun loginMatchesRecordedSession() = runBlocking {
        val token = "caba10d7494e3f63482c5266".hexToBytes()
        val appRandom = "70fdf4d78fed636cafa838ea1c3feda8".hexToBytes()
        val fixedRandom = object : SecureRandom() {
            override fun nextBytes(bytes: ByteArray) {
                appRandom.copyInto(bytes)
            }
        }
        val link = ScriptedLink(
            avdtpScript = listOf(
                Step("0000000b0100", "00000101"),
                Step("010070fdf4d78fed636cafa838ea1c3feda8", "00000100", "0000000d0100"),
                Step("00000101", "010092acfd7519f2f070c32ac276bc5a7872"),
                Step("00000100", "0000000c0200"),
                Step("00000101", "0100a8c49cb833cea6cdc9df40f9390d3f578aa3", "0200f0cb3504cf325df53cfc371eb237"),
                Step("00000100"),
                Step("0000000a0200", "00000101"),
                Step("01003041cd0247af8aa5fe82dd17bcaf43a690bb"),
                Step("02009ed1e390b5252b5e7c4c89a553b9", "00000100"),
            ),
            upnpAfterLast = "21000000",
        )
        val keys = MiAuth(link, fixedRandom).login(token)
        assertArrayEquals(session.devKey, keys.devKey)
        assertArrayEquals(session.appIv, keys.appIv)
        link.assertFinished()
        assertEquals(listOf(Attribute(4, 0, 1463)), (keys.decrypt("0300e1a01fdf6c1986fd7023fb31".hexToBytes()) as DeviceFrame.Report).attributes)
    }

    @Test
    fun registerRoundTripAgainstSimulatedDevice() = runBlocking {
        val device = SimulatedDevice()
        val token = MiAuth(device).register()
        assertEquals(12, token.size)
        assertNotNull(device.derivedToken)
        assertArrayEquals(device.derivedToken, token)
        assertEquals("00626c742e302e7a616d65723074657374303030", device.receivedDeviceId?.toHex())
    }

    private class Step(val expect: String, vararg val reply: String)

    private class ScriptedLink(private val avdtpScript: List<Step>, private val upnpAfterLast: String) : AuthLink {
        override val upnp = Channel<ByteArray>(Channel.UNLIMITED)
        override val avdtp = Channel<ByteArray>(Channel.UNLIMITED)
        private var index = 0
        private val upnpWrites = mutableListOf<String>()

        override suspend fun writeUpnp(data: ByteArray) { upnpWrites += data.toHex() }

        override suspend fun writeAvdtp(data: ByteArray) {
            val step = avdtpScript.getOrNull(index) ?: error("unexpected write ${data.toHex()}")
            assertEquals("AVDTP write #$index", step.expect, data.toHex())
            index++
            step.reply.forEach { avdtp.trySend(it.hexToBytes()) }
            if (index == avdtpScript.size) upnp.trySend(upnpAfterLast.hexToBytes())
        }

        fun assertFinished() {
            assertEquals(avdtpScript.size, index)
            assertEquals(listOf("24000000"), upnpWrites)
        }
    }

    private class SimulatedDevice : AuthLink {
        override val upnp = Channel<ByteArray>(Channel.UNLIMITED)
        override val avdtp = Channel<ByteArray>(Channel.UNLIMITED)
        private val own = MiCrypto.generateKeyPair()
        private val info = "0100000000626c742e302e7a616d65723074657374303030".hexToBytes()
        private var outbound: List<ByteArray> = emptyList()
        private var afterOutbound: () -> Unit = {}
        private var inboundLeft = 0
        private var inbound = java.io.ByteArrayOutputStream()
        private var inboundKind = ""
        private var appKey: ByteArray? = null
        private var idCt: ByteArray? = null
        var derivedToken: ByteArray? = null
        var receivedDeviceId: ByteArray? = null

        private fun sendParcel(header: String, payload: ByteArray, then: () -> Unit = {}) {
            outbound = payload.toList().chunked(18).mapIndexed { i, c -> byteArrayOf((i + 1).toByte(), 0) + c.toByteArray() }
            afterOutbound = then
            avdtp.trySend(header.hexToBytes())
        }

        override suspend fun writeUpnp(data: ByteArray) {
            when (data.toHex()) {
                "a2000000" -> sendParcel("000000000200", info)
                "15000000" -> inboundKind = "key"
                "13000000" -> {
                    val shared = MiCrypto.ecdh(own, appKey!!)
                    val d = MiCrypto.hkdfSha256(shared, null, "mible-setup-info".toByteArray(), 64)
                    derivedToken = d.copyOfRange(0, 12)
                    receivedDeviceId = MiCrypto.ccmDecrypt(
                        d.copyOfRange(28, 44), ByteArray(12) { (16 + it).toByte() }, idCt!!, "devID".toByteArray(),
                    )
                    upnp.trySend((if (receivedDeviceId != null) "11000000" else "12000000").hexToBytes())
                }
            }
        }

        override suspend fun writeAvdtp(data: ByteArray) {
            val hex = data.toHex()
            when {
                hex == "00000101" -> { outbound.forEach { avdtp.trySend(it) }; outbound = emptyList() }
                hex == "00000100" -> afterOutbound().also { afterOutbound = {} }
                inboundLeft == 0 && data.size == 6 && data[0].toInt() == 0 -> {
                    if (hex == "000000000200") inboundKind = "did"
                    inboundLeft = data[4].toInt()
                    inbound = java.io.ByteArrayOutputStream()
                    avdtp.trySend("00000101".hexToBytes())
                }
                inboundLeft > 0 -> {
                    inbound.write(data, 2, data.size - 2)
                    if (--inboundLeft == 0) {
                        avdtp.trySend("00000100".hexToBytes())
                        val got = inbound.toByteArray()
                        if (inboundKind == "key") {
                            appKey = got
                            sendParcel("000000030400", own.publicRaw)
                        } else idCt = got
                    }
                }
            }
        }
    }
}

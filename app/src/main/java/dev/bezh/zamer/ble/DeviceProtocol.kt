package dev.bezh.zamer.ble

import dev.bezh.zamer.domain.UnitSystem

data class Attribute(val type: Int, val id: Int, val value: Long)

@ConsistentCopyVisibility
data class Header private constructor(private val byte0: Int, private val byte1: Int) {
    val fromApp: Boolean get() = byte0 and FROM_APP != 0

    val isSingleFrame: Boolean get() = byte1 shr 4 == 0

    fun ack(): ByteArray = byteArrayOf((byte0 or FROM_APP).toByte(), byte1.toByte())

    companion object {
        private const val FROM_APP = 0x10

        fun of(message: ByteArray): Header? =
            if (message.size < 2) null else Header(message[0].toInt() and 0xFF, message[1].toInt() and 0xFF)
    }
}

@JvmInline
value class MsgId(val value: Int) {
    init {
        require(value in 1..MAX)
    }

    fun next() = MsgId(value % MAX + 1)

    companion object {
        const val MAX = 15
    }
}

sealed interface DeviceFrame {
    data class Report(val header: Header, val attributes: List<Attribute>) : DeviceFrame {
        fun valueOf(type: Int, id: Int): Long? = attributes.firstOrNull { it.type == type && it.id == id }?.value
    }

    data class MultiFrameReport(val header: Header) : DeviceFrame

    data class Other(val raw: ByteArray) : DeviceFrame

    companion object {
        fun parse(p: ByteArray): DeviceFrame {
            val header = Header.of(p)
            if (header == null || p.size < 4 || header.fromApp || p[3].toInt() and 0xFF != METHOD_REPORT) return Other(p)
            return if (header.isSingleFrame) Report(header, attributes(p, 4)) else MultiFrameReport(header)
        }

        private fun attributes(p: ByteArray, start: Int): List<Attribute> {
            val out = mutableListOf<Attribute>()
            var i = start
            while (i + 2 <= p.size) {
                val type = p[i].toInt() and 0xFF
                val width = valueWidth(type) ?: break
                if (i + 2 + width > p.size) break
                var value = 0L
                for (k in 0 until width) value = value shl 8 or (p[i + 2 + k].toLong() and 0xFF)
                out += Attribute(type, p[i + 1].toInt() and 0xFF, value)
                i += 2 + width
            }
            return out
        }

        private fun valueWidth(type: Int) = when (type) {
            0, 1, 2 -> 1
            3, 4 -> 2
            5, 6, 9 -> 4
            7, 8, 0x0A -> 8
            else -> null
        }

        private const val METHOD_REPORT = 0x80
    }
}

object DeviceAttr {
    const val DISTANCE_TYPE = 0x04
    const val DISTANCE_ID = 0x00
    const val SETTING_TYPE = 0x02
    const val REFERENCE_ID = 0x01
    const val UNIT_ID = 0x02

    fun set(msgId: MsgId, id: Int, value: Int): ByteArray =
        byteArrayOf(msgId.value.toByte(), 0, 4, METHOD_SET, SETTING_TYPE.toByte(), id.toByte(), value.toByte())

    private const val METHOD_SET: Byte = 0x01
}

enum class ReferenceEdge { REAR, FRONT }

enum class DeviceUnit { METRES, FEET }

val UnitSystem.deviceUnit: DeviceUnit
    get() = when (this) {
        UnitSystem.METRIC -> DeviceUnit.METRES
        UnitSystem.IMPERIAL -> DeviceUnit.FEET
    }

data class DeviceSettings(val reference: ReferenceEdge? = null, val unit: DeviceUnit? = null)

package dev.bezh.zamer.ble

/**
 * HOTO's own messages, carried decrypted inside the Xiaomi transparent channel (FEA7 out, FEA8 in).
 *
 * Every message starts with a 3-byte header `[type << 4 | msgId][total << 4 | seq][length]`, then a
 * method byte. A device report (method 0x80) holds attributes `[type][id][value, big-endian]` whose
 * value width depends on the type, and the app must acknowledge it with `[header0 | 0x10][header1]`.
 */
data class Attribute(val type: Int, val id: Int, val value: Long)

sealed interface DeviceFrame {
    /** Attributes the device reports; [ack] is the reply it expects. */
    data class Report(val attributes: List<Attribute>, val ack: ByteArray) : DeviceFrame {
        fun valueOf(type: Int, id: Int): Long? = attributes.firstOrNull { it.type == type && it.id == id }?.value
    }

    /** The device's answer to one of our commands, or anything we don't use. */
    data class Other(val raw: ByteArray) : DeviceFrame

    companion object {
        fun parse(p: ByteArray): DeviceFrame {
            if (p.size < 4) return Other(p)
            fun u(i: Int) = p[i].toInt() and 0xFF
            val fromApp = u(0) shr 4 and 1 == 1
            if (fromApp || u(3) != METHOD_REPORT) return Other(p)
            val ack = byteArrayOf((u(0) or 0x10).toByte(), p[1])
            // Reports split over several frames would need reassembly; nothing we read is that long.
            val single = u(1) shr 4 == 0
            return Report(if (single) attributes(p, 4) else emptyList(), ack)
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

/** The attributes Zamer reads or writes. */
object DeviceAttr {
    const val DISTANCE_TYPE = 0x04
    const val DISTANCE_ID = 0x00
    const val SETTING_TYPE = 0x02
    const val REFERENCE_ID = 0x01
    const val UNIT_ID = 0x02

    /** Sets a one-byte setting; [msgId] cycles 1..15 so the device can tell commands apart. */
    fun set(msgId: Int, id: Int, value: Int): ByteArray =
        byteArrayOf(msgId.toByte(), 0, 4, METHOD_SET, SETTING_TYPE.toByte(), id.toByte(), value.toByte())

    private const val METHOD_SET: Byte = 0x01
}

/** Where the rangefinder measures from. */
enum class ReferenceEdge { REAR, FRONT }

/** What the rangefinder's own display shows. Readings always arrive in millimetres. */
enum class DeviceUnit { METRES, FEET }

/** The rangefinder's own settings as it last reported them; null until it does. */
data class DeviceSettings(val reference: ReferenceEdge? = null, val unit: DeviceUnit? = null)

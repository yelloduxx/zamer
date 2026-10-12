package dev.bezh.zamer.ble

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** AES-CCM is implemented here (RFC 3610) because the platform providers don't expose it. */
object MiCrypto {

    class EcKeyPair(val privateKey: PrivateKey, val publicRaw: ByteArray)

    fun generateKeyPair(): EcKeyPair {
        val kp = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val w = (kp.public as ECPublicKey).w
        return EcKeyPair(kp.private, toFixed(w.affineX, 32) + toFixed(w.affineY, 32))
    }

    fun ecdh(own: EcKeyPair, remoteRaw: ByteArray): ByteArray {
        require(remoteRaw.size == 64) { "public key must be 64 bytes" }
        val params = KeyPairGenerator.getInstance("EC")
            .apply { initialize(ECGenParameterSpec("secp256r1")) }
            .generateKeyPair().let { (it.public as ECPublicKey).params }
        val point = ECPoint(BigInteger(1, remoteRaw.copyOfRange(0, 32)), BigInteger(1, remoteRaw.copyOfRange(32, 64)))
        val remote = KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(point, params))
        return KeyAgreement.getInstance("ECDH").run {
            init(own.privateKey)
            doPhase(remote, true)
            generateSecret()
        }
    }

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            doFinal(data)
        }

    fun hkdfSha256(ikm: ByteArray, salt: ByteArray?, info: ByteArray, length: Int): ByteArray {
        val prk = hmacSha256(salt?.takeIf { it.isNotEmpty() } ?: ByteArray(32), ikm)
        val out = ByteArray(length)
        var previous = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < length) {
            previous = hmacSha256(prk, previous + info + byteArrayOf(counter.toByte()))
            val n = minOf(previous.size, length - pos)
            previous.copyInto(out, pos, 0, n)
            pos += n
            counter++
        }
        return out
    }

    fun ccmEncrypt(key: ByteArray, nonce: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0), tagLen: Int = 4): ByteArray {
        val aes = aes(key)
        val tag = cbcMac(aes, nonce, plaintext, aad, tagLen)
        val ct = ctr(aes, nonce, plaintext, startBlock = 1)
        val s0 = aes.doFinal(counterBlock(nonce, 0))
        val encTag = ByteArray(tagLen) { (tag[it].toInt() xor s0[it].toInt()).toByte() }
        return ct + encTag
    }

    fun ccmDecrypt(key: ByteArray, nonce: ByteArray, ciphertextWithTag: ByteArray, aad: ByteArray = ByteArray(0), tagLen: Int = 4): ByteArray? {
        if (ciphertextWithTag.size < tagLen) return null
        val aes = aes(key)
        val ct = ciphertextWithTag.copyOfRange(0, ciphertextWithTag.size - tagLen)
        val encTag = ciphertextWithTag.copyOfRange(ciphertextWithTag.size - tagLen, ciphertextWithTag.size)
        val pt = ctr(aes, nonce, ct, startBlock = 1)
        val s0 = aes.doFinal(counterBlock(nonce, 0))
        val expected = cbcMac(aes, nonce, pt, aad, tagLen)
        val actual = ByteArray(tagLen) { (encTag[it].toInt() xor s0[it].toInt()).toByte() }
        return if (MessageDigest.isEqual(expected, actual)) pt else null
    }

    private fun aes(key: ByteArray): Cipher =
        Cipher.getInstance("AES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES")) }

    private fun counterBlock(nonce: ByteArray, i: Int): ByteArray {
        val b = ByteArray(16)
        b[0] = CCM_LENGTH_FLAGS.toByte()
        nonce.copyInto(b, 1, 0, CCM_NONCE_SIZE)
        b[13] = (i shr 16).toByte(); b[14] = (i shr 8).toByte(); b[15] = i.toByte()
        return b
    }

    private fun ctr(aes: Cipher, nonce: ByteArray, data: ByteArray, startBlock: Int): ByteArray {
        val out = ByteArray(data.size)
        var block = startBlock
        var pos = 0
        while (pos < data.size) {
            val ks = aes.doFinal(counterBlock(nonce, block++))
            val n = minOf(16, data.size - pos)
            for (i in 0 until n) out[pos + i] = (data[pos + i].toInt() xor ks[i].toInt()).toByte()
            pos += n
        }
        return out
    }

    private fun cbcMac(aes: Cipher, nonce: ByteArray, msg: ByteArray, aad: ByteArray, tagLen: Int): ByteArray {
        val b0 = ByteArray(16)
        b0[0] = ((if (aad.isNotEmpty()) CCM_ADATA_FLAG else 0) or ccmTagFlags(tagLen) or CCM_LENGTH_FLAGS).toByte()
        nonce.copyInto(b0, 1, 0, CCM_NONCE_SIZE)
        b0[13] = (msg.size shr 16).toByte(); b0[14] = (msg.size shr 8).toByte(); b0[15] = msg.size.toByte()

        val blocks = ArrayList<ByteArray>()
        blocks += b0
        if (aad.isNotEmpty()) {
            require(aad.size < 0xFF00) { "aad too long" }
            blocks += padTo16(byteArrayOf((aad.size shr 8).toByte(), aad.size.toByte()) + aad)
        }
        if (msg.isNotEmpty()) blocks += padTo16(msg)

        var x = ByteArray(16)
        for (block in blocks) {
            for (chunk in block.asList().chunked(16)) {
                val y = ByteArray(16) { (x[it].toInt() xor chunk[it].toInt()).toByte() }
                x = aes.doFinal(y)
            }
        }
        return x.copyOf(tagLen)
    }

    private const val CCM_NONCE_SIZE = 12

    /** RFC 3610 L: the bytes left for the length or counter after the flags byte and the nonce. */
    private const val CCM_LENGTH_SIZE = 16 - 1 - CCM_NONCE_SIZE

    /** RFC 3610 flags: L' = L - 1 in bits 0..2, M' = (M - 2) / 2 in bits 3..5, Adata in bit 6. */
    private const val CCM_LENGTH_FLAGS = CCM_LENGTH_SIZE - 1
    private const val CCM_ADATA_FLAG = 0x40

    private fun ccmTagFlags(tagLen: Int) = (tagLen - 2) / 2 shl 3

    private fun padTo16(data: ByteArray): ByteArray = data.copyOf((data.size + 15) / 16 * 16)

    private fun toFixed(v: BigInteger, size: Int): ByteArray {
        val raw = v.toByteArray()
        return when {
            raw.size == size -> raw
            raw.size > size -> raw.copyOfRange(raw.size - size, raw.size) // drop sign byte
            else -> ByteArray(size - raw.size) + raw
        }
    }
}

fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

fun String.hexToBytes(): ByteArray {
    val clean = filter { !it.isWhitespace() }
    require(clean.length % 2 == 0) { "odd hex length" }
    return ByteArray(clean.length / 2) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}

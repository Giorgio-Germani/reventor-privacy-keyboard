package org.reventor.sync.protocol

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** RFC 5869 HKDF over HMAC-SHA256. */
object Hkdf {
    private const val HASH_LEN = 32

    fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    fun extract(salt: ByteArray, ikm: ByteArray): ByteArray =
        hmacSha256(if (salt.isEmpty()) ByteArray(HASH_LEN) else salt, ikm)

    fun expand(prk: ByteArray, info: ByteArray, outLength: Int): ByteArray {
        require(outLength <= 255 * HASH_LEN) { "HKDF output too long" }
        val out = ByteArray(outLength)
        var t = ByteArray(0)
        var pos = 0
        var counter = 1
        while (pos < outLength) {
            val input = ByteArray(t.size + info.size + 1)
            t.copyInto(input, 0)
            info.copyInto(input, t.size)
            input[input.size - 1] = counter.toByte()
            t = hmacSha256(prk, input)
            val n = minOf(HASH_LEN, outLength - pos)
            t.copyInto(out, pos, 0, n)
            pos += n
            counter++
        }
        return out
    }

    fun derive(ikm: ByteArray, salt: ByteArray, info: ByteArray, outLength: Int): ByteArray =
        expand(extract(salt, ikm), info, outLength)
}

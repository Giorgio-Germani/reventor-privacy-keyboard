package org.reventor.sync.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class HkdfTest {
    // RFC 5869 Appendix A, Test Case 1 (SHA-256)
    @Test
    fun rfc5869_testCase1() {
        val ikm = ByteArray(22) { 0x0b }
        val salt = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c)
        val info = byteArrayOf(0xf0.toByte(), 0xf1.toByte(), 0xf2.toByte(), 0xf3.toByte(), 0xf4.toByte(), 0xf5.toByte(), 0xf6.toByte(), 0xf7.toByte(), 0xf8.toByte(), 0xf9.toByte())

        val prk = Hkdf.extract(salt, ikm)
        assertEquals(
            "077709362c2e32df0ddc3f0dc47bba6390b6c73bb50f9c3122ec844ad7c2b3e5",
            Hex.encode(prk)
        )

        val okm = Hkdf.expand(prk, info, 42)
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            Hex.encode(okm)
        )
    }

    // RFC 5869 Appendix A, Test Case 3 (SHA-256, zero-length salt/info)
    @Test
    fun rfc5869_testCase3() {
        val ikm = ByteArray(22) { 0x0b }
        val prk = Hkdf.extract(ByteArray(0), ikm)
        val okm = Hkdf.expand(prk, ByteArray(0), 42)
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            Hex.encode(okm)
        )
    }

    @Test
    fun hex_roundTrip() {
        val bytes = ByteArray(256) { it.toByte() }
        assertEquals(bytes.toList(), Hex.decode(Hex.encode(bytes)).toList())
    }
}

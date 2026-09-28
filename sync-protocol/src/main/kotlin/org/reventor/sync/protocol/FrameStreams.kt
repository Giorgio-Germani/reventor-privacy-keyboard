package org.reventor.sync.protocol

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException

/** Length-prefixed frame IO over a socket stream. */
object FrameStreams {
    fun writeFrame(out: DataOutputStream, body: ByteArray) {
        require(body.size <= SyncProtocol.MAX_FRAME_PAYLOAD + 24) { "Frame body too large" }
        out.writeInt(body.size)
        out.write(body)
        out.flush()
    }

    fun readFrame(input: DataInputStream): ByteArray {
        val len = input.readInt()
        if (len < 8 || len > SyncProtocol.MAX_FRAME_PAYLOAD + 24) {
            throw IOException("Invalid frame length $len")
        }
        val body = ByteArray(len)
        input.readFully(body)
        return body
    }

    /** Splits `[u64 seq][payload]`; used by both handshake and encrypted reads. */
    fun splitBody(body: ByteArray): Pair<Long, ByteArray> {
        var seq = 0L
        for (i in 0 until 8) seq = (seq shl 8) or (body[i].toLong() and 0xFF)
        return seq to body.copyOfRange(8, body.size)
    }

    fun joinBody(seq: Long, payload: ByteArray): ByteArray {
        val out = ByteArray(8 + payload.size)
        SessionCryptor.seqBytes(seq).copyInto(out, 0)
        payload.copyInto(out, 8)
        return out
    }
}

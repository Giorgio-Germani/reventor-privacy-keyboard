package org.reventor.sync.protocol

object SyncProtocol {
    /** Bump when the wire format or message set changes incompatibly. */
    const val VERSION: Int = 1

    /** Default TCP port the desktop peer listens on. */
    const val DEFAULT_PORT: Int = 42240

    /** mDNS service type advertised by listening peers. */
    const val SERVICE_TYPE: String = "_reventorsync._tcp"

    /** Largest plaintext (in bytes) accepted inside a single frame. */
    const val MAX_FRAME_PAYLOAD: Int = 4 * 1024 * 1024

    /** Largest clip text we will send or accept, mirroring the keyboard's own capture limit. */
    const val MAX_CLIP_CHARS: Int = 500_000

    /** Seconds to finish handshake + pairing before a connection is dropped. */
    const val PAIRING_TIMEOUT_SECONDS: Int = 120
}

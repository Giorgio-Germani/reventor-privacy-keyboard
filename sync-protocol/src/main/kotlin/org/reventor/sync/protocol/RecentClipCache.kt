package org.reventor.sync.protocol

import java.security.MessageDigest

/**
 * Bounded record of clip content hashes seen recently on this device.
 *
 * This is the loop-suppression mechanism: applying a remote clip to the
 * system clipboard strips any origin metadata, so the only way to know a
 * locally observed clip already traveled through sync is its content hash.
 * Both directions record here before sending/applying.
 */
class RecentClipCache(private val capacity: Int = 64) {
    private val entries = LinkedHashMap<String, Long>()

    @Synchronized
    fun isRecent(hash: String): Boolean = entries.containsKey(hash)

    @Synchronized
    fun record(hash: String, timestamp: Long = System.currentTimeMillis()) {
        entries.remove(hash)
        entries[hash] = timestamp
        while (entries.size > capacity) {
            val eldest = entries.entries.iterator()
            eldest.next()
            eldest.remove()
        }
    }

    @Synchronized
    fun clear() = entries.clear()
}

object ClipHashing {
    fun contentHash(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.toByteArray(Charsets.UTF_8))
        return Hex.encode(digest.copyOfRange(0, 16))
    }
}

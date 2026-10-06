package mesh.dedup

import java.util.LinkedHashMap

/**
 * Thread-safe exact bounded Least-Recently-Used (LRU) cache for packet message IDs.
 *
 * Sized by default to 1,000 IDs (~16 KiB) to eliminate false positives for recent traffic.
 */
class SeenCache(val capacity: Int = DEFAULT_CAPACITY) {

    private class ByteArrayKey(val bytes: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ByteArrayKey) return false
            return bytes.contentEquals(other.bytes)
        }
        override fun hashCode(): Int = bytes.contentHashCode()
    }

    private val map = object : LinkedHashMap<ByteArrayKey, Boolean>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ByteArrayKey, Boolean>?): Boolean {
            return size > capacity
        }
    }

    val size: Int
        @Synchronized get() = map.size

    @Synchronized
    fun put(id: ByteArray) {
        map[ByteArrayKey(id.clone())] = true
    }

    @Synchronized
    fun contains(id: ByteArray): Boolean {
        return map.containsKey(ByteArrayKey(id))
    }

    @Synchronized
    fun clear() {
        map.clear()
    }

    companion object {
        const val DEFAULT_CAPACITY = 1000
    }
}

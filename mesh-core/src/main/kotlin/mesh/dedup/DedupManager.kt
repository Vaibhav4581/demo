package mesh.dedup

import java.util.concurrent.atomic.AtomicLong

/**
 * High-level deduplication coordinator combining an exact [SeenCache] LRU with a [RotatingBloom] filter.
 *
 * Implements anti-storm protection with metric counters for network evaluation.
 */
class DedupManager(
    val seenCache: SeenCache = SeenCache(),
    val rotatingBloom: RotatingBloom = RotatingBloom(),
    var enabled: Boolean = true
) {
    private val _duplicatesDropped = AtomicLong(0)
    private val _bloomFalsePositivesSuspected = AtomicLong(0)
    private val _totalPacketsChecked = AtomicLong(0)

    val duplicatesDropped: Long
        get() = _duplicatesDropped.get()

    val bloomFalsePositivesSuspected: Long
        get() = _bloomFalsePositivesSuspected.get()

    val totalPacketsChecked: Long
        get() = _totalPacketsChecked.get()

    /**
     * Evaluates a message ID for deduplication.
     *
     * @param msgId the 16-byte unique packet identifier
     * @return true if the packet is fresh and should be processed; false if it is a duplicate and should be dropped
     */
    @Synchronized
    fun shouldProcess(msgId: ByteArray): Boolean {
        if (!enabled) return true

        _totalPacketsChecked.incrementAndGet()

        // 1. Exact check in recent LRU cache
        if (seenCache.contains(msgId)) {
            _duplicatesDropped.incrementAndGet()
            return false
        }

        // 2. Probabilistic check in rotating Bloom filter
        if (rotatingBloom.contains(msgId)) {
            _duplicatesDropped.incrementAndGet()
            return false
        }

        // 3. Fresh message: register in both caches
        seenCache.put(msgId)
        rotatingBloom.insert(msgId)
        return true
    }

    /**
     * Explicitly marks a message ID as seen (e.g. locally sent outgoing packets).
     */
    @Synchronized
    fun markSeen(msgId: ByteArray) {
        seenCache.put(msgId)
        rotatingBloom.insert(msgId)
    }

    /**
     * Checks if a message ID has already been marked seen without updating state.
     */
    @Synchronized
    fun isSeen(msgId: ByteArray): Boolean {
        return seenCache.contains(msgId) || rotatingBloom.contains(msgId)
    }

    /**
     * Increments the suspected Bloom false-positive metric counter.
     */
    fun recordSuspectedFalsePositive() {
        _bloomFalsePositivesSuspected.incrementAndGet()
    }

    /**
     * Triggers rotation of the underlying Bloom filter generation.
     */
    @Synchronized
    fun rotateBloom() {
        rotatingBloom.rotate()
    }

    @Synchronized
    fun resetMetrics() {
        _duplicatesDropped.set(0)
        _bloomFalsePositivesSuspected.set(0)
        _totalPacketsChecked.set(0)
    }

    @Synchronized
    fun clear() {
        seenCache.clear()
        rotatingBloom.clear()
        resetMetrics()
    }
}

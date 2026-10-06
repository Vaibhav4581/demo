package mesh.dedup

/**
 * Dual rotating Bloom filter that maintains an active filter and a previous generation filter.
 *
 * Prevents saturation over long uptimes by periodically aging out historical message IDs.
 */
class RotatingBloom(
    val expectedInsertions: Int = BloomFilter.DEFAULT_EXPECTED_INSERTIONS,
    val fpp: Double = BloomFilter.DEFAULT_FPP
) {
    var activeFilter: BloomFilter = BloomFilter(expectedInsertions, fpp)
        private set

    var previousFilter: BloomFilter? = null
        private set

    var rotationCount: Long = 0
        private set

    /**
     * Inserts an item into the active Bloom filter.
     */
    @Synchronized
    fun insert(item: ByteArray) {
        activeFilter.insert(item)
    }

    /**
     * Checks if an item is present in either the active or previous Bloom filter generation.
     */
    @Synchronized
    fun contains(item: ByteArray): Boolean {
        if (activeFilter.contains(item)) return true
        return previousFilter?.contains(item) ?: false
    }

    /**
     * Rotates generation: previous filter is discarded, active filter becomes previous,
     * and a fresh active filter is initialized.
     */
    @Synchronized
    fun rotate() {
        previousFilter = activeFilter
        activeFilter = BloomFilter(expectedInsertions, fpp)
        rotationCount++
    }

    @Synchronized
    fun clear() {
        activeFilter = BloomFilter(expectedInsertions, fpp)
        previousFilter = null
        rotationCount = 0
    }
}

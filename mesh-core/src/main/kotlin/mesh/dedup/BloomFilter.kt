package mesh.dedup

import java.nio.ByteBuffer
import java.util.BitSet
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * High-performance Space-efficient Bloom Filter implementing Kirsch-Mitzenmacher double-hashing.
 *
 * Sized by default for 10,000 items with a 1% false positive probability (~12 KiB).
 */
class BloomFilter(
    val expectedInsertions: Int = DEFAULT_EXPECTED_INSERTIONS,
    val fpp: Double = DEFAULT_FPP,
    bitSet: BitSet? = null,
    elementCount: Int = 0
) {
    val numBits: Int
    val numHashFunctions: Int
    private val bits: BitSet

    var count: Int = elementCount
        private set

    init {
        require(expectedInsertions > 0) { "expectedInsertions must be > 0" }
        require(fpp > 0.0 && fpp < 1.0) { "fpp must be between 0 and 1" }

        numBits = optimalNumBits(expectedInsertions, fpp)
        numHashFunctions = optimalNumHashFunctions(expectedInsertions, numBits)
        bits = bitSet ?: BitSet(numBits)
    }

    /**
     * Inserts an item byte array into the Bloom filter.
     */
    @Synchronized
    fun insert(item: ByteArray) {
        val (h1, h2) = hash128(item)
        for (i in 0 until numHashFunctions) {
            val combined = (h1 + i.toLong() * h2) and Long.MAX_VALUE
            val bitIndex = (combined % numBits).toInt()
            bits.set(bitIndex)
        }
        count++
    }

    /**
     * Checks whether an item might have been inserted.
     *
     * @return true if the item may be in the filter, false if it is definitely not.
     */
    @Synchronized
    fun contains(item: ByteArray): Boolean {
        val (h1, h2) = hash128(item)
        for (i in 0 until numHashFunctions) {
            val combined = (h1 + i.toLong() * h2) and Long.MAX_VALUE
            val bitIndex = (combined % numBits).toInt()
            if (!bits.get(bitIndex)) {
                return false
            }
        }
        return true
    }

    /**
     * Serializes the Bloom filter into a compact byte array suitable for SYNC_SUMMARY payloads.
     */
    @Synchronized
    fun toByteArray(): ByteArray {
        val bitBytes = bits.toByteArray()
        val buffer = ByteBuffer.allocate(HEADER_SIZE + bitBytes.size)
        buffer.put(MAGIC_BYTE)
        buffer.putInt(expectedInsertions)
        buffer.putDouble(fpp)
        buffer.putInt(count)
        buffer.putInt(bitBytes.size)
        buffer.put(bitBytes)
        return buffer.array()
    }

    companion object {
        const val DEFAULT_EXPECTED_INSERTIONS = 10_000
        const val DEFAULT_FPP = 0.01
        private const val MAGIC_BYTE: Byte = 0xBF.toByte()
        private const val HEADER_SIZE = 1 + 4 + 8 + 4 + 4 // 21 bytes

        fun optimalNumBits(n: Int, p: Double): Int {
            return ceil(-n * ln(p) / (ln(2.0) * ln(2.0))).toInt()
        }

        fun optimalNumHashFunctions(n: Int, m: Int): Int {
            return (m.toDouble() / n.toDouble() * ln(2.0)).roundToInt().coerceAtLeast(1)
        }

        /**
         * Fast 128-bit hash returning two 64-bit longs using a Murmur3-inspired algorithm.
         */
        fun hash128(data: ByteArray): Pair<Long, Long> {
            var h1 = 0x123456789ABCDEF0L
            var h2 = -0x6543210FEDCBA988L // unsigned 0x9ABCDEF012345678L
            val c1 = -0x783a54b71192e27bL
            val c2 = 0x4cf5ad432745937fL

            var i = 0
            val len = data.size
            while (i + 16 <= len) {
                var k1 = getLongLittleEndian(data, i)
                var k2 = getLongLittleEndian(data, i + 8)

                k1 *= c1
                k1 = java.lang.Long.rotateLeft(k1, 31)
                k1 *= c2
                h1 = h1 xor k1
                h1 = java.lang.Long.rotateLeft(h1, 27)
                h1 += h2
                h1 = h1 * 5 + 0x52dce729

                k2 *= c2
                k2 = java.lang.Long.rotateLeft(k2, 33)
                k2 *= c1
                h2 = h2 xor k2
                h2 = java.lang.Long.rotateLeft(h2, 31)
                h2 += h1
                h2 = h2 * 5 + 0x38495ab5

                i += 16
            }

            // Remainder bytes
            var k1 = 0L
            var k2 = 0L
            val tail = len - i
            if (tail > 8) {
                for (j in 0 until 8) {
                    k1 = k1 or ((data[i + j].toLong() and 0xFFL) shl (j * 8))
                }
                for (j in 8 until tail) {
                    k2 = k2 or ((data[i + j].toLong() and 0xFFL) shl ((j - 8) * 8))
                }
                k2 *= c2
                k2 = java.lang.Long.rotateLeft(k2, 33)
                k2 *= c1
                h2 = h2 xor k2
            } else if (tail > 0) {
                for (j in 0 until tail) {
                    k1 = k1 or ((data[i + j].toLong() and 0xFFL) shl (j * 8))
                }
            }

            if (tail > 0) {
                k1 *= c1
                k1 = java.lang.Long.rotateLeft(k1, 31)
                k1 *= c2
                h1 = h1 xor k1
            }

            // Finalization
            h1 = h1 xor len.toLong()
            h2 = h2 xor len.toLong()
            h1 += h2
            h2 += h1

            h1 = fmix64(h1)
            h2 = fmix64(h2)
            h1 += h2
            h2 += h1

            return Pair(h1, h2)
        }

        private fun getLongLittleEndian(b: ByteArray, offset: Int): Long {
            var value = 0L
            for (i in 0 until 8) {
                value = value or ((b[offset + i].toLong() and 0xFFL) shl (i * 8))
            }
            return value
        }

        private fun fmix64(k: Long): Long {
            var h = k
            h = h xor (h ushr 33)
            h *= -0xae502812aa7333L
            h = h xor (h ushr 33)
            h *= -0x3b314601e57a13adL
            h = h xor (h ushr 33)
            return h
        }

        /**
         * Deserializes a [BloomFilter] from a byte array.
         */
        fun fromByteArray(bytes: ByteArray): BloomFilter {
            require(bytes.size >= HEADER_SIZE) { "Byte array too short for BloomFilter: ${bytes.size} bytes" }
            val buffer = ByteBuffer.wrap(bytes)
            val magic = buffer.get()
            require(magic == MAGIC_BYTE) { "Invalid BloomFilter magic byte: $magic" }
            val expectedInsertions = buffer.int
            val fpp = buffer.double
            val count = buffer.int
            val bitBytesSize = buffer.int
            require(bytes.size >= HEADER_SIZE + bitBytesSize) { "Corrupted BloomFilter byte payload" }
            val bitBytes = ByteArray(bitBytesSize)
            buffer.get(bitBytes)
            val bitSet = BitSet.valueOf(bitBytes)
            return BloomFilter(expectedInsertions, fpp, bitSet, count)
        }
    }
}

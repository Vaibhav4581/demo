package mesh.dedup

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import assertk.assertions.isLessThan
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.security.SecureRandom

class BloomFilterTest {

    @Test
    fun `no false negatives and false positive rate within target over 100,000 items`() {
        val targetFpp = 0.01
        val numItems = 10_000
        val numTestQueries = 100_000

        val bloom = BloomFilter(expectedInsertions = numItems, fpp = targetFpp)
        val random = SecureRandom(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))

        val inserted = HashSet<ByteBuffer>(numItems)
        val idBuffer = ByteArray(16)

        // Insert 10,000 unique items
        for (i in 0 until numItems) {
            random.nextBytes(idBuffer)
            bloom.insert(idBuffer)
            inserted.add(ByteBuffer.wrap(idBuffer.clone()))
        }

        // Acceptance criteria 1: NO false negatives (all inserted items MUST be found)
        for (item in inserted) {
            val found = bloom.contains(item.array())
            assertThat(found, "False negative detected for item").isTrue()
        }

        // Acceptance criteria 2: Measure false-positive rate over 100,000 distinct non-inserted items
        var falsePositives = 0
        var testedCount = 0

        while (testedCount < numTestQueries) {
            random.nextBytes(idBuffer)
            val wrapped = ByteBuffer.wrap(idBuffer)
            if (!inserted.contains(wrapped)) {
                if (bloom.contains(idBuffer)) {
                    falsePositives++
                }
                testedCount++
            }
        }

        val measuredFpp = falsePositives.toDouble() / numTestQueries
        // Target is 1%; acceptance criteria requires within ~2x of target (e.g. <= 2.0%)
        assertThat(measuredFpp).isLessThan(targetFpp * 2.0)
    }

    @Test
    fun `serialization round-trip preserves state and queries`() {
        val bloom = BloomFilter(expectedInsertions = 500, fpp = 0.01)
        val item1 = "message-one-12345".toByteArray()
        val item2 = "message-two-67890".toByteArray()
        val item3 = "message-three-abc".toByteArray()

        bloom.insert(item1)
        bloom.insert(item2)

        val serialized = bloom.toByteArray()
        val restored = BloomFilter.fromByteArray(serialized)

        assertThat(restored.contains(item1)).isTrue()
        assertThat(restored.contains(item2)).isTrue()
        assertThat(restored.contains(item3)).isEqualTo(false)
        assertThat(restored.count).isEqualTo(2)
    }
}

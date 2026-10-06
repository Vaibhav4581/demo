package mesh.dedup

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

class RotatingBloomTest {

    @Test
    fun `rotation maintains item for two generations then evicts`() {
        val rotating = RotatingBloom(expectedInsertions = 100, fpp = 0.01)
        val item = byteArrayOf(10, 20, 30, 40)

        rotating.insert(item)
        assertThat(rotating.contains(item)).isTrue()

        // Rotate generation 1: item moves to previousFilter
        rotating.rotate()
        assertThat(rotating.contains(item)).isTrue()

        // Rotate generation 2: item is evicted
        rotating.rotate()
        assertThat(rotating.contains(item)).isFalse()
    }

    @Test
    fun `DedupManager tracks duplicatesDropped and suppresses duplicate processing`() {
        val dedup = DedupManager()
        val msgId1 = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16)
        val msgId2 = byteArrayOf(9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9, 9)

        // First presentation of msgId1
        assertThat(dedup.shouldProcess(msgId1)).isTrue()
        assertThat(dedup.duplicatesDropped).isEqualTo(0)

        // Second presentation of msgId1 (duplicate)
        assertThat(dedup.shouldProcess(msgId1)).isFalse()
        assertThat(dedup.duplicatesDropped).isEqualTo(1)

        // First presentation of msgId2
        assertThat(dedup.shouldProcess(msgId2)).isTrue()
        assertThat(dedup.duplicatesDropped).isEqualTo(1)

        // Third presentation of msgId1
        assertThat(dedup.shouldProcess(msgId1)).isFalse()
        assertThat(dedup.duplicatesDropped).isEqualTo(2)
    }
}

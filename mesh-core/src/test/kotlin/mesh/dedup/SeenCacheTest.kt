package mesh.dedup

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test

class SeenCacheTest {

    @Test
    fun `inserts and retrieves items`() {
        val cache = SeenCache(capacity = 10)
        val id1 = byteArrayOf(1, 2, 3)
        val id2 = byteArrayOf(4, 5, 6)

        cache.put(id1)
        assertThat(cache.contains(id1)).isTrue()
        assertThat(cache.contains(id2)).isFalse()
    }

    @Test
    fun `evicts least-recently-used items when exceeding capacity`() {
        val capacity = 5
        val cache = SeenCache(capacity = capacity)

        val items = (1..7).map { byteArrayOf(it.toByte()) }

        for (item in items) {
            cache.put(item)
        }

        assertThat(cache.size).isEqualTo(capacity)
        // items[0] (1) and items[1] (2) should be evicted
        assertThat(cache.contains(items[0])).isFalse()
        assertThat(cache.contains(items[1])).isFalse()
        // items[2..6] (3..7) should remain
        for (i in 2..6) {
            assertThat(cache.contains(items[i])).isTrue()
        }
    }
}

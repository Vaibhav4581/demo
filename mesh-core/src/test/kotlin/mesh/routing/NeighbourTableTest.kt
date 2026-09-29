package mesh.routing

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.doesNotContain
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import mesh.protocol.NodeId
import org.junit.jupiter.api.Test

class NeighbourTableTest {

    private val peerA = NodeId(byteArrayOf(1, 1, 1, 1, 1, 1, 1, 1))
    private val peerB = NodeId(byteArrayOf(2, 2, 2, 2, 2, 2, 2, 2))

    @Test
    fun `connect and disconnect updates active neighbours`() {
        val table = NeighbourTable()

        table.onPeerConnected(peerA, 1000L)
        assertThat(table.isNeighbour(peerA)).isTrue()
        assertThat(table.getActiveNeighbours()).contains(peerA)

        val removed = table.onPeerDisconnected(peerA)
        assertThat(removed).isTrue()
        assertThat(table.isNeighbour(peerA)).isFalse()
    }

    @Test
    fun `evicts neighbours missing 3 consecutive HELLO intervals`() {
        val table = NeighbourTable()
        val helloIntervalMs = 10_000L

        // Peer A last seen at t = 0
        table.onPeerConnected(peerA, 0L)
        // Peer B last seen at t = 25_000 (missed only 1 interval by t = 35_000)
        table.onPeerConnected(peerB, 25_000L)

        // At t = 31_000, peer A missed > 3 * 10s (30s)
        val evicted = table.checkMissedHellos(nowMs = 31_000L, helloIntervalMs = helloIntervalMs, missedLimit = 3)

        assertThat(evicted).contains(peerA)
        assertThat(evicted).doesNotContain(peerB)
        assertThat(table.isNeighbour(peerA)).isFalse()
        assertThat(table.isNeighbour(peerB)).isTrue()
    }
}

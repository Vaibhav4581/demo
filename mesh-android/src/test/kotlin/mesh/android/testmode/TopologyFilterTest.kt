package mesh.android.testmode

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import mesh.protocol.NodeId
import org.junit.jupiter.api.Test

class TopologyFilterTest {

    private val filter = TopologyFilter()
    private val nodeA = NodeId(byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0))
    private val nodeB = NodeId(byteArrayOf(2, 0, 0, 0, 0, 0, 0, 0))
    private val nodeC = NodeId(byteArrayOf(3, 0, 0, 0, 0, 0, 0, 0))
    private val nodeD = NodeId(byteArrayOf(4, 0, 0, 0, 0, 0, 0, 0))

    @Test
    fun `when filter is disabled all peers are allowed`() {
        filter.isEnabled = false
        assertThat(filter.isPeerAllowed(nodeA)).isTrue()
        assertThat(filter.isPeerAllowed(nodeB)).isTrue()
    }

    @Test
    fun `when filter is enabled only allow-listed peers are permitted`() {
        filter.isEnabled = true
        filter.setAllowedPeers(listOf(nodeB))

        assertThat(filter.isPeerAllowed(nodeB)).isTrue()
        assertThat(filter.isPeerAllowed(nodeA)).isFalse()
        assertThat(filter.isPeerAllowed(nodeC)).isFalse()
    }

    @Test
    fun `configureChain properly sets intermediate nodes in chain A-B-C-D`() {
        val chain = listOf(nodeA, nodeB, nodeC, nodeD)

        // For Node B: allowed are A and C
        filter.configureChain(nodeB, chain)
        assertThat(filter.isEnabled).isTrue()
        assertThat(filter.isPeerAllowed(nodeA)).isTrue()
        assertThat(filter.isPeerAllowed(nodeC)).isTrue()
        assertThat(filter.isPeerAllowed(nodeD)).isFalse()

        // For Node A (end of chain): only B is allowed
        filter.configureChain(nodeA, chain)
        assertThat(filter.isPeerAllowed(nodeB)).isTrue()
        assertThat(filter.isPeerAllowed(nodeC)).isFalse()
        assertThat(filter.isPeerAllowed(nodeD)).isFalse()
    }

    @Test
    fun `add and remove allowed peers dynamically`() {
        filter.isEnabled = true
        filter.addAllowedPeer(nodeA)
        assertThat(filter.isPeerAllowed(nodeA)).isTrue()

        filter.removeAllowedPeer(nodeA)
        assertThat(filter.isPeerAllowed(nodeA)).isFalse()
    }
}

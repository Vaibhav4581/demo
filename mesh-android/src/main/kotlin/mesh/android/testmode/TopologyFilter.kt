package mesh.android.testmode

import mesh.protocol.NodeId
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Filter that enforces artificial topologies (such as chain A-B-C-D) among physical
 * phones located within the same physical radio range.
 *
 * When enabled, any peer connection or incoming packet from a node NOT in the allow-list
 * is rejected, allowing reproducible multi-hop evaluation on a single desk.
 */
class TopologyFilter {

    @Volatile
    var isEnabled: Boolean = false

    private val allowedPeers = CopyOnWriteArraySet<NodeId>()

    fun setAllowedPeers(peers: Collection<NodeId>) {
        allowedPeers.clear()
        allowedPeers.addAll(peers)
    }

    fun addAllowedPeer(peer: NodeId) {
        allowedPeers.add(peer)
    }

    fun removeAllowedPeer(peer: NodeId) {
        allowedPeers.remove(peer)
    }

    fun clear() {
        allowedPeers.clear()
    }

    /**
     * Returns true if communication with [peer] is permitted.
     */
    fun isPeerAllowed(peer: NodeId): Boolean {
        if (!isEnabled) return true
        return allowedPeers.contains(peer)
    }

    fun getAllowedPeers(): Set<NodeId> = allowedPeers.toSet()

    /**
     * Configures a chain topology for a known ordered list of nodes.
     * For node at index `i`, its allowed peers are at indices `i - 1` and `i + 1`.
     */
    fun configureChain(myNodeId: NodeId, chainNodes: List<NodeId>) {
        val myIndex = chainNodes.indexOf(myNodeId)
        if (myIndex == -1) {
            clear()
            return
        }
        val allowed = mutableListOf<NodeId>()
        if (myIndex > 0) allowed.add(chainNodes[myIndex - 1])
        if (myIndex < chainNodes.size - 1) allowed.add(chainNodes[myIndex + 1])
        setAllowedPeers(allowed)
        isEnabled = true
    }
}

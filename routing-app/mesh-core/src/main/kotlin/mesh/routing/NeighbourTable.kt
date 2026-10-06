package mesh.routing

import mesh.protocol.NodeId
import java.util.concurrent.ConcurrentHashMap

data class NeighbourInfo(
    val nodeId: NodeId,
    var lastSeenMs: Long,
    var displayName: String? = null,
    var publicKey: ByteArray? = null
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NeighbourInfo) return false
        return nodeId == other.nodeId
    }

    override fun hashCode(): Int = nodeId.hashCode()
}

/**
 * Tracks physical direct neighbours discovered via radio transport events and periodic HELLO broadcasts.
 */
class NeighbourTable {

    private val neighbours = ConcurrentHashMap<NodeId, NeighbourInfo>()

    @Synchronized
    fun onPeerConnected(peer: NodeId, nowMs: Long) {
        val existing = neighbours[peer]
        if (existing != null) {
            existing.lastSeenMs = nowMs
        } else {
            neighbours[peer] = NeighbourInfo(peer, nowMs)
        }
    }

    @Synchronized
    fun onPeerDisconnected(peer: NodeId): Boolean {
        return neighbours.remove(peer) != null
    }

    @Synchronized
    fun onHelloReceived(
        peer: NodeId,
        nowMs: Long,
        displayName: String? = null,
        publicKey: ByteArray? = null
    ) {
        val existing = neighbours[peer]
        if (existing != null) {
            existing.lastSeenMs = nowMs
            if (displayName != null) existing.displayName = displayName
            if (publicKey != null) existing.publicKey = publicKey
        } else {
            neighbours[peer] = NeighbourInfo(peer, nowMs, displayName, publicKey)
        }
    }

    @Synchronized
    fun onPacketReceived(peer: NodeId, nowMs: Long) {
        val existing = neighbours[peer]
        if (existing != null) {
            existing.lastSeenMs = nowMs
        } else {
            neighbours[peer] = NeighbourInfo(peer, nowMs)
        }
    }

    /**
     * Checks for neighbours that have missed [missedLimit] consecutive HELLO intervals and evicts them.
     *
     * @return list of evicted [NodeId]s so dependent routes can be invalidated.
     */
    @Synchronized
    fun checkMissedHellos(nowMs: Long, helloIntervalMs: Long, missedLimit: Int = 3): List<NodeId> {
        val timeoutMs = helloIntervalMs * missedLimit
        val evicted = mutableListOf<NodeId>()
        val iterator = neighbours.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMs - entry.value.lastSeenMs > timeoutMs) {
                evicted.add(entry.key)
                iterator.remove()
            }
        }
        return evicted
    }

    fun isNeighbour(nodeId: NodeId): Boolean = neighbours.containsKey(nodeId)

    fun getActiveNeighbours(): Set<NodeId> = neighbours.keys.toSet()

    fun getNeighbour(nodeId: NodeId): NeighbourInfo? = neighbours[nodeId]

    fun getAllNeighbours(): List<NeighbourInfo> = neighbours.values.toList()

    @Synchronized
    fun clear() {
        neighbours.clear()
    }
}

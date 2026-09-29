package mesh.fakes

import mesh.protocol.NodeId
import mesh.protocol.Packet
import mesh.transport.Transport
import mesh.transport.TransportListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/**
 * In-memory simulated radio transport for linking nodes directly in JVM tests.
 */
class FakeTransport(override val localNodeId: NodeId) : Transport {

    val listeners = CopyOnWriteArrayList<TransportListener>()
    val connectedPeers = ConcurrentHashMap<NodeId, FakeTransport>()
    val sentTransmissions = CopyOnWriteArrayList<Pair<NodeId, Packet>>()

    var dropAllOutbound: Boolean = false

    fun link(other: FakeTransport) {
        connectedPeers[other.localNodeId] = other
        other.connectedPeers[localNodeId] = this

        listeners.forEach { it.onPeerConnected(other.localNodeId) }
        other.listeners.forEach { it.onPeerConnected(localNodeId) }
    }

    fun unlink(other: FakeTransport) {
        connectedPeers.remove(other.localNodeId)
        other.connectedPeers.remove(localNodeId)

        listeners.forEach { it.onPeerDisconnected(other.localNodeId) }
        other.listeners.forEach { it.onPeerDisconnected(localNodeId) }
    }

    override fun send(peer: NodeId, packet: Packet): Boolean {
        if (dropAllOutbound) return false
        val peerTransport = connectedPeers[peer] ?: return false
        sentTransmissions.add(Pair(peer, packet))
        peerTransport.receive(localNodeId, packet)
        return true
    }

    override fun broadcast(packet: Packet): Int {
        if (dropAllOutbound) return 0
        var count = 0
        for ((peerId, peerTransport) in connectedPeers) {
            sentTransmissions.add(Pair(peerId, packet))
            peerTransport.receive(localNodeId, packet)
            count++
        }
        return count
    }

    fun receive(fromPeer: NodeId, packet: Packet) {
        listeners.forEach { it.onPacketReceived(fromPeer, packet) }
    }

    override fun registerListener(listener: TransportListener) {
        listeners.add(listener)
    }

    override fun unregisterListener(listener: TransportListener) {
        listeners.remove(listener)
    }

    fun clear() {
        connectedPeers.clear()
        sentTransmissions.clear()
    }
}

package mesh.sim

import mesh.protocol.NodeId
import mesh.protocol.Packet
import mesh.transport.Transport
import mesh.transport.TransportListener
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Simulator-backed [Transport] implementation interacting with [SimNetwork].
 */
class SimTransport(
    override val localNodeId: NodeId,
    val network: SimNetwork
) : Transport {

    private val listeners = CopyOnWriteArrayList<TransportListener>()

    override fun send(peer: NodeId, packet: Packet): Boolean {
        if (!network.isNodeAlive(localNodeId)) return false
        return network.transmitPacket(localNodeId, peer, packet)
    }

    override fun broadcast(packet: Packet): Int {
        if (!network.isNodeAlive(localNodeId)) return 0
        val peers = network.getConnectedPeers(localNodeId)
        var count = 0
        for (peer in peers) {
            if (network.transmitPacket(localNodeId, peer, packet)) {
                count++
            }
        }
        return count
    }

    override fun registerListener(listener: TransportListener) {
        listeners.add(listener)
    }

    override fun unregisterListener(listener: TransportListener) {
        listeners.remove(listener)
    }

    fun notifyPeerConnected(peer: NodeId) {
        listeners.forEach { it.onPeerConnected(peer) }
    }

    fun notifyPeerDisconnected(peer: NodeId) {
        listeners.forEach { it.onPeerDisconnected(peer) }
    }

    fun receive(fromPeer: NodeId, packet: Packet) {
        if (network.isNodeAlive(localNodeId)) {
            listeners.forEach { it.onPacketReceived(fromPeer, packet) }
        }
    }

    fun clearPeers() {
        val peers = network.getConnectedPeers(localNodeId)
        peers.forEach { notifyPeerDisconnected(it) }
    }
}

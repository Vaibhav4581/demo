package mesh.transport

import mesh.protocol.NodeId
import mesh.protocol.Packet

/**
 * Listener interface for transport lifecycle and message reception events.
 */
interface TransportListener {
    /**
     * Invoked when a direct physical peer connection is established.
     */
    fun onPeerConnected(peer: NodeId)

    /**
     * Invoked when a direct physical peer connection is lost.
     */
    fun onPeerDisconnected(peer: NodeId)

    /**
     * Invoked when a packet arrives directly from a peer over the radio link.
     */
    fun onPacketReceived(fromPeer: NodeId, packet: Packet)
}

/**
 * Abstraction over ad-hoc peer-to-peer radio communication (Nearby Connections, Wi-Fi Direct, BLE).
 *
 * Routing and delivery logic strictly communicate through this interface without direct Android or radio imports.
 */
interface Transport {
    /**
     * The [NodeId] of the local device.
     */
    val localNodeId: NodeId

    /**
     * Transmits a packet directly to an immediate one-hop neighbour.
     *
     * @param peer the direct neighbour to send to
     * @param packet the packet to transmit
     * @return true if transmission was accepted by the transport link, false otherwise
     */
    fun send(peer: NodeId, packet: Packet): Boolean

    /**
     * Broadcasts a packet to all currently connected direct neighbours.
     *
     * @param packet the packet to transmit
     * @return the number of direct neighbours to which transmission was initiated
     */
    fun broadcast(packet: Packet): Int

    /**
     * Registers an event listener for peer lifecycle and incoming packets.
     */
    fun registerListener(listener: TransportListener)

    /**
     * Unregisters an event listener.
     */
    fun unregisterListener(listener: TransportListener)
}

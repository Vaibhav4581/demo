package mesh.routing

import mesh.dedup.DedupManager
import mesh.crypto.KeyStore
import mesh.protocol.HelloPayload
import mesh.protocol.NodeId
import mesh.protocol.Packet
import mesh.protocol.PacketFactory
import mesh.protocol.PacketType
import mesh.protocol.destNodeId
import mesh.protocol.isBroadcast
import mesh.protocol.isExpired
import mesh.protocol.originNodeId
import mesh.protocol.withDecrementedTtl
import mesh.delivery.RateLimiter
import mesh.transport.Clock
import mesh.transport.Transport
import mesh.transport.TransportListener
import java.util.concurrent.atomic.AtomicLong

enum class DropReason {
    DUPLICATE,
    TTL_EXPIRED,
    PACKET_EXPIRED,
    NO_ROUTE,
    LOOPBACK_BLOCKED,
    RATE_LIMITED
}

interface RouterListener {
    fun onLocalDelivery(packet: Packet) {}
    fun onPacketRelayed(packet: Packet, nextHop: NodeId) {}
    fun onPacketDropped(packet: Packet, reason: DropReason) {}
    fun onAckReceived(packet: Packet) {}
    fun onSyncSummaryReceived(fromPeer: NodeId, packet: Packet) {}
}

/**
 * Core multi-hop mesh routing engine implementing Section 4.2.
 *
 * Handles inbound packet deduplication, opportunistic distance-vector route learning,
 * local delivery, automatic ACK generation, split-horizon forwarding with fallback flooding,
 * and rate-limited relay protection.
 */
class Router(
    val localNodeId: NodeId,
    val transport: Transport,
    val clock: Clock,
    val neighbourTable: NeighbourTable = NeighbourTable(),
    val routeTable: RouteTable = RouteTable(),
    val dedupManager: DedupManager = DedupManager(),
    /** Optional key store — when provided, peer public keys from HELLO packets are registered. */
    val keyStore: KeyStore? = null,
    /** Optional rate limiter to bound packet forwarding and prevent relay storms. */
    val forwardRateLimiter: RateLimiter? = null
) : TransportListener {

    private val listeners = mutableListOf<RouterListener>()
    private val _rateLimitedDrops = AtomicLong(0)

    val rateLimitedDrops: Long
        get() = _rateLimitedDrops.get()

    init {
        transport.registerListener(this)
    }

    fun addListener(listener: RouterListener) {
        synchronized(listeners) { listeners.add(listener) }
    }

    fun removeListener(listener: RouterListener) {
        synchronized(listeners) { listeners.remove(listener) }
    }

    override fun onPeerConnected(peer: NodeId) {
        neighbourTable.onPeerConnected(peer, clock.nowMs())
    }

    override fun onPeerDisconnected(peer: NodeId) {
        neighbourTable.onPeerDisconnected(peer)
        routeTable.invalidateRoutesThrough(peer)
    }

    override fun onPacketReceived(fromPeer: NodeId, packet: Packet) {
        val nowMs = clock.nowMs()
        neighbourTable.onPacketReceived(fromPeer, nowMs)

        // 1. Packet expiration check
        if (packet.isExpired(nowMs)) {
            notifyDropped(packet, DropReason.PACKET_EXPIRED)
            return
        }

        // 2. Anti-storm deduplication check
        val msgIdBytes = packet.msgId.toByteArray()
        if (!dedupManager.shouldProcess(msgIdBytes)) {
            notifyDropped(packet, DropReason.DUPLICATE)
            return
        }

        // 3. Opportunistic reverse-route learning from incoming traffic
        val origin = packet.originNodeId
        if (origin != localNodeId) {
            val learnedCost = if (origin == fromPeer) 1 else packet.hopCount + 1
            routeTable.learnRoute(dest = origin, via = fromPeer, cost = learnedCost, nowMs = nowMs)
        }

        // 4. Packet-type dispatch
        when (packet.type) {
            PacketType.HELLO -> handleHello(fromPeer, packet, nowMs)
            PacketType.SYNC_SUMMARY -> handleSyncSummary(fromPeer, packet)
            PacketType.ACK -> handleAck(fromPeer, packet)
            PacketType.DATA -> handleData(fromPeer, packet)
            else -> notifyDropped(packet, DropReason.NO_ROUTE)
        }
    }

    private fun handleHello(fromPeer: NodeId, packet: Packet, nowMs: Long) {
        try {
            val payload = HelloPayload.decode(packet.payload.toByteArray())
            neighbourTable.onHelloReceived(fromPeer, nowMs, payload.displayName, payload.publicKey)
            // Register the peer's public key so X25519Crypto can encrypt to them.
            if (payload.publicKey.isNotEmpty()) {
                keyStore?.registerPeerKey(fromPeer, payload.publicKey)
            }
        } catch (_: Exception) {
            neighbourTable.onHelloReceived(fromPeer, nowMs)
        }
        // HELLO packets are 1-hop broadcasts; do not forward.
    }

    private fun handleSyncSummary(fromPeer: NodeId, packet: Packet) {
        val listenersCopy = synchronized(listeners) { listeners.toList() }
        listenersCopy.forEach { it.onSyncSummaryReceived(fromPeer, packet) }
        // SYNC_SUMMARY packets are 1-hop link local; do not forward.
    }

    private fun handleAck(fromPeer: NodeId, packet: Packet) {
        if (packet.destNodeId == localNodeId) {
            val listenersCopy = synchronized(listeners) { listeners.toList() }
            listenersCopy.forEach {
                it.onLocalDelivery(packet)
                it.onAckReceived(packet)
            }
        } else if (packet.ttl > 1) {
            val forwarded = packet.withDecrementedTtl()
            forwardPacket(forwarded, fromPeer)
        } else {
            notifyDropped(packet, DropReason.TTL_EXPIRED)
        }
    }

    private fun handleData(fromPeer: NodeId, packet: Packet) {
        val isForLocal = packet.destNodeId == localNodeId
        val isBcast = packet.isBroadcast

        // Deliver locally if addressed to this node or broadcast
        if (isForLocal || isBcast) {
            val listenersCopy = synchronized(listeners) { listeners.toList() }
            listenersCopy.forEach { it.onLocalDelivery(packet) }

            // Direct unicast messages must be acknowledged back to sender
            if (isForLocal) {
                sendAck(packet.originNodeId, packet.msgId.toByteArray())
            }
        }

        // Forward if not local or if broadcast, provided TTL permits
        if (!isForLocal || isBcast) {
            if (packet.ttl > 1) {
                val forwarded = packet.withDecrementedTtl()
                forwardPacket(forwarded, fromPeer)
            } else {
                notifyDropped(packet, DropReason.TTL_EXPIRED)
            }
        }
    }

    /**
     * Sends an ACK packet back to [targetOrigin] for message [ackedMsgId].
     */
    fun sendAck(targetOrigin: NodeId, ackedMsgId: ByteArray) {
        val ackPacket = PacketFactory.createAck(
            origin = localNodeId,
            dest = targetOrigin,
            ackedMsgId = ackedMsgId,
            createdAtMs = clock.nowMs()
        )
        // Mark locally sent ACK in dedup cache so we don't re-process if looped
        dedupManager.markSeen(ackPacket.msgId.toByteArray())
        forwardPacket(ackPacket, incomingPeer = null)
    }

    /**
     * Forwards a packet to downstream neighbours with loop prevention.
     *
     * @param packet the packet to forward
     * @param incomingPeer the neighbour from which the packet was received, or null if originating locally
     */
    fun forwardPacket(packet: Packet, incomingPeer: NodeId?): Boolean {
        // Enforce forwarding rate limiter on relayed packets
        if (incomingPeer != null && forwardRateLimiter != null && !forwardRateLimiter.tryAcquire()) {
            notifyDropped(packet, DropReason.RATE_LIMITED)
            return false
        }

        val activeNeighbours = neighbourTable.getActiveNeighbours()
        val candidateNeighbours = if (incomingPeer != null) {
            activeNeighbours.filter { it != incomingPeer }
        } else {
            activeNeighbours.toList()
        }

        if (candidateNeighbours.isEmpty()) {
            return false
        }

        if (packet.isBroadcast) {
            var sentCount = 0
            for (peer in candidateNeighbours) {
                if (transport.send(peer, packet)) {
                    sentCount++
                    notifyRelayed(packet, peer)
                }
            }
            return sentCount > 0
        } else {
            // Unicast routing: check learned route table
            val dest = packet.destNodeId!!
            val route = routeTable.getRoute(dest, clock.nowMs())

            if (route != null && candidateNeighbours.contains(route.nextHop)) {
                val success = transport.send(route.nextHop, packet)
                if (success) {
                    notifyRelayed(packet, route.nextHop)
                    return true
                } else {
                    // Unicast failed: invalidate route and fall back to flooding
                    routeTable.invalidateRoute(dest)
                }
            }

            // Fall back to flooding candidate neighbours
            var floodSuccessCount = 0
            for (peer in candidateNeighbours) {
                if (transport.send(peer, packet)) {
                    floodSuccessCount++
                    notifyRelayed(packet, peer)
                }
            }
            return floodSuccessCount > 0
        }
    }

    private fun notifyRelayed(packet: Packet, nextHop: NodeId) {
        val listenersCopy = synchronized(listeners) { listeners.toList() }
        listenersCopy.forEach { it.onPacketRelayed(packet, nextHop) }
    }

    internal fun notifyDropped(packet: Packet, reason: DropReason) {
        if (reason == DropReason.RATE_LIMITED) {
            _rateLimitedDrops.incrementAndGet()
        }
        val listenersCopy = synchronized(listeners) { listeners.toList() }
        listenersCopy.forEach { it.onPacketDropped(packet, reason) }
    }
}

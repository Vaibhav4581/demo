package mesh.sim

import mesh.node.MeshNode
import mesh.protocol.NodeId
import mesh.protocol.Packet
import java.util.PriorityQueue
import java.util.Random
import java.util.concurrent.atomic.AtomicLong

data class SimLink(
    val nodeA: NodeId,
    val nodeB: NodeId,
    val latencyMs: Long = 10L,
    val lossRate: Double = 0.0,
    var isActive: Boolean = true
)

data class SimEvent(
    val timeMs: Long,
    val sequence: Long,
    val description: String = "",
    val action: () -> Unit
) : Comparable<SimEvent> {
    override fun compareTo(other: SimEvent): Int {
        val timeCmp = timeMs.compareTo(other.timeMs)
        return if (timeCmp != 0) timeCmp else sequence.compareTo(other.sequence)
    }
}

/**
 * Discrete-event network simulation engine managing virtual time, links, and packet propagation.
 */
class SimNetwork(
    val clock: VirtualClock = VirtualClock(),
    val random: Random = Random(42)
) {
    private val eventQueue = PriorityQueue<SimEvent>()
    private val sequenceCounter = AtomicLong(0)

    private val transports = mutableMapOf<NodeId, SimTransport>()
    private val meshNodes = mutableMapOf<NodeId, MeshNode>()
    private val links = mutableMapOf<Pair<NodeId, NodeId>, SimLink>()
    private val killedNodes = mutableSetOf<NodeId>()

    var totalTransmissionsAttempted: Long = 0
        private set
    var totalTransmissionsDelivered: Long = 0
        private set
    var totalPacketsLostInFlight: Long = 0
        private set

    fun registerNode(nodeId: NodeId, transport: SimTransport, node: MeshNode? = null) {
        transports[nodeId] = transport
        if (node != null) {
            meshNodes[nodeId] = node
        }
    }

    fun getTransport(nodeId: NodeId): SimTransport? = transports[nodeId]
    fun getNode(nodeId: NodeId): MeshNode? = meshNodes[nodeId]

    fun isNodeAlive(nodeId: NodeId): Boolean = !killedNodes.contains(nodeId)

    fun killNode(nodeId: NodeId) {
        if (killedNodes.add(nodeId)) {
            // Notify neighbours of peer disconnection
            val peers = getConnectedPeers(nodeId)
            for (peer in peers) {
                transports[peer]?.notifyPeerDisconnected(nodeId)
            }
            transports[nodeId]?.clearPeers()
        }
    }

    fun reviveNode(nodeId: NodeId) {
        if (killedNodes.remove(nodeId)) {
            // Re-establish links with active neighbours
            for ((pair, link) in links) {
                if (link.isActive) {
                    val peer = when (nodeId) {
                        pair.first -> pair.second
                        pair.second -> pair.first
                        else -> null
                    }
                    if (peer != null && isNodeAlive(peer)) {
                        transports[nodeId]?.notifyPeerConnected(peer)
                        transports[peer]?.notifyPeerConnected(nodeId)
                    }
                }
            }
        }
    }

    fun connect(
        nodeA: NodeId,
        nodeB: NodeId,
        latencyMs: Long = 10L,
        lossRate: Double = 0.0
    ) {
        val link1 = SimLink(nodeA, nodeB, latencyMs, lossRate, isActive = true)
        val link2 = SimLink(nodeB, nodeA, latencyMs, lossRate, isActive = true)
        links[Pair(nodeA, nodeB)] = link1
        links[Pair(nodeB, nodeA)] = link2

        if (isNodeAlive(nodeA) && isNodeAlive(nodeB)) {
            transports[nodeA]?.notifyPeerConnected(nodeB)
            transports[nodeB]?.notifyPeerConnected(nodeA)
        }
    }

    fun disconnect(nodeA: NodeId, nodeB: NodeId) {
        links[Pair(nodeA, nodeB)]?.isActive = false
        links[Pair(nodeB, nodeA)]?.isActive = false

        transports[nodeA]?.notifyPeerDisconnected(nodeB)
        transports[nodeB]?.notifyPeerDisconnected(nodeA)
    }

    fun isConnected(nodeA: NodeId, nodeB: NodeId): Boolean {
        if (!isNodeAlive(nodeA) || !isNodeAlive(nodeB)) return false
        val link = links[Pair(nodeA, nodeB)] ?: return false
        return link.isActive
    }

    fun getConnectedPeers(nodeId: NodeId): Set<NodeId> {
        if (!isNodeAlive(nodeId)) return emptySet()
        val peers = mutableSetOf<NodeId>()
        for ((pair, link) in links) {
            if (pair.first == nodeId && link.isActive && isNodeAlive(pair.second)) {
                peers.add(pair.second)
            }
        }
        return peers
    }

    fun getLink(from: NodeId, to: NodeId): SimLink? = links[Pair(from, to)]

    /**
     * Schedules transmission of a packet over a radio link between two nodes.
     */
    fun transmitPacket(from: NodeId, to: NodeId, packet: Packet): Boolean {
        if (!isConnected(from, to)) return false

        val link = links[Pair(from, to)] ?: return false
        totalTransmissionsAttempted++

        val willDrop = link.lossRate > 0.0 && random.nextDouble() < link.lossRate
        val arrivalTime = clock.nowMs() + link.latencyMs

        scheduleAt(arrivalTime, "Packet [${packet.type}] $from -> $to") {
            if (willDrop) {
                totalPacketsLostInFlight++
            } else {
                if (isConnected(from, to)) {
                    totalTransmissionsDelivered++
                    transports[to]?.receive(from, packet)
                }
            }
        }
        return true
    }

    fun schedule(delayMs: Long, description: String = "", action: () -> Unit) {
        scheduleAt(clock.nowMs() + delayMs, description, action)
    }

    fun scheduleAt(timeMs: Long, description: String = "", action: () -> Unit) {
        require(timeMs >= clock.nowMs()) {
            "Cannot schedule event at $timeMs in the past (now is ${clock.nowMs()})"
        }
        eventQueue.add(SimEvent(timeMs, sequenceCounter.incrementAndGet(), description, action))
    }

    /**
     * Schedules periodic node ticks (e.g. HELLO broadcasts, outbox retries) every [intervalMs].
     */
    fun schedulePeriodicTicks(intervalMs: Long = 1000L) {
        schedule(intervalMs, "Periodic node tick") {
            for ((_, node) in meshNodes) {
                if (isNodeAlive(node.nodeId)) {
                    node.tick(clock.nowMs())
                }
            }
            schedulePeriodicTicks(intervalMs)
        }
    }

    /**
     * Advances simulation and executes the next pending event.
     * @return true if an event was executed, false if event queue is empty
     */
    fun step(): Boolean {
        val nextEvent = eventQueue.poll() ?: return false
        clock.advanceTo(nextEvent.timeMs)
        nextEvent.action()
        return true
    }

    /**
     * Runs discrete-event simulation until [targetTimeMs].
     */
    fun runUntil(targetTimeMs: Long) {
        while (!eventQueue.isEmpty() && eventQueue.peek().timeMs <= targetTimeMs) {
            step()
        }
        clock.advanceTo(targetTimeMs)
    }

    fun runFor(durationMs: Long) {
        runUntil(clock.nowMs() + durationMs)
    }

    fun pendingEventsCount(): Int = eventQueue.size

    fun reset() {
        eventQueue.clear()
        transports.clear()
        meshNodes.clear()
        links.clear()
        killedNodes.clear()
        totalTransmissionsAttempted = 0
        totalTransmissionsDelivered = 0
        totalPacketsLostInFlight = 0
    }
}

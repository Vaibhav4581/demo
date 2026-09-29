package mesh.node

import mesh.delivery.AntiEntropyManager
import mesh.delivery.DeliveryState
import mesh.delivery.Outbox
import mesh.protocol.NodeId
import mesh.protocol.Packet
import mesh.protocol.PacketFactory
import mesh.protocol.PacketType
import mesh.protocol.destNodeId
import mesh.protocol.isBroadcast
import mesh.protocol.originNodeId
import mesh.routing.RouteEntry
import mesh.routing.Router
import mesh.routing.RouterListener
import mesh.storage.InMemoryMessageStore
import mesh.storage.MessageRecord
import mesh.storage.MessageStore
import mesh.transport.Clock
import mesh.transport.Crypto
import mesh.transport.NoOpCrypto
import mesh.transport.SystemClock
import mesh.transport.Transport
import mesh.transport.TransportListener
import java.util.concurrent.CopyOnWriteArrayList

/**
 * High-level facade for a participating mesh node.
 *
 * Coordinates routing, deduplication, message persistence, outbox store-and-forward,
 * anti-entropy, and encryption.
 */
class MeshNode(
    val nodeId: NodeId,
    val transport: Transport,
    val clock: Clock = SystemClock,
    val messageStore: MessageStore = InMemoryMessageStore(),
    val crypto: Crypto = NoOpCrypto(),
    val config: NodeConfig = NodeConfig()
) : TransportListener, RouterListener {

    val router: Router = Router(
        localNodeId = nodeId,
        transport = transport,
        clock = clock
    )

    val outbox: Outbox = Outbox(
        router = router,
        messageStore = messageStore,
        clock = clock
    )

    val antiEntropyManager: AntiEntropyManager = AntiEntropyManager(
        localNodeId = nodeId,
        transport = transport,
        messageStore = messageStore,
        clock = clock
    )

    private val messageReceivedCallbacks = CopyOnWriteArrayList<(from: NodeId, payload: ByteArray, isBroadcast: Boolean) -> Unit>()
    private val deliveredCallbacks = CopyOnWriteArrayList<(msgId: ByteArray, ackPacket: Packet) -> Unit>()

    private var lastHelloTimeMs: Long = 0L

    init {
        router.addListener(this)
        transport.registerListener(this)
    }

    /**
     * Registers a callback invoked whenever an incoming DATA message is delivered locally.
     */
    fun onMessageReceived(callback: (from: NodeId, payload: ByteArray, isBroadcast: Boolean) -> Unit) {
        messageReceivedCallbacks.add(callback)
    }

    /**
     * Registers a callback invoked when any direct unicast message receives an ACK.
     */
    fun onDelivered(callback: (msgId: ByteArray, ackPacket: Packet) -> Unit) {
        deliveredCallbacks.add(callback)
    }

    /**
     * Sends a direct (unicast) message to [dest].
     *
     * The payload is encrypted with [Crypto], stored in [MessageStore], and enqueued in [Outbox].
     */
    fun send(
        dest: NodeId,
        payload: ByteArray,
        onDelivered: ((ackPacket: Packet) -> Unit)? = null
    ): Packet {
        val nowMs = clock.nowMs()
        val encrypted = crypto.encrypt(dest, payload)
        val packet = PacketFactory.createData(
            origin = nodeId,
            dest = dest,
            payload = encrypted,
            ttl = config.defaultTtl,
            createdAtMs = nowMs,
            expiresAtMs = nowMs + config.messageLifetimeMs
        )

        val record = MessageRecord(
            msgId = packet.msgId.toByteArray(),
            origin = nodeId,
            dest = dest,
            payload = payload, // Store unencrypted plaintext locally for user display
            createdAtMs = nowMs,
            expiresAtMs = packet.expiresAtMs,
            deliveryState = DeliveryState.QUEUED,
            isIncoming = false
        )
        messageStore.saveMessage(record)

        outbox.enqueue(packet, onDelivered = { ack ->
            deliveredCallbacks.forEach { it(packet.msgId.toByteArray(), ack) }
            onDelivered?.invoke(ack)
        })

        return packet
    }

    /**
     * Broadcasts a message to all reachable nodes in the mesh.
     */
    fun broadcast(payload: ByteArray): Packet {
        val nowMs = clock.nowMs()
        val packet = PacketFactory.createData(
            origin = nodeId,
            dest = null,
            payload = payload,
            ttl = config.defaultTtl,
            createdAtMs = nowMs,
            expiresAtMs = nowMs + config.messageLifetimeMs
        )

        val record = MessageRecord(
            msgId = packet.msgId.toByteArray(),
            origin = nodeId,
            dest = null,
            payload = payload,
            createdAtMs = nowMs,
            expiresAtMs = packet.expiresAtMs,
            deliveryState = DeliveryState.QUEUED,
            isIncoming = false
        )
        messageStore.saveMessage(record)

        outbox.enqueue(packet)
        return packet
    }

    /**
     * Broadcasts a HELLO discovery packet to immediate neighbours.
     */
    fun sendHello(): Packet {
        val helloPacket = PacketFactory.createHello(
            origin = nodeId,
            displayName = config.displayName,
            publicKey = config.publicKey,
            createdAtMs = clock.nowMs()
        )
        lastHelloTimeMs = clock.nowMs()
        router.forwardPacket(helloPacket, incomingPeer = null)
        return helloPacket
    }

    /**
     * Periodic maintenance step: handles HELLO interval, outbox retries, and route expirations.
     */
    fun tick(nowMs: Long = clock.nowMs()) {
        // 1. Periodic HELLO broadcast
        if (nowMs - lastHelloTimeMs >= config.helloIntervalMs) {
            sendHello()
        }

        // 2. Check for missing neighbours
        val evictedPeers = router.neighbourTable.checkMissedHellos(
            nowMs = nowMs,
            helloIntervalMs = config.helloIntervalMs,
            missedLimit = config.helloMissedLimit
        )
        for (peer in evictedPeers) {
            router.routeTable.invalidateRoutesThrough(peer)
        }

        // 3. Process outbox backoff retries
        outbox.processRetries()

        // 4. Purge expired routes and messages
        router.routeTable.purgeExpired(nowMs, config.routeTtlMs)
        messageStore.purgeExpired(nowMs)
    }

    override fun onPeerConnected(peer: NodeId) {
        // Retransmit any messages held in store-and-forward
        outbox.onPeerConnected(peer)
        // Initiate anti-entropy reconciliation
        antiEntropyManager.onPeerConnected(peer)
        // Announce presence immediately
        sendHello()
    }

    override fun onPeerDisconnected(peer: NodeId) {
        // Managed by Router through TransportListener
    }

    override fun onPacketReceived(fromPeer: NodeId, packet: Packet) {
        // Router processes packet automatically as a registered TransportListener
    }

    // RouterListener implementation
    override fun onLocalDelivery(packet: Packet) {
        if (packet.type == PacketType.DATA) {
            val decryptedPayload = crypto.decrypt(packet.originNodeId, packet.payload.toByteArray())
            val record = MessageRecord(
                msgId = packet.msgId.toByteArray(),
                origin = packet.originNodeId,
                dest = packet.destNodeId,
                payload = decryptedPayload,
                createdAtMs = packet.createdAtMs,
                expiresAtMs = packet.expiresAtMs,
                deliveryState = DeliveryState.DELIVERED,
                isIncoming = true
            )
            messageStore.saveMessage(record)
            messageReceivedCallbacks.forEach { it(packet.originNodeId, decryptedPayload, packet.isBroadcast) }
        }
    }

    override fun onAckReceived(packet: Packet) {
        outbox.onAckReceived(packet)
    }

    override fun onSyncSummaryReceived(fromPeer: NodeId, packet: Packet) {
        antiEntropyManager.onSyncSummaryReceived(fromPeer, packet)
    }

    fun getDeliveryState(msgId: ByteArray): DeliveryState? {
        return messageStore.getMessage(msgId)?.deliveryState
    }

    fun getDirectNeighbours(): Set<NodeId> = router.neighbourTable.getActiveNeighbours()

    fun getKnownRoutes(): List<RouteEntry> = router.routeTable.getAllValidRoutes(clock.nowMs(), config.routeTtlMs)
}

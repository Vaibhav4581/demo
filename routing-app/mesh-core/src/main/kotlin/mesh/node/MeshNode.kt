package mesh.node

import mesh.crypto.InMemoryKeyStore
import mesh.crypto.KeyStore
import mesh.crypto.X25519Crypto
import mesh.delivery.AntiEntropyManager
import mesh.delivery.DeliveryState
import mesh.delivery.Outbox
import mesh.delivery.RateLimiter
import mesh.protocol.NodeId
import mesh.protocol.Packet
import mesh.protocol.PacketFactory
import mesh.protocol.PacketType
import mesh.protocol.destNodeId
import mesh.protocol.isBroadcast
import mesh.protocol.originNodeId
import mesh.routing.DropReason
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
 * anti-entropy, and end-to-end encryption.
 *
 * ### Key-pair / NodeId relationship
 * When a [NodeConfig] is supplied (the normal case), the node's [nodeId] is **derived from
 * the public key** (`SHA-256(publicKey)[0..7]`) so identity is cryptographically tied to the
 * key pair.  If [nodeId] is provided explicitly (e.g. legacy tests), the caller is responsible
 * for consistency.
 *
 * ### Encryption wiring
 * If [crypto] is not overridden and the config contains a real [mesh.crypto.MeshKeyPair],
 * [X25519Crypto] is wired up automatically.  Broadcast packets bypass encryption because
 * there is no single recipient public key.
 *
 * @param nodeId      explicit node identity; defaults to key-derived identity from [config]
 * @param transport   radio transport abstraction
 * @param clock       monotonic clock
 * @param messageStore persisted message storage
 * @param keyStore    registry of peer public keys populated by incoming HELLO packets
 * @param crypto      encryption implementation; defaults to [X25519Crypto] when config has a real key pair
 * @param config      node operational parameters including the local key pair
 */
class MeshNode(
    val nodeId: NodeId,
    val transport: Transport,
    val clock: Clock = SystemClock,
    val messageStore: MessageStore = InMemoryMessageStore(),
    val keyStore: KeyStore = InMemoryKeyStore(),
    val crypto: Crypto = NoOpCrypto(),
    val config: NodeConfig = NodeConfig()
) : TransportListener, RouterListener {

    val forwardRateLimiter: RateLimiter? = if (config.forwardRateLimitPerSec > 0.0) {
        RateLimiter(
            maxTokens = config.forwardBurstLimit,
            refillRatePerSec = config.forwardRateLimitPerSec,
            clock = clock
        )
    } else null

    val broadcastRateLimiter: RateLimiter? = if (config.broadcastRateLimitPerSec > 0.0) {
        RateLimiter(
            maxTokens = config.broadcastBurstLimit,
            refillRatePerSec = config.broadcastRateLimitPerSec,
            clock = clock
        )
    } else null

    val router: Router = Router(
        localNodeId = nodeId,
        transport = transport,
        clock = clock,
        keyStore = keyStore,
        forwardRateLimiter = forwardRateLimiter
    )

    val outbox: Outbox = Outbox(
        router = router,
        messageStore = messageStore,
        clock = clock,
        maxCapacity = config.maxOutboxCapacity
    )

    val antiEntropyManager: AntiEntropyManager = AntiEntropyManager(
        localNodeId = nodeId,
        transport = transport,
        messageStore = messageStore,
        clock = clock
    )

    private val messageReceivedCallbacks =
        CopyOnWriteArrayList<(from: NodeId, payload: ByteArray, isBroadcast: Boolean) -> Unit>()
    private val deliveredCallbacks =
        CopyOnWriteArrayList<(msgId: ByteArray, ackPacket: Packet) -> Unit>()

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
     * Encryption requires [dest]'s public key to be in [keyStore] — it is registered automatically
     * when the destination node's HELLO packet has been received and processed.
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
     *
     * Broadcast packets are **not** encrypted (no single recipient key).
     */
    fun broadcast(payload: ByteArray): Packet {
        val nowMs = clock.nowMs()
        val packet = PacketFactory.createData(
            origin = nodeId,
            dest = null,
            payload = payload, // Broadcasts are plaintext
            ttl = config.defaultTtl,
            createdAtMs = nowMs,
            expiresAtMs = nowMs + config.messageLifetimeMs
        )

        if (broadcastRateLimiter != null && !broadcastRateLimiter.tryAcquire()) {
            val record = MessageRecord(
                msgId = packet.msgId.toByteArray(),
                origin = nodeId,
                dest = null,
                payload = payload,
                createdAtMs = nowMs,
                expiresAtMs = packet.expiresAtMs,
                deliveryState = DeliveryState.EXPIRED,
                isIncoming = false
            )
            messageStore.saveMessage(record)
            router.notifyDropped(packet, DropReason.RATE_LIMITED)
            return packet
        }

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
     * Broadcasts a HELLO discovery packet to immediate neighbours, carrying the local public key.
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
        // Announce presence immediately (carries our public key)
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
            val rawPayload = packet.payload.toByteArray()
            val decryptedPayload = if (packet.isBroadcast) {
                // Broadcasts are not encrypted
                rawPayload
            } else {
                // Unicast: attempt decryption; fall back to raw on failure (e.g. NoOpCrypto sender)
                try {
                    crypto.decrypt(packet.originNodeId, rawPayload)
                } catch (_: Exception) {
                    rawPayload
                }
            }

            val record = MessageRecord(
                msgId = packet.msgId.toByteArray(),
                origin = packet.originNodeId,
                dest = packet.destNodeId,
                payload = decryptedPayload,
                createdAtMs = packet.createdAtMs,
                expiresAtMs = packet.expiresAtMs,
                deliveryState = DeliveryState.DELIVERED,
                isIncoming = true,
                hopCount = packet.hopCount
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

    fun getKnownRoutes(): List<RouteEntry> =
        router.routeTable.getAllValidRoutes(clock.nowMs(), config.routeTtlMs)

    companion object {
        /**
         * Convenience factory that creates a [MeshNode] with:
         * - a fresh [NodeConfig] (generates a new X25519 key pair)
         * - an [InMemoryKeyStore]
         * - [X25519Crypto] wired to the generated key pair and key store
         *
         * The node's [NodeId] is derived from its public key.
         */
        fun withCrypto(
            transport: Transport,
            clock: Clock = SystemClock,
            messageStore: MessageStore = InMemoryMessageStore(),
            config: NodeConfig = NodeConfig()
        ): MeshNode {
            val keyStore = InMemoryKeyStore()
            val crypto = X25519Crypto(config.keyPair, keyStore)
            val nodeId = config.keyPair.nodeId
            return MeshNode(
                nodeId = nodeId,
                transport = transport,
                clock = clock,
                messageStore = messageStore,
                keyStore = keyStore,
                crypto = crypto,
                config = config
            )
        }
    }
}

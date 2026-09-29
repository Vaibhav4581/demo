package mesh.android.transport

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.google.android.gms.common.api.Status
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import mesh.protocol.NodeId
import mesh.protocol.Packet
import mesh.protocol.PacketCodec
import mesh.protocol.PacketCodecException
import mesh.transport.Transport
import mesh.transport.TransportListener
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Concrete [Transport] implementation using Google Nearby Connections API.
 * Configured with [Strategy.P2P_CLUSTER] for ad-hoc, multi-peer mesh networking.
 */
class NearbyTransport(
    private val context: Context,
    override val localNodeId: NodeId,
    private val connectionsClient: ConnectionsClient = Nearby.getConnectionsClient(context),
    val logger: TransportLogger = TransportLogger(),
    val serviceId: String = DEFAULT_SERVICE_ID
) : Transport {

    companion object {
        const val DEFAULT_SERVICE_ID = "org.mesh.emergency"
        val MESH_STRATEGY: Strategy = Strategy.P2P_CLUSTER
    }

    private val isStarted = AtomicBoolean(false)
    private val listeners = CopyOnWriteArraySet<TransportListener>()

    // Active connection state: endpointId <-> peer NodeId
    private val endpointToPeer = ConcurrentHashMap<String, NodeId>()
    private val peerToEndpoint = ConcurrentHashMap<NodeId, String>()

    // Pending connection requests waiting for resolution
    private val pendingEndpoints = ConcurrentHashMap<String, NodeId>()

    val isRunning: Boolean
        get() = isStarted.get()

    override fun registerListener(listener: TransportListener) {
        listeners.add(listener)
    }

    override fun unregisterListener(listener: TransportListener) {
        listeners.remove(listener)
    }

    /**
     * Starts concurrent advertising and discovery using [Strategy.P2P_CLUSTER].
     */
    fun start() {
        if (!isStarted.compareAndSet(false, true)) {
            return
        }

        startAdvertising()
        startDiscovery()
    }

    /**
     * Stops advertising, discovery, and closes all active peer links.
     */
    fun stop() {
        if (!isStarted.compareAndSet(true, false)) {
            return
        }

        try {
            connectionsClient.stopAdvertising()
        } catch (e: Exception) {
            logger.log(
                TransportLogEvent(
                    eventType = TransportEventType.ADVERTISING_FAILED,
                    details = "Error stopping advertising: ${e.message}"
                )
            )
        }

        try {
            connectionsClient.stopDiscovery()
        } catch (e: Exception) {
            logger.log(
                TransportLogEvent(
                    eventType = TransportEventType.DISCOVERY_FAILED,
                    details = "Error stopping discovery: ${e.message}"
                )
            )
        }

        try {
            connectionsClient.stopAllEndpoints()
        } catch (e: Exception) {
            // Ignore on shutdown
        }

        // Notify disconnect for all remaining peers
        val activePeers = peerToEndpoint.keys.toList()
        endpointToPeer.clear()
        peerToEndpoint.clear()
        pendingEndpoints.clear()

        for (peer in activePeers) {
            logger.log(
                TransportLogEvent(
                    eventType = TransportEventType.PEER_DISCONNECTED,
                    peerIdHex = peer.toHex(),
                    details = "Transport stopped"
                )
            )
            listeners.forEach { it.onPeerDisconnected(peer) }
        }
    }

    /**
     * Pauses discovery scanning (used by duty cycling during sleep intervals).
     */
    fun pauseDiscovery() {
        if (!isStarted.get()) return
        try {
            connectionsClient.stopDiscovery()
            logger.log(
                TransportLogEvent(
                    eventType = TransportEventType.DISCOVERY_FAILED,
                    details = "Duty cycle: paused discovery scanning"
                )
            )
        } catch (e: Exception) {
            // Ignore
        }
    }

    /**
     * Resumes discovery scanning (used by duty cycling during active scan windows).
     */
    fun resumeDiscovery() {
        if (!isStarted.get()) return
        startDiscovery()
    }

    private fun startAdvertising() {
        val advertisingOptions = AdvertisingOptions.Builder()
            .setStrategy(MESH_STRATEGY)
            .build()

        connectionsClient.startAdvertising(
            localNodeId.toHex(),
            serviceId,
            connectionLifecycleCallback,
            advertisingOptions
        ).addOnSuccessListener {
            logger.log(
                TransportLogEvent(
                    eventType = TransportEventType.ADVERTISING_STARTED,
                    details = "Advertising as ${localNodeId.toHex()} with service $serviceId"
                )
            )
        }.addOnFailureListener { e ->
            logger.log(
                TransportLogEvent(
                    eventType = TransportEventType.ADVERTISING_FAILED,
                    details = "Advertising failed: ${e.message}"
                )
            )
        }
    }

    private fun startDiscovery() {
        val discoveryOptions = DiscoveryOptions.Builder()
            .setStrategy(MESH_STRATEGY)
            .build()

        connectionsClient.startDiscovery(
            serviceId,
            endpointDiscoveryCallback,
            discoveryOptions
        ).addOnSuccessListener {
            logger.log(
                TransportLogEvent(
                    eventType = TransportEventType.DISCOVERY_STARTED,
                    details = "Discovery started for service $serviceId"
                )
            )
        }.addOnFailureListener { e ->
            logger.log(
                TransportLogEvent(
                    eventType = TransportEventType.DISCOVERY_FAILED,
                    details = "Discovery failed: ${e.message}"
                )
            )
        }
    }

    override fun send(peer: NodeId, packet: Packet): Boolean {
        if (!isStarted.get()) return false

        val endpointId = peerToEndpoint[peer] ?: return false

        val bytes = try {
            PacketCodec.encode(packet)
        } catch (e: PacketCodecException) {
            logger.log(
                TransportLogEvent(
                    eventType = TransportEventType.PACKET_REJECTED_OVERSIZE,
                    peerIdHex = peer.toHex(),
                    details = "Packet encode error: ${e.message}"
                )
            )
            return false
        }

        if (bytes.size > PacketCodec.MAX_PACKET_SIZE) {
            logger.log(
                TransportLogEvent(
                    eventType = TransportEventType.PACKET_REJECTED_OVERSIZE,
                    peerIdHex = peer.toHex(),
                    details = "Packet size ${bytes.size} exceeds maximum ${PacketCodec.MAX_PACKET_SIZE}"
                )
            )
            return false
        }

        return try {
            connectionsClient.sendPayload(endpointId, Payload.fromBytes(bytes))
            logger.log(
                TransportLogEvent(
                    eventType = TransportEventType.PACKET_SENT,
                    peerIdHex = peer.toHex(),
                    endpointId = endpointId,
                    packetSummary = "id=${packet.msgId.toStringUtf8()} type=${packet.type} bytes=${bytes.size}"
                )
            )
            true
        } catch (e: Exception) {
            logger.log(
                TransportLogEvent(
                    eventType = TransportEventType.CONNECTION_FAILED,
                    peerIdHex = peer.toHex(),
                    endpointId = endpointId,
                    details = "Failed to send payload: ${e.message}"
                )
            )
            false
        }
    }

    override fun broadcast(packet: Packet): Int {
        if (!isStarted.get()) return 0

        val activeEndpoints = peerToEndpoint.values.toList()
        if (activeEndpoints.isEmpty()) return 0

        val bytes = try {
            PacketCodec.encode(packet)
        } catch (e: PacketCodecException) {
            logger.log(
                TransportLogEvent(
                    eventType = TransportEventType.PACKET_REJECTED_OVERSIZE,
                    details = "Broadcast encode error: ${e.message}"
                )
            )
            return 0
        }

        if (bytes.size > PacketCodec.MAX_PACKET_SIZE) {
            logger.log(
                TransportLogEvent(
                    eventType = TransportEventType.PACKET_REJECTED_OVERSIZE,
                    details = "Broadcast packet size ${bytes.size} exceeds maximum ${PacketCodec.MAX_PACKET_SIZE}"
                )
            )
            return 0
        }

        return try {
            connectionsClient.sendPayload(activeEndpoints, Payload.fromBytes(bytes))
            logger.log(
                TransportLogEvent(
                    eventType = TransportEventType.PACKET_BROADCAST,
                    details = "Broadcast to ${activeEndpoints.size} endpoints, size=${bytes.size}"
                )
            )
            activeEndpoints.size
        } catch (e: Exception) {
            logger.log(
                TransportLogEvent(
                    eventType = TransportEventType.CONNECTION_FAILED,
                    details = "Failed to broadcast payload: ${e.message}"
                )
            )
            0
        }
    }

    fun getConnectedPeers(): Set<NodeId> = peerToEndpoint.keys.toSet()

    // ---------------------------------------------------------------------------------------------
    // Nearby Callbacks
    // ---------------------------------------------------------------------------------------------

    @VisibleForTesting
    internal val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            val peerHex = info.endpointName
            val peerNodeId = try {
                NodeId.fromHex(peerHex)
            } catch (e: Exception) {
                null
            }

            if (peerNodeId == null || peerNodeId == localNodeId) {
                return
            }

            // If already connected or connecting, do not create duplicate requests
            if (peerToEndpoint.containsKey(peerNodeId) || pendingEndpoints.values.contains(peerNodeId)) {
                return
            }

            // Deterministic collision breaker: node with lower hex ID initiates connection
            if (localNodeId.toHex() < peerHex) {
                logger.log(
                    TransportLogEvent(
                        eventType = TransportEventType.CONNECTION_INITIATED,
                        peerIdHex = peerHex,
                        endpointId = endpointId,
                        details = "Initiating connection request to discovered peer"
                    )
                )
                pendingEndpoints[endpointId] = peerNodeId
                connectionsClient.requestConnection(
                    localNodeId.toHex(),
                    endpointId,
                    connectionLifecycleCallback
                ).addOnFailureListener { e ->
                    pendingEndpoints.remove(endpointId)
                    logger.log(
                        TransportLogEvent(
                            eventType = TransportEventType.CONNECTION_FAILED,
                            peerIdHex = peerHex,
                            endpointId = endpointId,
                            details = "requestConnection failed: ${e.message}"
                        )
                    )
                }
            }
        }

        override fun onEndpointLost(endpointId: String) {
            pendingEndpoints.remove(endpointId)
        }
    }

    @VisibleForTesting
    internal val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val peerHex = info.endpointName
            val peerNodeId = try {
                NodeId.fromHex(peerHex)
            } catch (e: Exception) {
                null
            }

            if (peerNodeId == null || peerNodeId == localNodeId) {
                connectionsClient.rejectConnection(endpointId)
                return
            }

            pendingEndpoints[endpointId] = peerNodeId
            logger.log(
                TransportLogEvent(
                    eventType = TransportEventType.CONNECTION_INITIATED,
                    peerIdHex = peerHex,
                    endpointId = endpointId,
                    details = "Auto-accepting incoming connection"
                )
            )

            // Auto-accept connection
            connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { e ->
                    pendingEndpoints.remove(endpointId)
                    logger.log(
                        TransportLogEvent(
                            eventType = TransportEventType.CONNECTION_FAILED,
                            peerIdHex = peerHex,
                            endpointId = endpointId,
                            details = "acceptConnection failed: ${e.message}"
                        )
                    )
                }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            val peerNodeId = pendingEndpoints.remove(endpointId)

            if (resolution.status.statusCode == ConnectionsStatusCodes.STATUS_OK && peerNodeId != null) {
                synchronized(peerToEndpoint) {
                    val existingEndpoint = peerToEndpoint[peerNodeId]
                    if (existingEndpoint != null && existingEndpoint != endpointId) {
                        // Reject duplicate connection between the exact same two devices
                        logger.log(
                            TransportLogEvent(
                                eventType = TransportEventType.CONNECTION_FAILED,
                                peerIdHex = peerNodeId.toHex(),
                                endpointId = endpointId,
                                details = "Dropping duplicate connection for peer $peerNodeId"
                            )
                        )
                        try {
                            connectionsClient.disconnectFromEndpoint(endpointId)
                        } catch (e: Exception) {
                            // Ignored
                        }
                        return
                    }

                    peerToEndpoint[peerNodeId] = endpointId
                    endpointToPeer[endpointId] = peerNodeId
                }

                logger.log(
                    TransportLogEvent(
                        eventType = TransportEventType.PEER_CONNECTED,
                        peerIdHex = peerNodeId.toHex(),
                        endpointId = endpointId,
                        details = "Link established"
                    )
                )

                listeners.forEach { it.onPeerConnected(peerNodeId) }
            } else {
                logger.log(
                    TransportLogEvent(
                        eventType = TransportEventType.CONNECTION_FAILED,
                        peerIdHex = peerNodeId?.toHex(),
                        endpointId = endpointId,
                        details = "Connection resolution error code: ${resolution.status.statusCode}"
                    )
                )
            }
        }

        override fun onDisconnected(endpointId: String) {
            pendingEndpoints.remove(endpointId)
            val peerNodeId = endpointToPeer.remove(endpointId)

            if (peerNodeId != null) {
                val activeEndpoint = peerToEndpoint[peerNodeId]
                if (activeEndpoint == endpointId) {
                    peerToEndpoint.remove(peerNodeId)
                    logger.log(
                        TransportLogEvent(
                            eventType = TransportEventType.PEER_DISCONNECTED,
                            peerIdHex = peerNodeId.toHex(),
                            endpointId = endpointId,
                            details = "Link disconnected"
                        )
                    )
                    listeners.forEach { it.onPeerDisconnected(peerNodeId) }
                }
            }
        }
    }

    @VisibleForTesting
    internal val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) {
                logger.log(
                    TransportLogEvent(
                        eventType = TransportEventType.PACKET_REJECTED_OVERSIZE,
                        endpointId = endpointId,
                        details = "Ignoring non-byte payload type: ${payload.type}"
                    )
                )
                return
            }

            val bytes = payload.asBytes() ?: return
            if (bytes.size > PacketCodec.MAX_PACKET_SIZE) {
                logger.log(
                    TransportLogEvent(
                        eventType = TransportEventType.PACKET_REJECTED_OVERSIZE,
                        endpointId = endpointId,
                        details = "Received packet size ${bytes.size} exceeds maximum ${PacketCodec.MAX_PACKET_SIZE}"
                    )
                )
                return
            }

            val packet = try {
                PacketCodec.decode(bytes)
            } catch (e: Exception) {
                logger.log(
                    TransportLogEvent(
                        eventType = TransportEventType.PACKET_REJECTED_OVERSIZE,
                        endpointId = endpointId,
                        details = "Corrupt packet payload: ${e.message}"
                    )
                )
                return
            }

            val peerNodeId = endpointToPeer[endpointId]
            if (peerNodeId != null) {
                logger.log(
                    TransportLogEvent(
                        eventType = TransportEventType.PACKET_RECEIVED,
                        peerIdHex = peerNodeId.toHex(),
                        endpointId = endpointId,
                        packetSummary = "id=${packet.msgId.toStringUtf8()} type=${packet.type} bytes=${bytes.size}"
                    )
                )
                listeners.forEach { it.onPacketReceived(peerNodeId, packet) }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Transfer status monitoring if needed
        }
    }
}

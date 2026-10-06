package mesh.android.transport

import android.content.Context
import com.google.android.gms.common.api.Status
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
import com.google.android.gms.tasks.Task
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import mesh.protocol.NodeId
import mesh.protocol.Packet
import mesh.protocol.PacketCodec
import mesh.protocol.PacketFactory
import mesh.transport.TransportListener
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class NearbyTransportTest {

    private val context = mockk<Context>(relaxed = true)
    private val connectionsClient = mockk<ConnectionsClient>(relaxed = true)
    private val mockVoidTask = mockk<Task<Void>>()

    private val localNodeId = NodeId.fromHex("0011223344556677")
    private val peerNodeId1 = NodeId.fromHex("8899aabbccddeeff")
    private val peerNodeId2 = NodeId.fromHex("ffeeddccbbaa9988")

    private lateinit var transport: NearbyTransport

    @BeforeEach
    fun setUp() {
        every { mockVoidTask.addOnSuccessListener(any()) } returns mockVoidTask
        every { mockVoidTask.addOnFailureListener(any()) } returns mockVoidTask

        every {
            connectionsClient.startAdvertising(
                any<String>(),
                any<String>(),
                any<ConnectionLifecycleCallback>(),
                any<AdvertisingOptions>()
            )
        } returns mockVoidTask

        every {
            connectionsClient.startDiscovery(
                any<String>(),
                any<EndpointDiscoveryCallback>(),
                any<DiscoveryOptions>()
            )
        } returns mockVoidTask

        every {
            connectionsClient.requestConnection(
                any<String>(),
                any<String>(),
                any<ConnectionLifecycleCallback>()
            )
        } returns mockVoidTask

        every {
            connectionsClient.acceptConnection(
                any<String>(),
                any<PayloadCallback>()
            )
        } returns mockVoidTask

        every { connectionsClient.sendPayload(any<String>(), any<Payload>()) } returns mockVoidTask
        every { connectionsClient.sendPayload(any<List<String>>(), any<Payload>()) } returns mockVoidTask

        transport = NearbyTransport(
            context = context,
            localNodeId = localNodeId,
            connectionsClient = connectionsClient
        )
    }

    @Test
    fun `start initiates advertising and discovery with P2P_CLUSTER`() {
        transport.start()

        assertTrue(transport.isRunning)
        verify {
            connectionsClient.startAdvertising(
                localNodeId.toHex(),
                NearbyTransport.DEFAULT_SERVICE_ID,
                any<ConnectionLifecycleCallback>(),
                any<AdvertisingOptions>()
            )
            connectionsClient.startDiscovery(
                NearbyTransport.DEFAULT_SERVICE_ID,
                any<EndpointDiscoveryCallback>(),
                any<DiscoveryOptions>()
            )
        }
    }

    @Test
    fun `stop halts advertising discovery and endpoints`() {
        transport.start()
        transport.stop()

        assertFalse(transport.isRunning)
        verify {
            connectionsClient.stopAdvertising()
            connectionsClient.stopDiscovery()
            connectionsClient.stopAllEndpoints()
        }
    }

    @Test
    fun `discovery requests connection when peer has higher ID`() {
        transport.start()

        val endpointInfo = mockk<DiscoveredEndpointInfo>()
        every { endpointInfo.endpointName } returns peerNodeId1.toHex()

        transport.endpointDiscoveryCallback.onEndpointFound("endpoint-1", endpointInfo)

        verify {
            connectionsClient.requestConnection(
                localNodeId.toHex(),
                "endpoint-1",
                any()
            )
        }
    }

    @Test
    fun `connection lifecycle notifies listener and tracks peer`() {
        transport.start()
        val listener = mockk<TransportListener>(relaxed = true)
        transport.registerListener(listener)

        val info = mockk<ConnectionInfo>()
        every { info.endpointName } returns peerNodeId1.toHex()

        // 1. Connection Initiated
        transport.connectionLifecycleCallback.onConnectionInitiated("endpoint-1", info)
        verify { connectionsClient.acceptConnection("endpoint-1", any()) }

        // 2. Connection Result OK
        val resolution = ConnectionResolution(Status(ConnectionsStatusCodes.STATUS_OK))
        transport.connectionLifecycleCallback.onConnectionResult("endpoint-1", resolution)

        assertTrue(transport.getConnectedPeers().contains(peerNodeId1))
        verify { listener.onPeerConnected(peerNodeId1) }

        // 3. Disconnect
        transport.connectionLifecycleCallback.onDisconnected("endpoint-1")
        assertFalse(transport.getConnectedPeers().contains(peerNodeId1))
        verify { listener.onPeerDisconnected(peerNodeId1) }
    }

    @Test
    fun `duplicate connection from same peer drops redundant endpoint`() {
        transport.start()
        val listener = mockk<TransportListener>(relaxed = true)
        transport.registerListener(listener)

        val info1 = mockk<ConnectionInfo>()
        every { info1.endpointName } returns peerNodeId1.toHex()
        transport.connectionLifecycleCallback.onConnectionInitiated("endpoint-1", info1)
        transport.connectionLifecycleCallback.onConnectionResult(
            "endpoint-1",
            ConnectionResolution(Status(ConnectionsStatusCodes.STATUS_OK))
        )

        // Attempt second connection from exact same peer
        val info2 = mockk<ConnectionInfo>()
        every { info2.endpointName } returns peerNodeId1.toHex()
        transport.connectionLifecycleCallback.onConnectionInitiated("endpoint-2", info2)
        transport.connectionLifecycleCallback.onConnectionResult(
            "endpoint-2",
            ConnectionResolution(Status(ConnectionsStatusCodes.STATUS_OK))
        )

        // Verifies duplicate was disconnected and listener only notified once
        verify(exactly = 1) { listener.onPeerConnected(peerNodeId1) }
        verify { connectionsClient.disconnectFromEndpoint("endpoint-2") }
    }

    @Test
    fun `send unicast packet succeeds when peer is connected`() {
        transport.start()
        val info = mockk<ConnectionInfo>()
        every { info.endpointName } returns peerNodeId1.toHex()
        transport.connectionLifecycleCallback.onConnectionInitiated("endpoint-1", info)
        transport.connectionLifecycleCallback.onConnectionResult(
            "endpoint-1",
            ConnectionResolution(Status(ConnectionsStatusCodes.STATUS_OK))
        )

        val packet = PacketFactory.createData(
            origin = localNodeId,
            dest = peerNodeId1,
            payload = "Hello Peer".toByteArray()
        )

        val result = transport.send(peerNodeId1, packet)
        assertTrue(result)
        verify { connectionsClient.sendPayload("endpoint-1", any<Payload>()) }
    }

    @Test
    fun `send unicast packet fails when peer is not connected`() {
        transport.start()
        val packet = PacketFactory.createData(
            origin = localNodeId,
            dest = peerNodeId1,
            payload = "Hello".toByteArray()
        )

        val result = transport.send(peerNodeId1, packet)
        assertFalse(result)
    }

    @Test
    fun `broadcast sends packet to all connected peers`() {
        transport.start()

        // Connect peer 1
        val info1 = mockk<ConnectionInfo>()
        every { info1.endpointName } returns peerNodeId1.toHex()
        transport.connectionLifecycleCallback.onConnectionInitiated("ep-1", info1)
        transport.connectionLifecycleCallback.onConnectionResult(
            "ep-1",
            ConnectionResolution(Status(ConnectionsStatusCodes.STATUS_OK))
        )

        // Connect peer 2
        val info2 = mockk<ConnectionInfo>()
        every { info2.endpointName } returns peerNodeId2.toHex()
        transport.connectionLifecycleCallback.onConnectionInitiated("ep-2", info2)
        transport.connectionLifecycleCallback.onConnectionResult(
            "ep-2",
            ConnectionResolution(Status(ConnectionsStatusCodes.STATUS_OK))
        )

        val broadcastPacket = PacketFactory.createData(
            origin = localNodeId,
            dest = null,
            payload = "Broadcast emergency message".toByteArray()
        )

        val peerCount = transport.broadcast(broadcastPacket)
        assertEquals(2, peerCount)
        verify {
            connectionsClient.sendPayload(
                match<List<String>> { it.contains("ep-1") && it.contains("ep-2") },
                any<Payload>()
            )
        }
    }

    @Test
    fun `payload received dispatches valid packet to listener`() {
        transport.start()
        val listener = mockk<TransportListener>(relaxed = true)
        transport.registerListener(listener)

        val info = mockk<ConnectionInfo>()
        every { info.endpointName } returns peerNodeId1.toHex()
        transport.connectionLifecycleCallback.onConnectionInitiated("endpoint-1", info)
        transport.connectionLifecycleCallback.onConnectionResult(
            "endpoint-1",
            ConnectionResolution(Status(ConnectionsStatusCodes.STATUS_OK))
        )

        val incomingPacket = PacketFactory.createData(
            origin = peerNodeId1,
            dest = localNodeId,
            payload = "Test data".toByteArray()
        )
        val packetBytes = PacketCodec.encode(incomingPacket)

        val payload = Payload.fromBytes(packetBytes)
        transport.payloadCallback.onPayloadReceived("endpoint-1", payload)

        verify { listener.onPacketReceived(peerNodeId1, any<Packet>()) }
    }

    @Test
    fun `corrupt incoming payload is dropped without throwing exception`() {
        transport.start()
        val listener = mockk<TransportListener>(relaxed = true)
        transport.registerListener(listener)

        val info = mockk<ConnectionInfo>()
        every { info.endpointName } returns peerNodeId1.toHex()
        transport.connectionLifecycleCallback.onConnectionInitiated("endpoint-1", info)
        transport.connectionLifecycleCallback.onConnectionResult(
            "endpoint-1",
            ConnectionResolution(Status(ConnectionsStatusCodes.STATUS_OK))
        )

        val corruptPayload = Payload.fromBytes(byteArrayOf(0x01, 0x02, 0x03))
        transport.payloadCallback.onPayloadReceived("endpoint-1", corruptPayload)

        verify(exactly = 0) { listener.onPacketReceived(any(), any()) }
    }
}

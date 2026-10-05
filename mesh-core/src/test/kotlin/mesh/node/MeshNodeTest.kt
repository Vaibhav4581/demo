package mesh.node

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import mesh.delivery.DeliveryState
import mesh.fakes.FakeClock
import mesh.fakes.FakeTransport
import mesh.protocol.NodeId
import org.junit.jupiter.api.Test

class MeshNodeTest {

    private val idA = NodeId(byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0))
    private val idB = NodeId(byteArrayOf(2, 0, 0, 0, 0, 0, 0, 0))
    private val idC = NodeId(byteArrayOf(3, 0, 0, 0, 0, 0, 0, 0))

    @Test
    fun `end-to-end multi-hop delivery across chain A-B-C with ACK return`() {
        val clock = FakeClock(1000L)

        // Topology: A <---> B <---> C (A and C cannot communicate directly)
        val transportA = FakeTransport(idA)
        val transportB = FakeTransport(idB)
        val transportC = FakeTransport(idC)

        val nodeA = MeshNode(nodeId = idA, transport = transportA, clock = clock)
        val nodeB = MeshNode(nodeId = idB, transport = transportB, clock = clock)
        val nodeC = MeshNode(nodeId = idC, transport = transportC, clock = clock)

        // Establish links
        transportA.link(transportB)
        transportB.link(transportC)

        // Track deliveries and receptions
        var messageReceivedByC: String? = null
        nodeC.onMessageReceived { from, payload, _ ->
            if (from == idA) {
                messageReceivedByC = String(payload)
            }
        }

        var ackDeliveredToA = false
        nodeA.onDelivered { _, _ ->
            ackDeliveredToA = true
        }

        // 1. Node A sends direct unicast message to Node C
        val messageText = "Emergency SOS from A"
        val packet = nodeA.send(idC, messageText.toByteArray())

        // 2. Verify Node C received the message across the multi-hop path
        assertThat(messageReceivedByC).isEqualTo(messageText)

        // 3. Verify Node C generated an ACK that traversed back to Node A
        assertThat(ackDeliveredToA).isTrue()

        // 4. Verify Node A's delivery state is DELIVERED
        assertThat(nodeA.getDeliveryState(packet.msgId.toByteArray())).isEqualTo(DeliveryState.DELIVERED)

        // 5. Verify distance-vector route tables learned the reverse paths
        // Node C learned route to A via B with cost 2
        val routeToAAtC = nodeC.router.routeTable.getRoute(idA, clock.nowMs())
        assertThat(routeToAAtC).isNotNull()
        assertThat(routeToAAtC!!.nextHop).isEqualTo(idB)
        assertThat(routeToAAtC.cost).isEqualTo(2)

        // Node A learned route to C via B with cost 2 (from the returning ACK)
        val routeToCAtA = nodeA.router.routeTable.getRoute(idC, clock.nowMs())
        assertThat(routeToCAtA).isNotNull()
        assertThat(routeToCAtA!!.nextHop).isEqualTo(idB)
        assertThat(routeToCAtA.cost).isEqualTo(2)
    }

    @Test
    fun `broadcast message reaches all connected nodes in network`() {
        val clock = FakeClock(1000L)
        val transportA = FakeTransport(idA)
        val transportB = FakeTransport(idB)
        val transportC = FakeTransport(idC)

        val nodeA = MeshNode(nodeId = idA, transport = transportA, clock = clock)
        val nodeB = MeshNode(nodeId = idB, transport = transportB, clock = clock)
        val nodeC = MeshNode(nodeId = idC, transport = transportC, clock = clock)

        transportA.link(transportB)
        transportB.link(transportC)

        var bReceivedBroadcast = false
        var cReceivedBroadcast = false

        nodeB.onMessageReceived { from, payload, isBroadcast ->
            if (from == idA && isBroadcast && String(payload) == "Flood Alert") {
                bReceivedBroadcast = true
            }
        }

        nodeC.onMessageReceived { from, payload, isBroadcast ->
            if (from == idA && isBroadcast && String(payload) == "Flood Alert") {
                cReceivedBroadcast = true
            }
        }

        nodeA.broadcast("Flood Alert".toByteArray())

        assertThat(bReceivedBroadcast).isTrue()
        assertThat(cReceivedBroadcast).isTrue()
    }

    @Test
    fun `excessive broadcasts exceeding rate limit are dropped and marked EXPIRED`() {
        val clock = FakeClock(1000L)
        val transportA = FakeTransport(idA)
        val transportB = FakeTransport(idB)

        val config = NodeConfig(
            broadcastRateLimitPerSec = 1.0,
            broadcastBurstLimit = 1.0
        )
        val nodeA = MeshNode(nodeId = idA, transport = transportA, clock = clock, config = config)
        val nodeB = MeshNode(nodeId = idB, transport = transportB, clock = clock)

        var bReceivedCount = 0
        nodeB.onMessageReceived { _, _, isBcast ->
            if (isBcast) bReceivedCount++
        }

        // Establish link after nodes are registered as transport listeners
        transportA.link(transportB)

        // First broadcast is within burst limit
        val p1 = nodeA.broadcast("Alert 1".toByteArray())
        assertThat(nodeA.getDeliveryState(p1.msgId.toByteArray())).isEqualTo(DeliveryState.DELIVERED)
        assertThat(bReceivedCount).isEqualTo(1)

        // Second immediate broadcast exceeds rate limit (burst=1.0 exhausted)
        val p2 = nodeA.broadcast("Alert 2".toByteArray())
        assertThat(nodeA.getDeliveryState(p2.msgId.toByteArray())).isEqualTo(DeliveryState.EXPIRED)
        // Node B should not receive p2
        assertThat(bReceivedCount).isEqualTo(1)
        assertThat(nodeA.router.rateLimitedDrops).isEqualTo(1L)
    }
}

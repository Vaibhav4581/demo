package mesh.protocol

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isTrue
import assertk.assertions.isFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PacketFactoryTest {

    private val nodeA = NodeId(byteArrayOf(1, 1, 1, 1, 1, 1, 1, 1))
    private val nodeB = NodeId(byteArrayOf(2, 2, 2, 2, 2, 2, 2, 2))

    @Test
    fun `createData defaults and fields`() {
        val payload = "Hello Mesh".toByteArray()
        val packet = PacketFactory.createData(
            origin = nodeA,
            dest = nodeB,
            payload = payload
        )

        assertThat(packet.originNodeId).isEqualTo(nodeA)
        assertThat(packet.destNodeId).isEqualTo(nodeB)
        assertThat(packet.isBroadcast).isFalse()
        assertThat(packet.type).isEqualTo(PacketType.DATA)
        assertThat(packet.ttl).isEqualTo(PacketFactory.DEFAULT_TTL)
        assertThat(packet.hopCount).isEqualTo(0)
        assertThat(packet.payload.toByteArray()).isEqualTo(payload)
        assertThat(packet.expiresAtMs - packet.createdAtMs).isEqualTo(PacketFactory.DEFAULT_LIFETIME_MS)
    }

    @Test
    fun `createData broadcast when dest is null`() {
        val packet = PacketFactory.createData(
            origin = nodeA,
            dest = null,
            payload = "Broadcast emergency".toByteArray()
        )

        assertThat(packet.isBroadcast).isTrue()
        assertThat(packet.destNodeId).isEqualTo(null)
    }

    @Test
    fun `createAck embeds original msgId in payload`() {
        val originalMsgId = PacketFactory.generateMsgId()
        val ackPacket = PacketFactory.createAck(
            origin = nodeB,
            dest = nodeA,
            ackedMsgId = originalMsgId
        )

        assertThat(ackPacket.type).isEqualTo(PacketType.ACK)
        assertThat(ackPacket.originNodeId).isEqualTo(nodeB)
        assertThat(ackPacket.destNodeId).isEqualTo(nodeA)
        assertThat(ackPacket.payload.toByteArray()).isEqualTo(originalMsgId)
        assertThat(ackPacket.isAckFor(originalMsgId)).isTrue()
        assertThat(ackPacket.isAckFor(PacketFactory.generateMsgId())).isFalse()
    }

    @Test
    fun `createHello generates broadcast hello packet`() {
        val pubKey = byteArrayOf(9, 9, 9, 9)
        val helloPacket = PacketFactory.createHello(
            origin = nodeA,
            displayName = "Rescue-1",
            publicKey = pubKey
        )

        assertThat(helloPacket.type).isEqualTo(PacketType.HELLO)
        assertThat(helloPacket.isBroadcast).isTrue()
        assertThat(helloPacket.ttl).isEqualTo(1)

        val decoded = HelloPayload.decode(helloPacket.payload.toByteArray())
        assertThat(decoded.displayName).isEqualTo("Rescue-1")
        assertThat(decoded.publicKey).isEqualTo(pubKey)
    }

    @Test
    fun `createSyncSummary creates anti-entropy packet`() {
        val bloomBytes = byteArrayOf(1, 2, 3, 4, 5)
        val syncPacket = PacketFactory.createSyncSummary(
            origin = nodeA,
            dest = nodeB,
            bloomFilterBytes = bloomBytes
        )

        assertThat(syncPacket.type).isEqualTo(PacketType.SYNC_SUMMARY)
        assertThat(syncPacket.originNodeId).isEqualTo(nodeA)
        assertThat(syncPacket.destNodeId).isEqualTo(nodeB)
        assertThat(syncPacket.payload.toByteArray()).isEqualTo(bloomBytes)
    }

    @Test
    fun `withDecrementedTtl decrements ttl and increments hopCount`() {
        val packet = PacketFactory.createData(
            origin = nodeA,
            dest = nodeB,
            payload = byteArrayOf(1),
            ttl = 5,
            hopCount = 2
        )

        val forwarded = packet.withDecrementedTtl()
        assertThat(forwarded.ttl).isEqualTo(4)
        assertThat(forwarded.hopCount).isEqualTo(3)
    }

    @Test
    fun `withDecrementedTtl throws when ttl is 0`() {
        val packet = PacketFactory.createData(
            origin = nodeA,
            dest = nodeB,
            payload = byteArrayOf(1),
            ttl = 0
        )

        assertThrows<IllegalArgumentException> {
            packet.withDecrementedTtl()
        }
    }

    @Test
    fun `isExpired checks timestamp correctly`() {
        val now = 100_000L
        val packet = PacketFactory.createData(
            origin = nodeA,
            dest = nodeB,
            payload = byteArrayOf(1),
            createdAtMs = now,
            expiresAtMs = now + 5000L
        )

        assertThat(packet.isExpired(now + 4000L)).isFalse()
        assertThat(packet.isExpired(now + 5000L)).isTrue()
        assertThat(packet.isExpired(now + 6000L)).isTrue()
    }
}

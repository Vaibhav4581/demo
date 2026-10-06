package mesh.protocol

import assertk.assertThat
import assertk.assertions.isEqualTo
import com.google.protobuf.ByteString
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class PacketCodecTest {

    private val originNode = NodeId(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
    private val destNode = NodeId(byteArrayOf(8, 7, 6, 5, 4, 3, 2, 1))

    @Test
    fun `round-trip encode and decode unicast DATA packet`() {
        val payload = "Important message".toByteArray(Charsets.UTF_8)
        val packet = PacketFactory.createData(
            origin = originNode,
            dest = destNode,
            payload = payload,
            ttl = 6,
            hopCount = 2
        )

        val encoded = PacketCodec.encode(packet)
        val decoded = PacketCodec.decode(encoded)

        assertThat(decoded.msgId).isEqualTo(packet.msgId)
        assertThat(decoded.origin).isEqualTo(packet.origin)
        assertThat(decoded.dest).isEqualTo(packet.dest)
        assertThat(decoded.type).isEqualTo(PacketType.DATA)
        assertThat(decoded.ttl).isEqualTo(6)
        assertThat(decoded.hopCount).isEqualTo(2)
        assertThat(decoded.createdAtMs).isEqualTo(packet.createdAtMs)
        assertThat(decoded.expiresAtMs).isEqualTo(packet.expiresAtMs)
        assertThat(decoded.payload.toByteArray()).isEqualTo(payload)
        assertThat(decoded).isEqualTo(packet)
    }

    @Test
    fun `round-trip encode and decode broadcast packet with empty dest`() {
        val payload = "Broadcast alert".toByteArray(Charsets.UTF_8)
        val packet = PacketFactory.createData(
            origin = originNode,
            dest = null,
            payload = payload
        )

        assertThat(packet.dest.isEmpty).isEqualTo(true)

        val encoded = PacketCodec.encode(packet)
        val decoded = PacketCodec.decode(encoded)

        assertThat(decoded.dest.isEmpty).isEqualTo(true)
        assertThat(decoded.isBroadcast).isEqualTo(true)
        assertThat(decoded.destNodeId).isEqualTo(null)
        assertThat(decoded.payload.toByteArray()).isEqualTo(payload)
        assertThat(decoded).isEqualTo(packet)
    }

    @Test
    fun `round-trip encode and decode ACK packet`() {
        val originalMsgId = PacketFactory.generateMsgId()
        val ack = PacketFactory.createAck(
            origin = originNode,
            dest = destNode,
            ackedMsgId = originalMsgId
        )

        val encoded = PacketCodec.encode(ack)
        val decoded = PacketCodec.decode(encoded)

        assertThat(decoded.type).isEqualTo(PacketType.ACK)
        assertThat(decoded.payload.toByteArray()).isEqualTo(originalMsgId)
        assertThat(decoded).isEqualTo(ack)
    }

    @Test
    fun `round-trip encode and decode HELLO packet`() {
        val hello = PacketFactory.createHello(
            origin = originNode,
            displayName = "Alice Station",
            publicKey = byteArrayOf(11, 22, 33, 44)
        )

        val encoded = PacketCodec.encode(hello)
        val decoded = PacketCodec.decode(encoded)

        assertThat(decoded.type).isEqualTo(PacketType.HELLO)
        val payload = HelloPayload.decode(decoded.payload.toByteArray())
        assertThat(payload.displayName).isEqualTo("Alice Station")
        assertThat(payload.publicKey).isEqualTo(byteArrayOf(11, 22, 33, 44))
        assertThat(decoded).isEqualTo(hello)
    }

    @Test
    fun `round-trip encode and decode SYNC_SUMMARY packet`() {
        val bloomBytes = ByteArray(256) { it.toByte() }
        val sync = PacketFactory.createSyncSummary(
            origin = originNode,
            dest = destNode,
            bloomFilterBytes = bloomBytes
        )

        val encoded = PacketCodec.encode(sync)
        val decoded = PacketCodec.decode(encoded)

        assertThat(decoded.type).isEqualTo(PacketType.SYNC_SUMMARY)
        assertThat(decoded.payload.toByteArray()).isEqualTo(bloomBytes)
        assertThat(decoded).isEqualTo(sync)
    }

    @Test
    fun `round-trip with maximum-size payload`() {
        val maxPayloadSize = PacketCodec.MAX_PAYLOAD_SIZE
        val largePayload = ByteArray(maxPayloadSize) { (it % 255).toByte() }

        val packet = PacketFactory.createData(
            origin = originNode,
            dest = destNode,
            payload = largePayload
        )

        val encoded = PacketCodec.encode(packet)
        assertThat(encoded.size <= PacketCodec.MAX_PACKET_SIZE).isEqualTo(true)

        val decoded = PacketCodec.decode(encoded)
        assertThat(decoded.payload.size()).isEqualTo(maxPayloadSize)
        assertThat(decoded.payload.toByteArray()).isEqualTo(largePayload)
    }

    @Test
    fun `encode rejects packet exceeding maximum packet size`() {
        val oversizePayload = ByteArray(PacketCodec.MAX_PACKET_SIZE + 1)
        val packet = Packet.newBuilder()
            .setMsgId(ByteString.copyFrom(PacketFactory.generateMsgId()))
            .setOrigin(originNode.toByteString())
            .setType(PacketType.DATA)
            .setPayload(ByteString.copyFrom(oversizePayload))
            .build()

        assertThrows<PacketCodecException> {
            PacketCodec.encode(packet)
        }
    }

    @Test
    fun `decode rejects empty byte array`() {
        assertThrows<PacketCodecException> {
            PacketCodec.decode(ByteArray(0))
        }
    }

    @Test
    fun `decode rejects bytes exceeding maximum size`() {
        val oversizeBytes = ByteArray(PacketCodec.MAX_PACKET_SIZE + 1)
        assertThrows<PacketCodecException> {
            PacketCodec.decode(oversizeBytes)
        }
    }

    @Test
    fun `validation rejects invalid msg_id length`() {
        val invalidMsgIdPacket = Packet.newBuilder()
            .setMsgId(ByteString.copyFrom(byteArrayOf(1, 2, 3))) // only 3 bytes
            .setOrigin(originNode.toByteString())
            .setType(PacketType.DATA)
            .build()

        assertThrows<PacketCodecException> {
            PacketCodec.validatePacket(invalidMsgIdPacket)
        }
    }

    @Test
    fun `validation rejects invalid origin length`() {
        val invalidOriginPacket = Packet.newBuilder()
            .setMsgId(ByteString.copyFrom(PacketFactory.generateMsgId()))
            .setOrigin(ByteString.copyFrom(byteArrayOf(1, 2, 3, 4))) // only 4 bytes
            .setType(PacketType.DATA)
            .build()

        assertThrows<PacketCodecException> {
            PacketCodec.validatePacket(invalidOriginPacket)
        }
    }

    @Test
    fun `validation rejects invalid dest length`() {
        val invalidDestPacket = Packet.newBuilder()
            .setMsgId(ByteString.copyFrom(PacketFactory.generateMsgId()))
            .setOrigin(originNode.toByteString())
            .setDest(ByteString.copyFrom(byteArrayOf(1, 2, 3))) // not 0 and not 8 bytes
            .setType(PacketType.DATA)
            .build()

        assertThrows<PacketCodecException> {
            PacketCodec.validatePacket(invalidDestPacket)
        }
    }

    @Test
    fun `validation rejects unspecified packet type`() {
        val invalidTypePacket = Packet.newBuilder()
            .setMsgId(ByteString.copyFrom(PacketFactory.generateMsgId()))
            .setOrigin(originNode.toByteString())
            .setType(PacketType.PACKET_TYPE_UNSPECIFIED)
            .build()

        assertThrows<PacketCodecException> {
            PacketCodec.validatePacket(invalidTypePacket)
        }
    }

    @Test
    fun `validation rejects invalid timestamps`() {
        val invalidTimestampPacket = Packet.newBuilder()
            .setMsgId(ByteString.copyFrom(PacketFactory.generateMsgId()))
            .setOrigin(originNode.toByteString())
            .setType(PacketType.DATA)
            .setCreatedAtMs(1000L)
            .setExpiresAtMs(999L)
            .build()

        assertThrows<PacketCodecException> {
            PacketCodec.validatePacket(invalidTimestampPacket)
        }
    }
}

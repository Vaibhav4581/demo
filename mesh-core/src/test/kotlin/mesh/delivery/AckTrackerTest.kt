package mesh.delivery

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import mesh.protocol.NodeId
import mesh.protocol.PacketFactory
import org.junit.jupiter.api.Test

class AckTrackerTest {

    private val origin = NodeId(byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0))
    private val dest = NodeId(byteArrayOf(2, 0, 0, 0, 0, 0, 0, 0))

    @Test
    fun `matching ACK invokes callback and clears tracker`() {
        val tracker = AckTracker()
        val packet = PacketFactory.createData(origin = origin, dest = dest, payload = "Test".toByteArray())

        var delivered = false
        tracker.track(packet, onDelivered = { delivered = true })

        assertThat(tracker.isAwaitingAck(packet.msgId.toByteArray())).isTrue()

        // Arriving ACK for this packet
        val ack = PacketFactory.createAck(origin = dest, dest = origin, ackedMsgId = packet.msgId.toByteArray())
        val matched = tracker.handleAck(ack)

        assertThat(matched).isTrue()
        assertThat(delivered).isTrue()
        assertThat(tracker.isAwaitingAck(packet.msgId.toByteArray())).isFalse()
    }

    @Test
    fun `purgeExpired removes expired messages and invokes onExpired`() {
        val tracker = AckTracker()
        val packet = PacketFactory.createData(
            origin = origin,
            dest = dest,
            payload = "Test".toByteArray(),
            createdAtMs = 1000L,
            expiresAtMs = 5000L
        )

        var expired = false
        tracker.track(packet, onExpired = { expired = true })

        assertThat(tracker.purgeExpired(4000L)).isEqualTo(0)
        assertThat(expired).isFalse()

        assertThat(tracker.purgeExpired(5000L)).isEqualTo(1)
        assertThat(expired).isTrue()
        assertThat(tracker.isAwaitingAck(packet.msgId.toByteArray())).isFalse()
    }
}

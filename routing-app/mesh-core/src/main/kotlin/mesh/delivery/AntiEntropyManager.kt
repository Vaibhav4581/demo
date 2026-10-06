package mesh.delivery

import mesh.dedup.BloomFilter
import mesh.protocol.NodeId
import mesh.protocol.Packet
import mesh.protocol.PacketFactory
import mesh.protocol.isExpired
import mesh.storage.MessageStore
import mesh.transport.Clock
import mesh.transport.Transport

/**
 * Manages anti-entropy synchronization across network partitions.
 *
 * Exchanging compact Bloom filters on connection discovery enables peers to detect
 * and synchronize missing stored messages.
 */
class AntiEntropyManager(
    val localNodeId: NodeId,
    val transport: Transport,
    val messageStore: MessageStore,
    val clock: Clock
) {
    /**
     * Triggered when a new direct peer connects.
     * Generates a Bloom filter summary of currently held messages and sends a SYNC_SUMMARY packet.
     */
    fun onPeerConnected(peer: NodeId): Packet? {
        val heldIds = messageStore.getHeldMessageIds()
        val bloom = BloomFilter(
            expectedInsertions = heldIds.size.coerceAtLeast(BloomFilter.DEFAULT_EXPECTED_INSERTIONS),
            fpp = 0.01
        )
        for (id in heldIds) {
            bloom.insert(id)
        }

        val syncPacket = PacketFactory.createSyncSummary(
            origin = localNodeId,
            dest = peer,
            bloomFilterBytes = bloom.toByteArray(),
            createdAtMs = clock.nowMs()
        )
        transport.send(peer, syncPacket)
        return syncPacket
    }

    /**
     * Processes a received SYNC_SUMMARY packet from a peer.
     * Identifies stored messages missing from the peer's Bloom filter and transmits them.
     *
     * @return count of missing messages synchronized to the peer
     */
    fun onSyncSummaryReceived(fromPeer: NodeId, syncPacket: Packet): Int {
        val peerBloom = try {
            BloomFilter.fromByteArray(syncPacket.payload.toByteArray())
        } catch (_: Exception) {
            return 0
        }

        val nowMs = clock.nowMs()
        var synchronizedCount = 0

        // Synchronize pending outbox packets first
        val outboxPackets = messageStore.getOutboxPackets()
        for (packet in outboxPackets) {
            if (packet.isExpired(nowMs)) continue
            val id = packet.msgId.toByteArray()
            if (!peerBloom.contains(id)) {
                if (transport.send(fromPeer, packet)) {
                    synchronizedCount++
                }
            }
        }

        // Also check all saved messages
        val savedMessages = messageStore.getAllMessages()
        for (record in savedMessages) {
            if (nowMs >= record.expiresAtMs) continue
            if (!peerBloom.contains(record.msgId)) {
                // If not already sent in outbox check
                if (outboxPackets.none { it.msgId.toByteArray().contentEquals(record.msgId) }) {
                    val dataPacket = PacketFactory.createData(
                        origin = record.origin,
                        dest = record.dest,
                        payload = record.payload,
                        msgId = record.msgId,
                        createdAtMs = record.createdAtMs,
                        expiresAtMs = record.expiresAtMs
                    )
                    if (transport.send(fromPeer, dataPacket)) {
                        synchronizedCount++
                    }
                }
            }
        }

        return synchronizedCount
    }
}

package mesh.protocol

import com.google.protobuf.ByteString

/**
 * Returns true if this packet is a broadcast packet (destination is empty).
 */
val Packet.isBroadcast: Boolean
    get() = dest.isEmpty

/**
 * The [NodeId] of the original sender.
 */
val Packet.originNodeId: NodeId
    get() = NodeId(origin.toByteArray())

/**
 * The [NodeId] of the intended destination, or null if broadcast.
 */
val Packet.destNodeId: NodeId?
    get() = NodeId.fromBytesOrNull(dest.toByteArray())

/**
 * Hexadecimal string representation of the 16-byte message ID.
 */
val Packet.msgIdHex: String
    get() = msgId.toByteArray().joinToString("") { "%02x".format(it) }

/**
 * Returns a new [Packet] with TTL decremented by 1 and hopCount incremented by 1.
 */
fun Packet.withDecrementedTtl(): Packet {
    require(ttl > 0) { "Cannot decrement TTL from $ttl" }
    return toBuilder()
        .setTtl(ttl - 1)
        .setHopCount(hopCount + 1)
        .build()
}

/**
 * Returns true if the message has expired relative to [nowMs].
 */
fun Packet.isExpired(nowMs: Long): Boolean {
    return nowMs >= expiresAtMs
}

/**
 * Returns true if this packet is an ACK acknowledging [targetMsgId].
 */
fun Packet.isAckFor(targetMsgId: ByteArray): Boolean {
    return type == PacketType.ACK && payload.toByteArray().contentEquals(targetMsgId)
}

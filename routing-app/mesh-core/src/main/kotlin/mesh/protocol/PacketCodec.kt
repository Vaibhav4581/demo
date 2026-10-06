package mesh.protocol

import com.google.protobuf.InvalidProtocolBufferException

/**
 * Exception thrown when packet encoding or decoding fails.
 */
class PacketCodecException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * Encodes and decodes wire-format [Packet] instances with boundary and structural validation.
 */
object PacketCodec {

    /**
     * Maximum allowed size for a serialized packet on the wire (64 KiB).
     */
    const val MAX_PACKET_SIZE = 64 * 1024

    /**
     * Maximum allowed payload size inside a packet.
     */
    const val MAX_PAYLOAD_SIZE = MAX_PACKET_SIZE - 256

    /**
     * Serializes a [Packet] to its Protobuf wire format bytes.
     *
     * @throws PacketCodecException if the serialized packet exceeds [MAX_PACKET_SIZE] or fails validation.
     */
    fun encode(packet: Packet): ByteArray {
        validatePacket(packet)
        val bytes = packet.toByteArray()
        if (bytes.size > MAX_PACKET_SIZE) {
            throw PacketCodecException(
                "Encoded packet size ${bytes.size} bytes exceeds maximum allowed limit of $MAX_PACKET_SIZE bytes"
            )
        }
        return bytes
    }

    /**
     * Deserializes raw wire bytes into a validated [Packet].
     *
     * @throws PacketCodecException if bytes are invalid or fail structural integrity checks.
     */
    fun decode(bytes: ByteArray): Packet {
        if (bytes.isEmpty()) {
            throw PacketCodecException("Cannot decode empty byte array")
        }
        if (bytes.size > MAX_PACKET_SIZE) {
            throw PacketCodecException(
                "Packet byte array size ${bytes.size} bytes exceeds maximum allowed limit of $MAX_PACKET_SIZE bytes"
            )
        }
        val packet = try {
            Packet.parseFrom(bytes)
        } catch (e: InvalidProtocolBufferException) {
            throw PacketCodecException("Corrupted protobuf payload", e)
        }
        validatePacket(packet)
        return packet
    }

    /**
     * Validates protocol fields and constraints according to the specification.
     */
    fun validatePacket(packet: Packet) {
        if (packet.msgId.size() != PacketFactory.MSG_ID_SIZE_BYTES) {
            throw PacketCodecException(
                "msg_id must be exactly ${PacketFactory.MSG_ID_SIZE_BYTES} bytes, but was ${packet.msgId.size()} bytes"
            )
        }
        if (packet.origin.size() != NodeId.SIZE_BYTES) {
            throw PacketCodecException(
                "origin must be exactly ${NodeId.SIZE_BYTES} bytes, but was ${packet.origin.size()} bytes"
            )
        }
        val destSize = packet.dest.size()
        if (destSize != 0 && destSize != NodeId.SIZE_BYTES) {
            throw PacketCodecException(
                "dest must be either 0 bytes (broadcast) or ${NodeId.SIZE_BYTES} bytes (unicast), but was $destSize bytes"
            )
        }
        if (packet.type == PacketType.PACKET_TYPE_UNSPECIFIED || packet.type == PacketType.UNRECOGNIZED) {
            throw PacketCodecException("Packet type must not be unspecified or unrecognized")
        }
        if (packet.expiresAtMs < packet.createdAtMs) {
            throw PacketCodecException(
                "expires_at_ms (${packet.expiresAtMs}) cannot be earlier than created_at_ms (${packet.createdAtMs})"
            )
        }
        if (packet.payload.size() > MAX_PAYLOAD_SIZE) {
            throw PacketCodecException(
                "payload size ${packet.payload.size()} bytes exceeds maximum allowed payload of $MAX_PAYLOAD_SIZE bytes"
            )
        }
    }
}

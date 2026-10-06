package mesh.protocol

import com.google.protobuf.ByteString
import java.security.MessageDigest

/**
 * An 8-byte identifier representing a node on the mesh network.
 *
 * Typically derived from the first 8 bytes of the SHA-256 hash of a node's public key.
 */
class NodeId(val bytes: ByteArray) : Comparable<NodeId> {

    init {
        require(bytes.size == SIZE_BYTES) {
            "NodeId must be exactly $SIZE_BYTES bytes, but was ${bytes.size} bytes"
        }
    }

    val rawBytes: ByteArray
        get() = bytes.clone()

    fun toByteString(): ByteString = ByteString.copyFrom(bytes)

    fun toHex(): String = bytes.joinToString("") { "%02x".format(it) }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NodeId) return false
        return bytes.contentEquals(other.bytes)
    }

    override fun hashCode(): Int = bytes.contentHashCode()

    override fun toString(): String = toHex()

    override fun compareTo(other: NodeId): Int {
        for (i in 0 until SIZE_BYTES) {
            val a = bytes[i].toInt() and 0xFF
            val b = other.bytes[i].toInt() and 0xFF
            if (a != b) return a.compareTo(b)
        }
        return 0
    }

    companion object {
        const val SIZE_BYTES = 8

        /**
         * Derives an 8-byte [NodeId] from a public key by taking the first 8 bytes of its SHA-256 digest.
         */
        fun fromPublicKey(publicKey: ByteArray): NodeId {
            require(publicKey.isNotEmpty()) { "Public key cannot be empty" }
            val digest = MessageDigest.getInstance("SHA-256").digest(publicKey)
            return NodeId(digest.copyOf(SIZE_BYTES))
        }

        /**
         * Parses a 16-character hexadecimal string into a [NodeId].
         */
        fun fromHex(hex: String): NodeId {
            val cleanHex = hex.trim()
            require(cleanHex.length == SIZE_BYTES * 2) {
                "Hex string must be ${SIZE_BYTES * 2} characters long, but was ${cleanHex.length}"
            }
            val bytes = ByteArray(SIZE_BYTES)
            for (i in 0 until SIZE_BYTES) {
                val index = i * 2
                val byteVal = cleanHex.substring(index, index + 2).toIntOrNull(16)
                    ?: throw IllegalArgumentException("Invalid hex character in string: $hex")
                bytes[i] = byteVal.toByte()
            }
            return NodeId(bytes)
        }

        fun fromByteString(byteString: ByteString): NodeId {
            return NodeId(byteString.toByteArray())
        }

        fun fromBytes(bytes: ByteArray): NodeId {
            return NodeId(bytes.clone())
        }

        /**
         * Returns a [NodeId] if the byte array is exactly 8 bytes, null if empty (broadcast),
         * or throws [IllegalArgumentException] if of invalid non-zero length.
         */
        fun fromBytesOrNull(bytes: ByteArray): NodeId? {
            return when {
                bytes.isEmpty() -> null
                bytes.size == SIZE_BYTES -> NodeId(bytes)
                else -> throw IllegalArgumentException("NodeId must be 0 or 8 bytes, got ${bytes.size}")
            }
        }
    }
}

package mesh.protocol

import java.nio.ByteBuffer

/**
 * Payload data for a HELLO packet containing a peer's display name and public key.
 */
data class HelloPayload(
    val displayName: String,
    val publicKey: ByteArray
) {
    fun encode(): ByteArray {
        val nameBytes = displayName.toByteArray(Charsets.UTF_8)
        require(nameBytes.size <= 0xFFFF) { "Display name too long" }
        val buffer = ByteBuffer.allocate(2 + nameBytes.size + publicKey.size)
        buffer.putShort(nameBytes.size.toShort())
        buffer.put(nameBytes)
        buffer.put(publicKey)
        return buffer.array()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HelloPayload) return false
        if (displayName != other.displayName) return false
        return publicKey.contentEquals(other.publicKey)
    }

    override fun hashCode(): Int {
        var result = displayName.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        return result
    }

    companion object {
        fun decode(bytes: ByteArray): HelloPayload {
            require(bytes.size >= 2) { "Hello payload too short: ${bytes.size} bytes" }
            val buffer = ByteBuffer.wrap(bytes)
            val nameLength = buffer.short.toInt() and 0xFFFF
            require(bytes.size >= 2 + nameLength) {
                "Hello payload truncated: expected $nameLength name bytes, had ${bytes.size - 2}"
            }
            val nameBytes = ByteArray(nameLength)
            buffer.get(nameBytes)
            val displayName = String(nameBytes, Charsets.UTF_8)
            val pubKeyBytes = ByteArray(buffer.remaining())
            buffer.get(pubKeyBytes)
            return HelloPayload(displayName, pubKeyBytes)
        }
    }
}

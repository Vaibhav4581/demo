package mesh.node

/**
 * Operational parameters and intervals for a [MeshNode].
 */
data class NodeConfig(
    val displayName: String = "MeshNode",
    val publicKey: ByteArray = ByteArray(32),
    val helloIntervalMs: Long = DEFAULT_HELLO_INTERVAL_MS,
    val helloMissedLimit: Int = 3,
    val routeTtlMs: Long = DEFAULT_ROUTE_TTL_MS,
    val defaultTtl: Int = 8,
    val messageLifetimeMs: Long = DEFAULT_MESSAGE_LIFETIME_MS
) {
    companion object {
        const val DEFAULT_HELLO_INTERVAL_MS = 10_000L     // 10 seconds
        const val DEFAULT_ROUTE_TTL_MS = 30 * 60 * 1000L  // 30 minutes
        const val DEFAULT_MESSAGE_LIFETIME_MS = 24 * 60 * 60 * 1000L // 24 hours
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NodeConfig) return false
        if (displayName != other.displayName) return false
        if (!publicKey.contentEquals(other.publicKey)) return false
        if (helloIntervalMs != other.helloIntervalMs) return false
        if (helloMissedLimit != other.helloMissedLimit) return false
        if (routeTtlMs != other.routeTtlMs) return false
        if (defaultTtl != other.defaultTtl) return false
        return messageLifetimeMs == other.messageLifetimeMs
    }

    override fun hashCode(): Int {
        var result = displayName.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + helloIntervalMs.hashCode()
        result = 31 * result + helloMissedLimit
        result = 31 * result + routeTtlMs.hashCode()
        result = 31 * result + defaultTtl
        result = 31 * result + messageLifetimeMs.hashCode()
        return result
    }
}

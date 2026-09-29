package mesh.delivery

/**
 * Lifecycle delivery state of a message on the mesh network.
 */
enum class DeliveryState {
    /** Stored locally in the outbox awaiting a connection or next retry attempt. */
    QUEUED,

    /** Transmitted to at least one immediate neighbour. */
    SENT,

    /** Forwarded by an intermediate node toward the destination. */
    RELAYED,

    /** Delivery confirmed by the recipient via an ACK packet. */
    DELIVERED,

    /** Packet time-to-live expired or message lifetime elapsed before delivery confirmation. */
    EXPIRED
}

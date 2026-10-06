package mesh.crypto

import mesh.protocol.NodeId

/**
 * Stores and looks up the X25519 public-key bytes (raw 32 bytes) for known peer [NodeId]s.
 *
 * Public keys are distributed via HELLO packets and must be registered here before
 * [X25519Crypto] can encrypt a unicast DATA payload destined for that peer.
 */
interface KeyStore {
    /**
     * Registers or refreshes the public key of a known peer.
     *
     * @param nodeId  the peer's mesh node ID
     * @param publicKeyBytes the raw 32-byte X25519 public key bytes from the peer's HELLO packet
     */
    fun registerPeerKey(nodeId: NodeId, publicKeyBytes: ByteArray)

    /**
     * Returns the raw public-key bytes for [nodeId], or null if not yet known.
     */
    fun getPeerKeyBytes(nodeId: NodeId): ByteArray?

    /**
     * Removes the entry for [nodeId] (e.g. when a peer disconnects permanently).
     */
    fun removePeerKey(nodeId: NodeId)

    /**
     * Returns all currently registered [NodeId]s whose public keys are known.
     */
    fun knownPeers(): Set<NodeId>
}

package mesh.crypto

import mesh.protocol.NodeId
import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe in-memory [KeyStore] implementation for unit tests and the JVM simulator.
 *
 * On Android, this should be replaced (or backed) by a persistent, encrypted store so that
 * known peer public keys survive process restarts without requiring re-exchange.
 */
class InMemoryKeyStore : KeyStore {

    private val keys = ConcurrentHashMap<NodeId, ByteArray>()

    override fun registerPeerKey(nodeId: NodeId, publicKeyBytes: ByteArray) {
        keys[nodeId] = publicKeyBytes.clone()
    }

    override fun getPeerKeyBytes(nodeId: NodeId): ByteArray? =
        keys[nodeId]?.clone()

    override fun removePeerKey(nodeId: NodeId) {
        keys.remove(nodeId)
    }

    override fun knownPeers(): Set<NodeId> = keys.keys.toSet()
}

package mesh.android.storage

import mesh.android.storage.dao.NodeDao
import mesh.android.storage.entities.NodeEntity
import mesh.crypto.KeyStore
import mesh.protocol.NodeId

/**
 * Implementation of [KeyStore] backed by the Room encrypted `nodes` table.
 *
 * Automatically persists peer public keys discovered from HELLO packets across
 * process restarts.
 */
class RoomKeyStore(
    private val nodeDao: NodeDao
) : KeyStore {

    constructor(database: MeshDatabase) : this(database.nodeDao())

    override fun registerPeerKey(nodeId: NodeId, publicKeyBytes: ByteArray) {
        val existing = nodeDao.getNode(nodeId.rawBytes)
        val displayName = existing?.displayName ?: "Peer-${nodeId.toHex().takeLast(4)}"
        nodeDao.upsertNode(
            NodeEntity(
                nodeId = nodeId.rawBytes,
                displayName = displayName,
                publicKey = publicKeyBytes,
                lastSeenMs = System.currentTimeMillis()
            )
        )
    }

    override fun getPeerKeyBytes(nodeId: NodeId): ByteArray? {
        return nodeDao.getNode(nodeId.rawBytes)?.publicKey
    }

    override fun removePeerKey(nodeId: NodeId) {
        nodeDao.deleteNode(nodeId.rawBytes)
    }

    override fun knownPeers(): Set<NodeId> {
        return nodeDao.getAllNodes().map { NodeId(it.nodeId) }.toSet()
    }
}

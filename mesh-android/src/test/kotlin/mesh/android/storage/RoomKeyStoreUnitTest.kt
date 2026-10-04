package mesh.android.storage

import assertk.assertThat
import assertk.assertions.contains
import assertk.assertions.isEqualTo
import assertk.assertions.isNull
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import mesh.android.storage.dao.NodeDao
import mesh.android.storage.entities.NodeEntity
import mesh.protocol.NodeId
import org.junit.jupiter.api.Test

class RoomKeyStoreUnitTest {

    private val nodeDao = mockk<NodeDao>(relaxed = true)
    private val keyStore = RoomKeyStore(nodeDao)

    private val testNodeId = NodeId(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8))
    private val testPubKey = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun `registerPeerKey upserts into nodeDao`() {
        keyStore.registerPeerKey(testNodeId, testPubKey)

        verify {
            nodeDao.upsertNode(
                match {
                    it.nodeId.contentEquals(testNodeId.rawBytes) &&
                            it.publicKey.contentEquals(testPubKey)
                }
            )
        }
    }

    @Test
    fun `getPeerKeyBytes retrieves public key from nodeDao`() {
        val entity = NodeEntity(
            nodeId = testNodeId.rawBytes,
            displayName = "Peer-5678",
            publicKey = testPubKey,
            lastSeenMs = 123456L
        )
        every { nodeDao.getNode(testNodeId.rawBytes) } returns entity

        val key = keyStore.getPeerKeyBytes(testNodeId)
        assertThat(key).isEqualTo(testPubKey)
    }

    @Test
    fun `getPeerKeyBytes returns null when peer not found`() {
        every { nodeDao.getNode(testNodeId.rawBytes) } returns null

        val key = keyStore.getPeerKeyBytes(testNodeId)
        assertThat(key).isNull()
    }

    @Test
    fun `removePeerKey deletes node from nodeDao`() {
        keyStore.removePeerKey(testNodeId)
        verify { nodeDao.deleteNode(testNodeId.rawBytes) }
    }

    @Test
    fun `knownPeers returns all node IDs in nodeDao`() {
        val entity = NodeEntity(
            nodeId = testNodeId.rawBytes,
            displayName = "Peer-1",
            publicKey = testPubKey,
            lastSeenMs = 123456L
        )
        every { nodeDao.getAllNodes() } returns listOf(entity)

        val peers = keyStore.knownPeers()
        assertThat(peers).contains(testNodeId)
    }
}

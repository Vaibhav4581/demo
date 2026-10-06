package mesh.android.identity

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import mesh.protocol.NodeId
import org.junit.jupiter.api.Test

class NodeIdentityManagerTest {

    @Test
    fun `NodeId is derived deterministically from public key`() {
        val fakePubKey = ByteArray(32) { (it + 1).toByte() }
        val fakePrivKey = ByteArray(32) { (it + 33).toByte() }
        val expectedNodeId = NodeId.fromPublicKey(fakePubKey)

        val keystore = mockk<KeystoreManager>(relaxed = true)
        every { keystore.getBytes("node_identity_public_key") } returns fakePubKey
        every { keystore.getBytes("node_identity_private_key") } returns fakePrivKey

        val identityManager = NodeIdentityManager(keystore)

        assertThat(identityManager.nodeId).isEqualTo(expectedNodeId)
        assertThat(identityManager.publicKey).isEqualTo(fakePubKey)
    }

    @Test
    fun `new keypair is generated and saved if not present in keystore`() {
        val keystore = mockk<KeystoreManager>(relaxed = true)
        val savedKeys = mutableMapOf<String, ByteArray>()

        every { keystore.getBytes(any()) } answers { savedKeys[firstArg()] }
        every { keystore.putBytes(any(), any()) } answers {
            savedKeys[firstArg()] = secondArg()
        }

        val identityManager = NodeIdentityManager(keystore)

        assertThat(identityManager.nodeId).isNotNull()
        assertThat(identityManager.publicKey.isNotEmpty()).isTrue()
        assertThat(identityManager.privateKey.isNotEmpty()).isTrue()
        assertThat(savedKeys.containsKey("node_identity_public_key")).isTrue()
        assertThat(savedKeys.containsKey("node_identity_private_key")).isTrue()
    }

    @Test
    fun `display name can be updated and retrieved`() {
        val keystore = mockk<KeystoreManager>(relaxed = true)
        var storedName: String? = null

        every { keystore.getString("node_display_name") } answers { storedName }
        every { keystore.putString("node_display_name", any()) } answers {
            storedName = secondArg()
        }

        val identityManager = NodeIdentityManager(keystore)
        identityManager.displayName = "Emergency-Alpha-1"

        assertThat(identityManager.displayName).isEqualTo("Emergency-Alpha-1")
        assertThat(storedName).isEqualTo("Emergency-Alpha-1")
    }
}

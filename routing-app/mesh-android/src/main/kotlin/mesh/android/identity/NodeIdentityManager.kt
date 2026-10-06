package mesh.android.identity

import android.content.Context
import mesh.crypto.MeshKeyPair
import mesh.protocol.NodeId
import java.security.KeyPairGenerator
import java.security.SecureRandom

/**
 * Manages local node identity, cryptographic keys, and user display name.
 */
class NodeIdentityManager(
    private val keystoreManager: KeystoreManager
) {
    constructor(context: Context) : this(KeystoreManager(context))

    val nodeId: NodeId
    val publicKey: ByteArray
    val privateKey: ByteArray

    var displayName: String
        get() = keystoreManager.getString(KEY_DISPLAY_NAME) ?: "User-${nodeId.toHex().takeLast(4)}"
        set(value) {
            keystoreManager.putString(KEY_DISPLAY_NAME, value)
        }

    init {
        var pub = keystoreManager.getBytes(KEY_PUBLIC_KEY)
        var priv = keystoreManager.getBytes(KEY_PRIVATE_KEY)

        if (pub == null || pub.size != MeshKeyPair.KEY_SIZE_BYTES || priv == null || priv.size != MeshKeyPair.KEY_SIZE_BYTES) {
            val kp = MeshKeyPair.generate()
            pub = kp.publicKeyBytes
            priv = kp.privateKeyBytes
            keystoreManager.putBytes(KEY_PUBLIC_KEY, pub)
            keystoreManager.putBytes(KEY_PRIVATE_KEY, priv)
        }

        publicKey = pub
        privateKey = priv
        nodeId = NodeId.fromPublicKey(publicKey)
    }

    companion object {
        private const val KEY_PUBLIC_KEY = "node_identity_public_key"
        private const val KEY_PRIVATE_KEY = "node_identity_private_key"
        private const val KEY_DISPLAY_NAME = "node_display_name"
    }
}

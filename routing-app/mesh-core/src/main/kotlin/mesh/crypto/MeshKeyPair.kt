package mesh.crypto

import mesh.protocol.NodeId
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom

/**
 * An X25519 key pair used for end-to-end encryption of unicast DATA packet payloads.
 *
 * ### Key representation
 * - **Private key:** 32-byte raw X25519 private key.
 * - **Public key:** 32-byte raw X25519 public key (RFC 7748).
 *   This is transmitted in HELLO packets and used to derive the 8-byte [NodeId].
 */
class MeshKeyPair(
    val privateKeyBytes: ByteArray,
    val publicKeyBytes: ByteArray
) {
    init {
        require(privateKeyBytes.size == KEY_SIZE_BYTES) {
            "Private key must be $KEY_SIZE_BYTES bytes, but was ${privateKeyBytes.size}"
        }
        require(publicKeyBytes.size == KEY_SIZE_BYTES) {
            "Public key must be $KEY_SIZE_BYTES bytes, but was ${publicKeyBytes.size}"
        }
    }

    /**
     * Backward-compatible alias for the public key bytes.
     */
    val publicKeysetBytes: ByteArray get() = publicKeyBytes

    /**
     * The [NodeId] derived from SHA-256(publicKeyBytes)[0..7].
     */
    val nodeId: NodeId by lazy {
        NodeId.fromPublicKey(publicKeyBytes)
    }

    /**
     * Serialises the private key to 32 raw bytes for persistent storage.
     * On Android, this should be stored securely in the Keystore or EncryptedSharedPreferences.
     */
    fun serialisePrivate(): ByteArray = privateKeyBytes.clone()

    companion object {
        const val KEY_SIZE_BYTES = 32

        private val secureRandom = SecureRandom()

        /**
         * Generates a fresh random X25519 key pair.
         */
        fun generate(): MeshKeyPair {
            val privateKey = X25519PrivateKeyParameters(secureRandom)
            val publicKey = privateKey.generatePublicKey()
            return MeshKeyPair(privateKey.encoded, publicKey.encoded)
        }

        /**
         * Reconstructs a [MeshKeyPair] from raw 32-byte private key bytes.
         */
        fun fromPrivateKey(privateKeyBytes: ByteArray): MeshKeyPair {
            require(privateKeyBytes.size == KEY_SIZE_BYTES) {
                "Invalid private key size: ${privateKeyBytes.size}"
            }
            val privateKey = X25519PrivateKeyParameters(privateKeyBytes, 0)
            val publicKey = privateKey.generatePublicKey()
            return MeshKeyPair(privateKey.encoded, publicKey.encoded)
        }

        /**
         * Backward-compatible loader from serialised bytes.
         */
        fun fromSerialisedKeyset(serialised: ByteArray): MeshKeyPair = fromPrivateKey(serialised)
    }
}

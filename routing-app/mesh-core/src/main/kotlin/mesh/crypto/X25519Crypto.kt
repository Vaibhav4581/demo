package mesh.crypto

import mesh.protocol.NodeId
import mesh.transport.Crypto
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import java.security.SecureRandom

/**
 * End-to-end encryption implementation using **X25519** key agreement, **HKDF-SHA256**,
 * and **ChaCha20-Poly1305** authenticated encryption (RFC 7748 / RFC 8439).
 *
 * ### Security model
 * - **Confidentiality:** DATA payloads are encrypted to the recipient's X25519 public key.
 *   An intermediary relay node without the recipient's private key cannot recover plaintext.
 * - **Integrity / Authenticity:** ChaCha20-Poly1305 provides authenticated encryption with a 16-byte
 *   MAC tag; any tampering with the ciphertext causes decryption to fail.
 * - **Forward-Secrecy:** Generates a fresh ephemeral sender key pair for each message.
 * - **Wire format:** `[32 bytes ephemeral public key] + [ChaCha20-Poly1305 ciphertext + 16-byte tag]`
 *
 * Broadcast DATA packets are NOT encrypted — there is no single recipient key.
 *
 * @param localKeyPair this node's long-term X25519 key pair
 * @param keyStore peer public key registry populated from HELLO packets
 */
class X25519Crypto(
    private val localKeyPair: MeshKeyPair,
    private val keyStore: KeyStore
) : Crypto {

    companion object {
        /**
         * Context info string bound into the HKDF label of every ciphertext.
         */
        private val CONTEXT_INFO: ByteArray = "mesh-v1-e2e".toByteArray(Charsets.UTF_8)
        private const val SYMMETRIC_KEY_SIZE = 32
        private const val NONCE_SIZE = 12
        private const val MAC_SIZE_BITS = 128
        private const val EPHEMERAL_PUBKEY_SIZE = 32

        private val secureRandom = SecureRandom()
    }

    override fun encrypt(dest: NodeId, plaintext: ByteArray): ByteArray {
        val recipientPubKeyBytes = keyStore.getPeerKeyBytes(dest)
            ?: throw CryptoException(
                "Cannot encrypt for ${dest.toHex()}: public key not registered in KeyStore. " +
                "The node must have sent a HELLO packet first."
            )

        if (recipientPubKeyBytes.size != MeshKeyPair.KEY_SIZE_BYTES) {
            throw CryptoException("Invalid recipient public key size: ${recipientPubKeyBytes.size}")
        }

        return try {
            // 1. Generate ephemeral X25519 key pair
            val ephPriv = X25519PrivateKeyParameters(secureRandom)
            val ephPub = ephPriv.generatePublicKey()
            val ephPubBytes = ephPub.encoded

            // 2. Diffie-Hellman agreement with recipient's public key
            val recipientPubKey = X25519PublicKeyParameters(recipientPubKeyBytes, 0)
            val agreement = X25519Agreement()
            agreement.init(ephPriv)
            val sharedSecret = ByteArray(agreement.agreementSize)
            agreement.calculateAgreement(recipientPubKey, sharedSecret, 0)

            // 3. Derive symmetric key (32 bytes) and nonce (12 bytes) using HKDF-SHA256
            val hkdf = HKDFBytesGenerator(SHA256Digest())
            hkdf.init(HKDFParameters(sharedSecret, null, CONTEXT_INFO))
            val derived = ByteArray(SYMMETRIC_KEY_SIZE + NONCE_SIZE)
            hkdf.generateBytes(derived, 0, derived.size)
            val key = derived.copyOfRange(0, SYMMETRIC_KEY_SIZE)
            val nonce = derived.copyOfRange(SYMMETRIC_KEY_SIZE, derived.size)

            // 4. Encrypt with ChaCha20-Poly1305
            val cipher = ChaCha20Poly1305()
            cipher.init(true, AEADParameters(KeyParameter(key), MAC_SIZE_BITS, nonce, CONTEXT_INFO))
            val cipherOutput = ByteArray(cipher.getOutputSize(plaintext.size))
            val len = cipher.processBytes(plaintext, 0, plaintext.size, cipherOutput, 0)
            cipher.doFinal(cipherOutput, len)

            // 5. Wire format: [32-byte ephemeral public key] + [ciphertext + tag]
            val result = ByteArray(EPHEMERAL_PUBKEY_SIZE + cipherOutput.size)
            System.arraycopy(ephPubBytes, 0, result, 0, EPHEMERAL_PUBKEY_SIZE)
            System.arraycopy(cipherOutput, 0, result, EPHEMERAL_PUBKEY_SIZE, cipherOutput.size)
            result
        } catch (e: CryptoException) {
            throw e
        } catch (e: Exception) {
            throw CryptoException("Encryption failed for ${dest.toHex()}: ${e.message}", e)
        }
    }

    override fun decrypt(origin: NodeId, ciphertext: ByteArray): ByteArray {
        val minSize = EPHEMERAL_PUBKEY_SIZE + (MAC_SIZE_BITS / 8)
        if (ciphertext.size < minSize) {
            throw CryptoException(
                "Ciphertext too short (${ciphertext.size} bytes, min $minSize bytes)"
            )
        }

        return try {
            // 1. Extract ephemeral public key and payload
            val ephPubKeyBytes = ciphertext.copyOfRange(0, EPHEMERAL_PUBKEY_SIZE)
            val cipherBytes = ciphertext.copyOfRange(EPHEMERAL_PUBKEY_SIZE, ciphertext.size)

            // 2. Diffie-Hellman agreement using local private key
            val localPrivKey = X25519PrivateKeyParameters(localKeyPair.privateKeyBytes, 0)
            val ephPubKey = X25519PublicKeyParameters(ephPubKeyBytes, 0)
            val agreement = X25519Agreement()
            agreement.init(localPrivKey)
            val sharedSecret = ByteArray(agreement.agreementSize)
            agreement.calculateAgreement(ephPubKey, sharedSecret, 0)

            // 3. Derive symmetric key and nonce using HKDF-SHA256
            val hkdf = HKDFBytesGenerator(SHA256Digest())
            hkdf.init(HKDFParameters(sharedSecret, null, CONTEXT_INFO))
            val derived = ByteArray(SYMMETRIC_KEY_SIZE + NONCE_SIZE)
            hkdf.generateBytes(derived, 0, derived.size)
            val key = derived.copyOfRange(0, SYMMETRIC_KEY_SIZE)
            val nonce = derived.copyOfRange(SYMMETRIC_KEY_SIZE, derived.size)

            // 4. Decrypt and authenticate with ChaCha20-Poly1305
            val cipher = ChaCha20Poly1305()
            cipher.init(false, AEADParameters(KeyParameter(key), MAC_SIZE_BITS, nonce, CONTEXT_INFO))
            val plaintext = ByteArray(cipher.getOutputSize(cipherBytes.size))
            val len = cipher.processBytes(cipherBytes, 0, cipherBytes.size, plaintext, 0)
            val finalLen = cipher.doFinal(plaintext, len)
            plaintext.copyOf(finalLen)
        } catch (e: Exception) {
            throw CryptoException(
                "Decryption failed for message from ${origin.toHex()}: ${e.message}. " +
                "Possible causes: wrong recipient, tampered ciphertext, or invalid key.",
                e
            )
        }
    }
}

/**
 * Thrown when an encryption or decryption operation fails.
 */
class CryptoException(message: String, cause: Throwable? = null) : Exception(message, cause)

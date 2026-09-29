package mesh.transport

import mesh.protocol.NodeId

/**
 * End-to-end cryptographic encryption and decryption contract.
 *
 * Full implementation (Noise/Tink) will be implemented in Phase 8; no-op implementation is used during earlier phases.
 */
interface Crypto {
    fun encrypt(dest: NodeId, plaintext: ByteArray): ByteArray
    fun decrypt(origin: NodeId, ciphertext: ByteArray): ByteArray
}

/**
 * Pass-through implementation of [Crypto] for testing and early development.
 */
class NoOpCrypto : Crypto {
    override fun encrypt(dest: NodeId, plaintext: ByteArray): ByteArray = plaintext.clone()
    override fun decrypt(origin: NodeId, ciphertext: ByteArray): ByteArray = ciphertext.clone()
}

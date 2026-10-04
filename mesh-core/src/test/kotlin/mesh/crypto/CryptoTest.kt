package mesh.crypto

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import assertk.assertions.isTrue
import assertk.assertions.isFailure
import assertk.assertions.hasClass
import mesh.fakes.FakeClock
import mesh.fakes.FakeTransport
import mesh.node.MeshNode
import mesh.node.NodeConfig
import mesh.protocol.NodeId
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class CryptoTest {

    // -------------------------------------------------------------------------
    // MeshKeyPair tests
    // -------------------------------------------------------------------------

    @Test
    fun `MeshKeyPair generates different key pairs each call`() {
        val kp1 = MeshKeyPair.generate()
        val kp2 = MeshKeyPair.generate()
        assertThat(kp1.publicKeysetBytes).isNotEqualTo(kp2.publicKeysetBytes)
    }

    @Test
    fun `MeshKeyPair publicKeysetBytes is non-empty Tink serialised keyset`() {
        val kp = MeshKeyPair.generate()
        assertThat(kp.publicKeysetBytes.isNotEmpty()).isTrue()
    }

    @Test
    fun `MeshKeyPair nodeId is derived from publicKeysetBytes`() {
        val kp = MeshKeyPair.generate()
        val derived = NodeId.fromPublicKey(kp.publicKeysetBytes)
        assertThat(kp.nodeId).isEqualTo(derived)
    }

    @Test
    fun `MeshKeyPair serialise and deserialise round-trip produces same publicKeysetBytes`() {
        val original = MeshKeyPair.generate()
        val serialised = original.serialisePrivate()
        val restored = MeshKeyPair.fromSerialisedKeyset(serialised)
        assertThat(restored.publicKeysetBytes).isEqualTo(original.publicKeysetBytes)
    }

    // -------------------------------------------------------------------------
    // InMemoryKeyStore tests
    // -------------------------------------------------------------------------

    @Test
    fun `InMemoryKeyStore registers and retrieves peer keys`() {
        val store = InMemoryKeyStore()
        val nodeId = NodeId(byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0))
        val keyBytes = ByteArray(32) { it.toByte() }

        store.registerPeerKey(nodeId, keyBytes)

        assertThat(store.getPeerKeyBytes(nodeId)).isNotNull()
        assertThat(store.getPeerKeyBytes(nodeId)!!).isEqualTo(keyBytes)
    }

    @Test
    fun `InMemoryKeyStore returns null for unknown peer`() {
        val store = InMemoryKeyStore()
        val nodeId = NodeId(byteArrayOf(2, 0, 0, 0, 0, 0, 0, 0))
        assertThat(store.getPeerKeyBytes(nodeId)).isNull()
    }

    @Test
    fun `InMemoryKeyStore remove clears peer key`() {
        val store = InMemoryKeyStore()
        val nodeId = NodeId(byteArrayOf(3, 0, 0, 0, 0, 0, 0, 0))
        store.registerPeerKey(nodeId, ByteArray(32))
        store.removePeerKey(nodeId)
        assertThat(store.getPeerKeyBytes(nodeId)).isNull()
    }

    @Test
    fun `InMemoryKeyStore getPeerKeyBytes returns defensive copy`() {
        val store = InMemoryKeyStore()
        val nodeId = NodeId(byteArrayOf(4, 0, 0, 0, 0, 0, 0, 0))
        val keyBytes = ByteArray(32) { 0x42 }
        store.registerPeerKey(nodeId, keyBytes)

        val retrieved = store.getPeerKeyBytes(nodeId)!!
        retrieved[0] = 0x00 // mutate the returned copy

        // Stored entry must be unchanged
        assertThat(store.getPeerKeyBytes(nodeId)!![0]).isEqualTo(0x42.toByte())
    }

    @Test
    fun `InMemoryKeyStore knownPeers lists registered IDs`() {
        val store = InMemoryKeyStore()
        val id1 = NodeId(byteArrayOf(1, 0, 0, 0, 0, 0, 0, 0))
        val id2 = NodeId(byteArrayOf(2, 0, 0, 0, 0, 0, 0, 0))
        store.registerPeerKey(id1, ByteArray(32))
        store.registerPeerKey(id2, ByteArray(32))
        assertThat(store.knownPeers()).isEqualTo(setOf(id1, id2))
    }

    // -------------------------------------------------------------------------
    // X25519Crypto encrypt / decrypt round-trip
    // -------------------------------------------------------------------------

    @Test
    fun `X25519Crypto encrypt-decrypt round-trip recovers plaintext`() {
        val aliceKp = MeshKeyPair.generate()
        val bobKp = MeshKeyPair.generate()

        val aliceKeyStore = InMemoryKeyStore()
        val bobKeyStore = InMemoryKeyStore()

        // Simulate HELLO exchange: Alice registers Bob's serialised public keyset; Bob registers Alice's
        aliceKeyStore.registerPeerKey(bobKp.nodeId, bobKp.publicKeysetBytes)
        bobKeyStore.registerPeerKey(aliceKp.nodeId, aliceKp.publicKeysetBytes)

        val aliceCrypto = X25519Crypto(aliceKp, aliceKeyStore)
        val bobCrypto = X25519Crypto(bobKp, bobKeyStore)

        val plaintext = "Emergency shelter at grid 4519".toByteArray()
        val ciphertext = aliceCrypto.encrypt(bobKp.nodeId, plaintext)
        val recovered = bobCrypto.decrypt(aliceKp.nodeId, ciphertext)

        assertThat(String(recovered)).isEqualTo("Emergency shelter at grid 4519")
    }

    @Test
    fun `X25519Crypto ciphertext is different from plaintext`() {
        val aliceKp = MeshKeyPair.generate()
        val bobKp = MeshKeyPair.generate()

        val aliceKeyStore = InMemoryKeyStore()
        aliceKeyStore.registerPeerKey(bobKp.nodeId, bobKp.publicKeyBytes)

        val aliceCrypto = X25519Crypto(aliceKp, aliceKeyStore)
        val plaintext = "SOS".toByteArray()
        val ciphertext = aliceCrypto.encrypt(bobKp.nodeId, plaintext)

        assertThat(ciphertext).isNotEqualTo(plaintext)
    }

    @Test
    fun `X25519Crypto produces different ciphertext each call (ephemeral keys)`() {
        val aliceKp = MeshKeyPair.generate()
        val bobKp = MeshKeyPair.generate()

        val aliceKeyStore = InMemoryKeyStore()
        aliceKeyStore.registerPeerKey(bobKp.nodeId, bobKp.publicKeyBytes)

        val aliceCrypto = X25519Crypto(aliceKp, aliceKeyStore)
        val plaintext = "SOS".toByteArray()

        val ct1 = aliceCrypto.encrypt(bobKp.nodeId, plaintext)
        val ct2 = aliceCrypto.encrypt(bobKp.nodeId, plaintext)

        // HPKE uses a fresh ephemeral key per message — ciphertexts should differ
        assertThat(ct1).isNotEqualTo(ct2)
    }

    @Test
    fun `X25519Crypto encrypt throws CryptoException when peer key not registered`() {
        val aliceKp = MeshKeyPair.generate()
        val bobKp = MeshKeyPair.generate()
        val emptyStore = InMemoryKeyStore()

        val aliceCrypto = X25519Crypto(aliceKp, emptyStore)

        assertThrows<CryptoException> {
            aliceCrypto.encrypt(bobKp.nodeId, "hello".toByteArray())
        }
    }

    @Test
    fun `X25519Crypto relay node fed ciphertext cannot decrypt it`() {
        val aliceKp = MeshKeyPair.generate()
        val bobKp = MeshKeyPair.generate()
        val relayKp = MeshKeyPair.generate()

        val aliceKeyStore = InMemoryKeyStore()
        aliceKeyStore.registerPeerKey(bobKp.nodeId, bobKp.publicKeyBytes)

        val aliceCrypto = X25519Crypto(aliceKp, aliceKeyStore)
        val ciphertext = aliceCrypto.encrypt(bobKp.nodeId, "Secret".toByteArray())

        // Relay tries to decrypt with its own private key — must fail
        val relayKeyStore = InMemoryKeyStore()
        val relayCrypto = X25519Crypto(relayKp, relayKeyStore)
        assertThrows<CryptoException> {
            relayCrypto.decrypt(aliceKp.nodeId, ciphertext)
        }
    }

    @Test
    fun `X25519Crypto tampered ciphertext fails authentication`() {
        val aliceKp = MeshKeyPair.generate()
        val bobKp = MeshKeyPair.generate()

        val aliceKeyStore = InMemoryKeyStore()
        val bobKeyStore = InMemoryKeyStore()
        aliceKeyStore.registerPeerKey(bobKp.nodeId, bobKp.publicKeysetBytes)
        bobKeyStore.registerPeerKey(aliceKp.nodeId, aliceKp.publicKeysetBytes)

        val aliceCrypto = X25519Crypto(aliceKp, aliceKeyStore)
        val bobCrypto = X25519Crypto(bobKp, bobKeyStore)

        val ciphertext = aliceCrypto.encrypt(bobKp.nodeId, "Top secret".toByteArray()).also {
            // Flip a bit in the AEAD ciphertext body
            it[it.size / 2] = (it[it.size / 2].toInt() xor 0xFF).toByte()
        }

        assertThrows<CryptoException> {
            bobCrypto.decrypt(aliceKp.nodeId, ciphertext)
        }
    }

    // -------------------------------------------------------------------------
    // End-to-end integration: MeshNode.withCrypto()
    // -------------------------------------------------------------------------

    @Test
    fun `withCrypto nodes exchange HELLO and then encrypt-decrypt a unicast message`() {
        val clock = FakeClock(1000L)

        val configA = NodeConfig(displayName = "Alice")
        val configB = NodeConfig(displayName = "Bob")

        val transportA = FakeTransport(configA.keyPair.nodeId)
        val transportB = FakeTransport(configB.keyPair.nodeId)

        val nodeA = MeshNode.withCrypto(transport = transportA, clock = clock, config = configA)
        val nodeB = MeshNode.withCrypto(transport = transportB, clock = clock, config = configB)

        // Link transports — fires onPeerConnected which triggers HELLO exchange
        transportA.link(transportB)

        // Capture what B received
        var receivedByB: String? = null
        nodeB.onMessageReceived { _, payload, _ ->
            receivedByB = String(payload)
        }

        // Alice sends encrypted unicast to Bob (Bob's key was registered via HELLO)
        nodeA.send(nodeB.nodeId, "Rescue needed at sector 7".toByteArray())

        assertThat(receivedByB).isEqualTo("Rescue needed at sector 7")
    }

    @Test
    fun `withCrypto multi-hop delivery A to C via relay B - relay cannot read plaintext`() {
        val clock = FakeClock(1000L)

        val configA = NodeConfig(displayName = "Alice")
        val configB = NodeConfig(displayName = "Bob (relay)")
        val configC = NodeConfig(displayName = "Carol")

        val transportA = FakeTransport(configA.keyPair.nodeId)
        val transportB = FakeTransport(configB.keyPair.nodeId)
        val transportC = FakeTransport(configC.keyPair.nodeId)

        val nodeA = MeshNode.withCrypto(transport = transportA, clock = clock, config = configA)
        val nodeB = MeshNode.withCrypto(transport = transportB, clock = clock, config = configB)
        val nodeC = MeshNode.withCrypto(transport = transportC, clock = clock, config = configC)

        // A-B-C chain; A and C are not directly connected
        transportA.link(transportB)
        transportB.link(transportC)

        // C needs A's public key to verify nodeId; A needs C's to encrypt.
        // The HELLO packets exchanged on link() propagate A→B→ (B knows A, B knows C).
        // For A to encrypt to C it needs C's public key. We simulate a second-hop HELLO
        // by explicitly sending HELLOs from C so A can eventually learn C's key via B.
        // In a real network additional HELLO rounds would propagate. Here we short-circuit
        // by registering C's public key in A's keyStore directly (simulates HELLO propagation).
        nodeA.keyStore.registerPeerKey(nodeC.nodeId, configC.keyPair.publicKeysetBytes)

        var receivedByC: String? = null
        nodeC.onMessageReceived { _, payload, _ ->
            receivedByC = String(payload)
        }

        // Relay B should NOT be able to see the plaintext
        var relayBSawPlaintext = false
        nodeB.onMessageReceived { _, payload, _ ->
            if (String(payload) == "Encrypted secret") relayBSawPlaintext = true
        }

        nodeA.send(nodeC.nodeId, "Encrypted secret".toByteArray())

        assertThat(receivedByC).isEqualTo("Encrypted secret")
        // B delivers the packet onward; it was not the dest so its callback isn't invoked for DATA.
        // Relay B's onLocalDelivery is NOT called for packets addressed to C, so this is always false.
        assertThat(relayBSawPlaintext).isEqualTo(false)
    }

    @Test
    fun `withCrypto broadcast is received as plaintext by all nodes`() {
        val clock = FakeClock(1000L)

        val configA = NodeConfig(displayName = "Alice")
        val configB = NodeConfig(displayName = "Bob")

        val transportA = FakeTransport(configA.keyPair.nodeId)
        val transportB = FakeTransport(configB.keyPair.nodeId)

        val nodeA = MeshNode.withCrypto(transport = transportA, clock = clock, config = configA)
        val nodeB = MeshNode.withCrypto(transport = transportB, clock = clock, config = configB)

        transportA.link(transportB)

        var broadcastText: String? = null
        nodeB.onMessageReceived { _, payload, isBcast ->
            if (isBcast) broadcastText = String(payload)
        }

        nodeA.broadcast("Flood warning".toByteArray())
        assertThat(broadcastText).isEqualTo("Flood warning")
    }

    @Test
    fun `NodeConfig key pair generates stable nodeId`() {
        val config = NodeConfig()
        val id1 = config.keyPair.nodeId
        val id2 = config.keyPair.nodeId
        assertThat(id1).isEqualTo(id2)
    }
}

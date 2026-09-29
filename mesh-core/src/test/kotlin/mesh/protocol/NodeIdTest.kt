package mesh.protocol

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.security.MessageDigest

class NodeIdTest {

    @Test
    fun `valid 8-byte array creates NodeId`() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val nodeId = NodeId(bytes)
        assertThat(nodeId.bytes).isEqualTo(bytes)
        assertThat(nodeId.toHex()).isEqualTo("0102030405060708")
    }

    @Test
    fun `invalid byte array size throws IllegalArgumentException`() {
        assertThrows<IllegalArgumentException> {
            NodeId(byteArrayOf(1, 2, 3))
        }
        assertThrows<IllegalArgumentException> {
            NodeId(ByteArray(9))
        }
    }

    @Test
    fun `fromPublicKey derives first 8 bytes of SHA-256 digest`() {
        val pubKey = "test-public-key-material".toByteArray(Charsets.UTF_8)
        val expectedDigest = MessageDigest.getInstance("SHA-256").digest(pubKey).copyOf(8)
        val nodeId = NodeId.fromPublicKey(pubKey)
        assertThat(nodeId.bytes).isEqualTo(expectedDigest)
    }

    @Test
    fun `hex conversion round-trip`() {
        val hex = "0a1b2c3d4e5f6789"
        val nodeId = NodeId.fromHex(hex)
        assertThat(nodeId.toHex()).isEqualTo(hex)
        assertThat(nodeId.toString()).isEqualTo(hex)
    }

    @Test
    fun `invalid hex throws exception`() {
        assertThrows<IllegalArgumentException> {
            NodeId.fromHex("0102")
        }
        assertThrows<IllegalArgumentException> {
            NodeId.fromHex("010203040506070z")
        }
    }

    @Test
    fun `equals and hashCode compare content`() {
        val bytes1 = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val bytes2 = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val bytes3 = byteArrayOf(8, 7, 6, 5, 4, 3, 2, 1)

        val id1 = NodeId(bytes1)
        val id2 = NodeId(bytes2)
        val id3 = NodeId(bytes3)

        assertThat(id1).isEqualTo(id2)
        assertThat(id1.hashCode()).isEqualTo(id2.hashCode())
        assertThat(id1).isNotEqualTo(id3)
    }

    @Test
    fun `compareTo orders unsigned byte values correctly`() {
        val idSmall = NodeId(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 1))
        val idLarge = NodeId(byteArrayOf(0, 0, 0, 0, 0, 0, 0, 2))
        val idHighByte = NodeId(byteArrayOf(0xFF.toByte(), 0, 0, 0, 0, 0, 0, 0))

        assertThat(idSmall < idLarge).isTrue()
        assertThat(idLarge < idHighByte).isTrue()
    }

    @Test
    fun `fromBytesOrNull handles broadcast and unicast`() {
        val empty = ByteArray(0)
        assertThat(NodeId.fromBytesOrNull(empty)).isEqualTo(null)

        val valid = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val parsed = NodeId.fromBytesOrNull(valid)
        assertThat(parsed).isNotNull()
        assertThat(parsed!!.bytes).isEqualTo(valid)

        assertThrows<IllegalArgumentException> {
            NodeId.fromBytesOrNull(byteArrayOf(1, 2))
        }
    }
}

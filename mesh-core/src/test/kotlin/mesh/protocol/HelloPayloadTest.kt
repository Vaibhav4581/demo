package mesh.protocol

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotEqualTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class HelloPayloadTest {

    @Test
    fun `round-trip encode and decode hello payload`() {
        val name = "Alice Mobile"
        val pubKey = byteArrayOf(10, 20, 30, 40, 50, 60, 70, 80)
        val original = HelloPayload(name, pubKey)

        val encoded = original.encode()
        val decoded = HelloPayload.decode(encoded)

        assertThat(decoded.displayName).isEqualTo(name)
        assertThat(decoded.publicKey).isEqualTo(pubKey)
        assertThat(decoded).isEqualTo(original)
    }

    @Test
    fun `decode fails on truncated buffer`() {
        assertThrows<IllegalArgumentException> {
            HelloPayload.decode(byteArrayOf(1))
        }
        assertThrows<IllegalArgumentException> {
            HelloPayload.decode(byteArrayOf(0, 10, 65)) // expects 10 bytes, has 1
        }
    }

    @Test
    fun `equals and hashCode compare content`() {
        val p1 = HelloPayload("Alice", byteArrayOf(1, 2, 3))
        val p2 = HelloPayload("Alice", byteArrayOf(1, 2, 3))
        val p3 = HelloPayload("Bob", byteArrayOf(1, 2, 3))

        assertThat(p1).isEqualTo(p2)
        assertThat(p1.hashCode()).isEqualTo(p2.hashCode())
        assertThat(p1).isNotEqualTo(p3)
    }
}

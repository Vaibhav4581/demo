package mesh.delivery

import assertk.assertThat
import assertk.assertions.isCloseTo
import assertk.assertions.isEqualTo
import assertk.assertions.isFalse
import assertk.assertions.isTrue
import mesh.fakes.FakeClock
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RateLimiterTest {

    private lateinit var clock: FakeClock

    @BeforeEach
    fun setup() {
        clock = FakeClock(10_000L)
    }

    @Test
    fun `burst consumption up to maxTokens succeeds and subsequent acquire fails`() {
        val limiter = RateLimiter(maxTokens = 3.0, refillRatePerSec = 1.0, clock = clock)

        assertThat(limiter.tryAcquire(1.0)).isTrue()
        assertThat(limiter.tryAcquire(1.0)).isTrue()
        assertThat(limiter.tryAcquire(1.0)).isTrue()

        // Bucket exhausted
        assertThat(limiter.tryAcquire(1.0)).isFalse()
        assertThat(limiter.getAvailableTokens()).isCloseTo(0.0, 0.001)
    }

    @Test
    fun `tokens refill proportionally over time`() {
        val limiter = RateLimiter(maxTokens = 10.0, refillRatePerSec = 2.0, clock = clock)

        // Drain completely
        assertThat(limiter.tryAcquire(10.0)).isTrue()
        assertThat(limiter.tryAcquire(1.0)).isFalse()

        // Advance 1.5 seconds -> should refill 3.0 tokens
        clock.advance(1500L)
        assertThat(limiter.getAvailableTokens()).isCloseTo(3.0, 0.001)

        assertThat(limiter.tryAcquire(2.0)).isTrue()
        assertThat(limiter.getAvailableTokens()).isCloseTo(1.0, 0.001)
        assertThat(limiter.tryAcquire(2.0)).isFalse()
    }

    @Test
    fun `refill is capped at maxTokens`() {
        val limiter = RateLimiter(maxTokens = 5.0, refillRatePerSec = 10.0, clock = clock)

        // Consume 2 tokens
        assertThat(limiter.tryAcquire(2.0)).isTrue()
        assertThat(limiter.getAvailableTokens()).isCloseTo(3.0, 0.001)

        // Advance 10 seconds (would generate 100 tokens, but max is 5.0)
        clock.advance(10_000L)
        assertThat(limiter.getAvailableTokens()).isCloseTo(5.0, 0.001)
    }

    @Test
    fun `unlimited mode when rate or maxTokens is non-positive`() {
        val limiter = RateLimiter(maxTokens = 0.0, refillRatePerSec = 0.0, clock = clock)

        for (i in 1..100) {
            assertThat(limiter.tryAcquire(10.0)).isTrue()
        }
    }

    @Test
    fun `reset restores bucket to full capacity`() {
        val limiter = RateLimiter(maxTokens = 5.0, refillRatePerSec = 1.0, clock = clock)

        limiter.tryAcquire(5.0)
        assertThat(limiter.tryAcquire(1.0)).isFalse()

        limiter.reset()
        assertThat(limiter.getAvailableTokens()).isCloseTo(5.0, 0.001)
        assertThat(limiter.tryAcquire(5.0)).isTrue()
    }

    @Test
    fun `negative or zero token acquisition always succeeds without consuming`() {
        val limiter = RateLimiter(maxTokens = 5.0, refillRatePerSec = 1.0, clock = clock)

        assertThat(limiter.tryAcquire(0.0)).isTrue()
        assertThat(limiter.tryAcquire(-1.0)).isTrue()
        assertThat(limiter.getAvailableTokens()).isCloseTo(5.0, 0.001)
    }

    @Test
    fun `backward clock skew does not corrupt available tokens`() {
        val limiter = RateLimiter(maxTokens = 5.0, refillRatePerSec = 1.0, clock = clock)

        limiter.tryAcquire(2.0)
        assertThat(limiter.getAvailableTokens()).isCloseTo(3.0, 0.001)

        // Time jumps backward
        clock.set(clock.currentTimeMs - 2000L)
        assertThat(limiter.getAvailableTokens()).isCloseTo(3.0, 0.001)
    }
}

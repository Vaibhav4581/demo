package mesh.delivery

import mesh.transport.Clock
import mesh.transport.SystemClock

/**
 * Token bucket rate limiter for controlling packet transmission, broadcast generation,
 * and forwarding rates to prevent runaway network flooding.
 *
 * @param maxTokens Maximum burst capacity of the token bucket.
 * @param refillRatePerSec Tokens added to the bucket per second.
 * @param clock Time source for measuring elapsed intervals.
 */
class RateLimiter(
    val maxTokens: Double,
    val refillRatePerSec: Double,
    val clock: Clock = SystemClock
) {
    private var availableTokens: Double = maxTokens
    private var lastRefillTimeMs: Long = clock.nowMs()

    /**
     * Attempts to acquire [tokens] from the bucket.
     *
     * @param tokens Number of tokens to acquire (default: 1.0).
     * @return `true` if sufficient tokens were available and consumed; `false` otherwise.
     */
    @Synchronized
    fun tryAcquire(tokens: Double = 1.0): Boolean {
        if (tokens <= 0.0) return true
        if (maxTokens <= 0.0 || refillRatePerSec <= 0.0) return true // Unlimited mode

        refill()

        return if (availableTokens >= tokens) {
            availableTokens -= tokens
            true
        } else {
            false
        }
    }

    /**
     * Returns the currently available tokens after refilling up to the current clock time.
     */
    @Synchronized
    fun getAvailableTokens(): Double {
        if (maxTokens <= 0.0 || refillRatePerSec <= 0.0) return Double.POSITIVE_INFINITY
        refill()
        return availableTokens
    }

    /**
     * Resets the bucket to its maximum token capacity.
     */
    @Synchronized
    fun reset() {
        availableTokens = maxTokens
        lastRefillTimeMs = clock.nowMs()
    }

    private fun refill() {
        val nowMs = clock.nowMs()
        val elapsedMs = nowMs - lastRefillTimeMs
        if (elapsedMs > 0) {
            val tokensToAdd = (elapsedMs / 1000.0) * refillRatePerSec
            availableTokens = (availableTokens + tokensToAdd).coerceAtMost(maxTokens)
            lastRefillTimeMs = nowMs
        } else if (elapsedMs < 0) {
            // Guard against clock skew or time jumping backward
            lastRefillTimeMs = nowMs
        }
    }
}

package mesh.delivery

import kotlin.math.min
import kotlin.math.roundToLong

/**
 * Exponential backoff retransmission policy for direct messages awaiting ACK.
 *
 * Defaults to 5s, 15s, 45s, capped at 60s.
 */
class RetryPolicy(
    val initialDelayMs: Long = DEFAULT_INITIAL_DELAY_MS,
    val backoffMultiplier: Double = DEFAULT_MULTIPLIER,
    val maxDelayMs: Long = DEFAULT_MAX_DELAY_MS,
    val maxRetries: Int = DEFAULT_MAX_RETRIES
) {
    /**
     * Computes the retry delay in milliseconds for the given 0-indexed [attemptNumber].
     */
    fun nextRetryDelayMs(attemptNumber: Int): Long {
        if (attemptNumber <= 0) return initialDelayMs
        var delay = initialDelayMs.toDouble()
        for (i in 0 until attemptNumber) {
            delay *= backoffMultiplier
            if (delay >= maxDelayMs) {
                return maxDelayMs
            }
        }
        return min(delay.roundToLong(), maxDelayMs)
    }

    /**
     * Checks if another retry attempt is permitted.
     */
    fun canRetry(attemptNumber: Int, expiresAtMs: Long, nowMs: Long): Boolean {
        if (attemptNumber >= maxRetries) return false
        if (nowMs >= expiresAtMs) return false
        val nextDelay = nextRetryDelayMs(attemptNumber)
        return nowMs + nextDelay < expiresAtMs
    }

    companion object {
        const val DEFAULT_INITIAL_DELAY_MS = 5_000L   // 5s
        const val DEFAULT_MULTIPLIER = 3.0           // 5s -> 15s -> 45s -> 60s
        const val DEFAULT_MAX_DELAY_MS = 60_000L      // 1 minute cap
        const val DEFAULT_MAX_RETRIES = 5
    }
}

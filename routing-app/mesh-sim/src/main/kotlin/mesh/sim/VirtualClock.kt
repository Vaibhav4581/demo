package mesh.sim

import mesh.transport.Clock

/**
 * Deterministic virtual clock controlled by the discrete-event simulation scheduler.
 */
class VirtualClock(initialTimeMs: Long = 0L) : Clock {
    var currentTimeMs: Long = initialTimeMs
        private set

    override fun nowMs(): Long = currentTimeMs

    fun advanceTo(targetTimeMs: Long) {
        require(targetTimeMs >= currentTimeMs) {
            "Cannot advance virtual time backwards from $currentTimeMs to $targetTimeMs"
        }
        currentTimeMs = targetTimeMs
    }

    fun advanceBy(deltaMs: Long) {
        require(deltaMs >= 0) { "Time delta must be non-negative: $deltaMs" }
        currentTimeMs += deltaMs
    }

    fun reset(timeMs: Long = 0L) {
        currentTimeMs = timeMs
    }
}

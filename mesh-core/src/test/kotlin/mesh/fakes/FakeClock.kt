package mesh.fakes

import mesh.transport.Clock

/**
 * Deterministic controllable virtual clock for unit and simulation testing.
 */
class FakeClock(var currentTimeMs: Long = 1_000_000L) : Clock {
    override fun nowMs(): Long = currentTimeMs

    fun advance(deltaMs: Long) {
        require(deltaMs >= 0) { "Cannot move clock backwards" }
        currentTimeMs += deltaMs
    }

    fun set(timeMs: Long) {
        currentTimeMs = timeMs
    }
}

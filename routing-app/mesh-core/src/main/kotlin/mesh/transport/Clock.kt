package mesh.transport

/**
 * Clock abstraction allowing deterministic virtual-time testing and simulation.
 */
interface Clock {
    /**
     * Returns current epoch time in milliseconds.
     */
    fun nowMs(): Long
}

/**
 * Default clock implementation using standard system wall-clock time.
 */
object SystemClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}

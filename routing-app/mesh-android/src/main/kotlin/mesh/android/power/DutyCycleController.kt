package mesh.android.power

import mesh.protocol.NodeId
import mesh.transport.Clock
import mesh.transport.SystemClock
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Operating states of the adaptive duty cycling controller.
 */
enum class DutyCycleState {
    /**
     * Continuous discovery and advertising. Used during high traffic or dynamic neighbourhood changes.
     */
    ACTIVE,

    /**
     * Short scan window within IDLE state to discover newly arrived nodes.
     */
    IDLE_SCANNING,

    /**
     * Sleep window within IDLE state where radio discovery is halted to save battery.
     */
    IDLE_SLEEPING
}

/**
 * Configuration parameters for adaptive duty cycling.
 */
data class DutyCycleConfig(
    val stabilityThresholdMs: Long = 60_000L,
    val inactivityThresholdMs: Long = 60_000L,
    val scanWindowMs: Long = 10_000L,
    val baseSleepIntervalMs: Long = 30_000L,
    val lowBatterySleepIntervalMs: Long = 90_000L,
    val criticalBatterySleepIntervalMs: Long = 150_000L,
    val lowBatteryThreshold: Int = 20,
    val criticalBatteryThreshold: Int = 10
)

/**
 * Listener interface for duty cycle state changes and radio control actions.
 */
interface DutyCycleListener {
    fun onStateChanged(newState: DutyCycleState)
    fun onRadioScanStateChanged(enableScanning: Boolean)
}

/**
 * Adaptive duty cycling controller.
 *
 * Transitions between ACTIVE (continuous scanning/advertising) and IDLE (periodic scan/sleep windows)
 * to conserve battery in disaster scenarios while maintaining responsive message relaying.
 */
class DutyCycleController(
    val config: DutyCycleConfig = DutyCycleConfig(),
    val clock: Clock = SystemClock,
    var batteryLevelProvider: () -> Int = { 100 },
    var listener: DutyCycleListener? = null
) {
    private val isRunning = AtomicBoolean(false)

    var currentState: DutyCycleState = DutyCycleState.ACTIVE
        private set

    var stateEnteredTimeMs: Long = clock.nowMs()
        private set

    var lastPeerChangeTimeMs: Long = clock.nowMs()
        private set

    var lastTrafficTimeMs: Long = clock.nowMs()
        private set

    fun start() {
        if (isRunning.compareAndSet(false, true)) {
            transitionTo(DutyCycleState.ACTIVE)
        }
    }

    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            transitionTo(DutyCycleState.ACTIVE)
            listener?.onRadioScanStateChanged(true)
        }
    }

    /**
     * Called whenever traffic is sent, received, or queued.
     * Immediately resets the inactivity timer and wakes the controller back to ACTIVE.
     */
    @Synchronized
    fun onTraffic() {
        lastTrafficTimeMs = clock.nowMs()
        if (currentState != DutyCycleState.ACTIVE) {
            transitionTo(DutyCycleState.ACTIVE)
        }
    }

    /**
     * Called when a peer connection is established.
     */
    @Synchronized
    fun onPeerConnected(peer: NodeId) {
        lastPeerChangeTimeMs = clock.nowMs()
    }

    /**
     * Called when a peer connection is lost.
     * Indicates topological disruption; immediately forces the controller back to ACTIVE.
     */
    @Synchronized
    fun onPeerDisconnected(peer: NodeId) {
        lastPeerChangeTimeMs = clock.nowMs()
        if (currentState != DutyCycleState.ACTIVE) {
            transitionTo(DutyCycleState.ACTIVE)
        }
    }

    /**
     * Explicit wake up back to continuous ACTIVE mode.
     */
    @Synchronized
    fun wakeUp() {
        lastTrafficTimeMs = clock.nowMs()
        if (currentState != DutyCycleState.ACTIVE) {
            transitionTo(DutyCycleState.ACTIVE)
        }
    }

    /**
     * Calculates sleep duration based on the current battery percentage.
     */
    fun calculateSleepDuration(batteryPercent: Int = batteryLevelProvider()): Long {
        return when {
            batteryPercent <= config.criticalBatteryThreshold -> config.criticalBatterySleepIntervalMs
            batteryPercent <= config.lowBatteryThreshold -> config.lowBatterySleepIntervalMs
            else -> config.baseSleepIntervalMs
        }
    }

    /**
     * Periodic evaluation tick. Called by service or clock loop.
     */
    @Synchronized
    fun evaluate(nowMs: Long = clock.nowMs()) {
        if (!isRunning.get()) return

        when (currentState) {
            DutyCycleState.ACTIVE -> {
                val timeSincePeerChange = nowMs - lastPeerChangeTimeMs
                val timeSinceTraffic = nowMs - lastTrafficTimeMs

                if (timeSincePeerChange >= config.stabilityThresholdMs && timeSinceTraffic >= config.inactivityThresholdMs) {
                    // Neighbourhood is stable and traffic is idle -> transition to power-saving sleep
                    transitionTo(DutyCycleState.IDLE_SLEEPING)
                }
            }

            DutyCycleState.IDLE_SLEEPING -> {
                val sleepDuration = calculateSleepDuration()
                val elapsed = nowMs - stateEnteredTimeMs
                if (elapsed >= sleepDuration) {
                    // Sleep period finished -> wake for short scan window
                    transitionTo(DutyCycleState.IDLE_SCANNING)
                }
            }

            DutyCycleState.IDLE_SCANNING -> {
                val elapsed = nowMs - stateEnteredTimeMs
                if (elapsed >= config.scanWindowMs) {
                    // Scan window finished -> return to sleep
                    transitionTo(DutyCycleState.IDLE_SLEEPING)
                }
            }
        }
    }

    private fun transitionTo(newState: DutyCycleState) {
        currentState = newState
        stateEnteredTimeMs = clock.nowMs()
        listener?.onStateChanged(newState)

        when (newState) {
            DutyCycleState.ACTIVE, DutyCycleState.IDLE_SCANNING -> {
                listener?.onRadioScanStateChanged(true)
            }
            DutyCycleState.IDLE_SLEEPING -> {
                listener?.onRadioScanStateChanged(false)
            }
        }
    }
}

package mesh.android.power

import mesh.protocol.NodeId
import mesh.transport.Clock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DutyCycleControllerTest {

    private class FakeClock(var currentMs: Long = 10_000L) : Clock {
        override fun nowMs(): Long = currentMs
        fun advance(ms: Long) {
            currentMs += ms
        }
    }

    private class TestDutyCycleListener : DutyCycleListener {
        var lastState: DutyCycleState? = null
        var isScanningEnabled: Boolean = true
        var stateChangeCount: Int = 0

        override fun onStateChanged(newState: DutyCycleState) {
            lastState = newState
            stateChangeCount++
        }

        override fun onRadioScanStateChanged(enableScanning: Boolean) {
            isScanningEnabled = enableScanning
        }
    }

    private lateinit var clock: FakeClock
    private lateinit var listener: TestDutyCycleListener
    private lateinit var controller: DutyCycleController
    private val config = DutyCycleConfig(
        stabilityThresholdMs = 60_000L,
        inactivityThresholdMs = 60_000L,
        scanWindowMs = 10_000L,
        baseSleepIntervalMs = 30_000L,
        lowBatterySleepIntervalMs = 90_000L,
        criticalBatterySleepIntervalMs = 150_000L,
        lowBatteryThreshold = 20,
        criticalBatteryThreshold = 10
    )

    private val peer1 = NodeId.fromHex("1111222233334444")

    @BeforeEach
    fun setUp() {
        clock = FakeClock()
        listener = TestDutyCycleListener()
        controller = DutyCycleController(
            config = config,
            clock = clock,
            batteryLevelProvider = { 100 },
            listener = listener
        )
    }

    @Test
    fun `starts in ACTIVE state and keeps radio scanning enabled`() {
        controller.start()

        assertEquals(DutyCycleState.ACTIVE, controller.currentState)
        assertTrue(listener.isScanningEnabled)
    }

    @Test
    fun `remains in ACTIVE state while traffic is active`() {
        controller.start()

        // Advance 40 seconds (less than 60s inactivity threshold)
        clock.advance(40_000L)
        controller.onTraffic()
        clock.advance(30_000L)
        controller.evaluate()

        assertEquals(DutyCycleState.ACTIVE, controller.currentState)
        assertTrue(listener.isScanningEnabled)
    }

    @Test
    fun `remains in ACTIVE state while neighbourhood is changing`() {
        controller.start()

        // Advance 50s, peer connects, advance 30s
        clock.advance(50_000L)
        controller.onPeerConnected(peer1)
        clock.advance(30_000L)
        controller.evaluate()

        assertEquals(DutyCycleState.ACTIVE, controller.currentState)
    }

    @Test
    fun `transitions from ACTIVE to IDLE_SLEEPING when stable and inactive`() {
        controller.start()

        // Advance past both 60s thresholds
        clock.advance(65_000L)
        controller.evaluate()

        assertEquals(DutyCycleState.IDLE_SLEEPING, controller.currentState)
        assertFalse(listener.isScanningEnabled) // Radio scanning disabled to conserve battery
    }

    @Test
    fun `cycles between IDLE_SLEEPING and IDLE_SCANNING based on timers`() {
        controller.start()

        // Enter IDLE_SLEEPING
        clock.advance(65_000L)
        controller.evaluate()
        assertEquals(DutyCycleState.IDLE_SLEEPING, controller.currentState)

        // Advance by base sleep interval (30s)
        clock.advance(30_000L)
        controller.evaluate()

        // Wakes up for short scan window
        assertEquals(DutyCycleState.IDLE_SCANNING, controller.currentState)
        assertTrue(listener.isScanningEnabled)

        // Advance by scan window (10s)
        clock.advance(10_000L)
        controller.evaluate()

        // Returns to sleep
        assertEquals(DutyCycleState.IDLE_SLEEPING, controller.currentState)
        assertFalse(listener.isScanningEnabled)
    }

    @Test
    fun `lengthens sleep interval when battery is low or critical`() {
        var batteryLevel = 100
        controller.batteryLevelProvider = { batteryLevel }
        controller.start()

        // 1. Normal battery (100%) -> 30s sleep
        assertEquals(30_000L, controller.calculateSleepDuration(100))

        // 2. Low battery (20%) -> 90s sleep
        assertEquals(90_000L, controller.calculateSleepDuration(20))

        // 3. Critical battery (8%) -> 150s sleep
        assertEquals(150_000L, controller.calculateSleepDuration(8))

        // Test state transition with low battery (20%)
        batteryLevel = 20
        clock.advance(65_000L)
        controller.evaluate()
        assertEquals(DutyCycleState.IDLE_SLEEPING, controller.currentState)

        // Advance 30s (normal sleep) -> should NOT wake up yet because low battery sleep is 90s
        clock.advance(30_000L)
        controller.evaluate()
        assertEquals(DutyCycleState.IDLE_SLEEPING, controller.currentState)

        // Advance another 60s (total 90s) -> now it wakes up
        clock.advance(60_000L)
        controller.evaluate()
        assertEquals(DutyCycleState.IDLE_SCANNING, controller.currentState)
    }

    @Test
    fun `wakes to ACTIVE immediately on traffic event`() {
        controller.start()

        // Move to IDLE_SLEEPING
        clock.advance(65_000L)
        controller.evaluate()
        assertEquals(DutyCycleState.IDLE_SLEEPING, controller.currentState)

        // Incoming or outgoing packet arrives
        controller.onTraffic()

        assertEquals(DutyCycleState.ACTIVE, controller.currentState)
        assertTrue(listener.isScanningEnabled)
    }

    @Test
    fun `wakes to ACTIVE immediately on lost neighbour event`() {
        controller.start()

        // Move to IDLE_SLEEPING
        clock.advance(65_000L)
        controller.evaluate()
        assertEquals(DutyCycleState.IDLE_SLEEPING, controller.currentState)

        // A neighbour link drops
        controller.onPeerDisconnected(peer1)

        assertEquals(DutyCycleState.ACTIVE, controller.currentState)
        assertTrue(listener.isScanningEnabled)
    }
}

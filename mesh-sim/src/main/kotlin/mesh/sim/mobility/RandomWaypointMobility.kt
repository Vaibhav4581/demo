package mesh.sim.mobility

import mesh.protocol.NodeId
import mesh.sim.SimNetwork
import java.util.Random
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class NodeMobilityState(
    var x: Double,
    var y: Double,
    var destX: Double,
    var destY: Double,
    var speedMps: Double // meters per second
)

/**
 * Random Waypoint mobility model with dynamic link updates based on radio distance.
 */
class RandomWaypointMobility(
    val network: SimNetwork,
    val nodeIds: List<NodeId>,
    val areaWidth: Double = 1000.0,
    val areaHeight: Double = 1000.0,
    val radioRange: Double = 250.0,
    val minSpeedMps: Double = 1.0,
    val maxSpeedMps: Double = 5.0,
    val random: Random = Random(54321),
    initialPositions: Map<NodeId, Pair<Double, Double>>? = null
) {
    val states = mutableMapOf<NodeId, NodeMobilityState>()

    init {
        for (id in nodeIds) {
            val (initX, initY) = initialPositions?.get(id) ?: Pair(
                random.nextDouble() * areaWidth,
                random.nextDouble() * areaHeight
            )
            val state = NodeMobilityState(
                x = initX,
                y = initY,
                destX = random.nextDouble() * areaWidth,
                destY = random.nextDouble() * areaHeight,
                speedMps = minSpeedMps + random.nextDouble() * (maxSpeedMps - minSpeedMps)
            )
            states[id] = state
        }
    }

    /**
     * Advances node positions by [deltaSeconds] and dynamically updates link connectivity.
     */
    fun step(deltaSeconds: Double) {
        // 1. Move each node toward its waypoint
        for ((_, state) in states) {
            val dx = state.destX - state.x
            val dy = state.destY - state.y
            val distToDest = sqrt(dx * dx + dy * dy)
            val moveDist = state.speedMps * deltaSeconds

            if (distToDest <= moveDist) {
                // Reached destination, pick new random destination
                state.x = state.destX
                state.y = state.destY
                state.destX = random.nextDouble() * areaWidth
                state.destY = random.nextDouble() * areaHeight
                state.speedMps = minSpeedMps + random.nextDouble() * (maxSpeedMps - minSpeedMps)
            } else {
                val angle = atan2(dy, dx)
                state.x += cos(angle) * moveDist
                state.y += sin(angle) * moveDist
            }
        }

        // 2. Update links based on radio range
        val n = nodeIds.size
        for (i in 0 until n) {
            val idA = nodeIds[i]
            val sA = states[idA] ?: continue
            for (j in i + 1 until n) {
                val idB = nodeIds[j]
                val sB = states[idB] ?: continue

                val dx = sA.x - sB.x
                val dy = sA.y - sB.y
                val dist = sqrt(dx * dx + dy * dy)

                val inRange = dist <= radioRange
                val isConnected = network.isConnected(idA, idB)

                if (inRange && !isConnected) {
                    network.connect(idA, idB)
                } else if (!inRange && isConnected) {
                    network.disconnect(idA, idB)
                }
            }
        }
    }

    /**
     * Schedules periodic mobility updates in [network] every [intervalMs].
     */
    fun schedulePeriodicUpdates(intervalMs: Long = 1000L) {
        network.schedule(intervalMs, "Mobility update") {
            step(intervalMs / 1000.0)
            schedulePeriodicUpdates(intervalMs)
        }
    }

    fun getPosition(nodeId: NodeId): Pair<Double, Double>? {
        val s = states[nodeId] ?: return null
        return Pair(s.x, s.y)
    }
}

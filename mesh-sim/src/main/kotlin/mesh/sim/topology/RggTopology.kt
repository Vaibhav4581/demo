package mesh.sim.topology

import mesh.node.NodeConfig
import mesh.protocol.NodeId
import mesh.sim.SimNetwork
import java.util.Random
import kotlin.math.sqrt

/**
 * Random Geometric Graph (RGG) topology placing nodes in a 2D bounding area.
 * Links exist between any two nodes whose distance is within [radioRange].
 */
class RggTopology(
    override val network: SimNetwork,
    val nodeCount: Int,
    val areaWidth: Double = 1000.0,
    val areaHeight: Double = 1000.0,
    val radioRange: Double = 250.0,
    val linkLatencyMs: Long = 10L,
    val linkLossRate: Double = 0.0,
    val random: Random = Random(12345),
    configFactory: (Int) -> NodeConfig = { NodeConfig(displayName = "RGG-$it") }
) : Topology {

    override val nodes: List<SimNodeEntry> = createNodes(nodeCount, configFactory)
    val positions = mutableMapOf<NodeId, Pair<Double, Double>>()

    init {
        // Assign coordinates
        for (entry in nodes) {
            val x = random.nextDouble() * areaWidth
            val y = random.nextDouble() * areaHeight
            positions[entry.id] = Pair(x, y)
        }

        // Establish links based on radio range
        for (i in 0 until nodeCount) {
            for (j in i + 1 until nodeCount) {
                val p1 = positions[nodes[i].id]!!
                val p2 = positions[nodes[j].id]!!
                val dx = p1.first - p2.first
                val dy = p1.second - p2.second
                val dist = sqrt(dx * dx + dy * dy)
                if (dist <= radioRange) {
                    network.connect(nodes[i].id, nodes[j].id, linkLatencyMs, linkLossRate)
                }
            }
        }
    }

    fun getPosition(nodeId: NodeId): Pair<Double, Double>? = positions[nodeId]
}

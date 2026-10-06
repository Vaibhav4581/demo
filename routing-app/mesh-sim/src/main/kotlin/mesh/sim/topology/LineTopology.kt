package mesh.sim.topology

import mesh.node.NodeConfig
import mesh.sim.SimNetwork

/**
 * Sequential line topology: Node(0) <-> Node(1) <-> ... <-> Node(N-1).
 */
class LineTopology(
    override val network: SimNetwork,
    val nodeCount: Int,
    val linkLatencyMs: Long = 10L,
    val linkLossRate: Double = 0.0,
    configFactory: (Int) -> NodeConfig = { NodeConfig(displayName = "Line-$it") }
) : Topology {

    override val nodes: List<SimNodeEntry> = createNodes(nodeCount, configFactory)

    init {
        require(nodeCount >= 2) { "Line topology requires at least 2 nodes" }
        for (i in 0 until nodeCount - 1) {
            network.connect(nodes[i].id, nodes[i + 1].id, linkLatencyMs, linkLossRate)
        }
    }
}

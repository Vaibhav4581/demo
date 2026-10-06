package mesh.sim.topology

import mesh.node.MeshNode
import mesh.node.NodeConfig
import mesh.protocol.NodeId
import mesh.sim.SimNetwork

/**
 * 2D Lattice Grid Topology with redundant multi-hop paths.
 */
class GridTopology(
    override val network: SimNetwork,
    val width: Int,
    val height: Int,
    val linkLatencyMs: Long = 10L,
    val linkLossRate: Double = 0.0,
    configFactory: (Int) -> NodeConfig = { NodeConfig(displayName = "Grid-$it") }
) : Topology {

    val totalNodes: Int = width * height
    override val nodes: List<SimNodeEntry> = createNodes(totalNodes, configFactory)

    init {
        require(width >= 2 && height >= 2) { "Grid topology requires width >= 2 and height >= 2" }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val current = index(x, y)
                // Horizontal link
                if (x + 1 < width) {
                    val right = index(x + 1, y)
                    network.connect(nodes[current].id, nodes[right].id, linkLatencyMs, linkLossRate)
                }
                // Vertical link
                if (y + 1 < height) {
                    val down = index(x, y + 1)
                    network.connect(nodes[current].id, nodes[down].id, linkLatencyMs, linkLossRate)
                }
            }
        }
    }

    fun index(x: Int, y: Int): Int = y * width + x

    fun getNodeAt(x: Int, y: Int): MeshNode = nodes[index(x, y)].node
    fun getNodeIdAt(x: Int, y: Int): NodeId = nodes[index(x, y)].id
}

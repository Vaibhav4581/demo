package mesh.sim.topology

import mesh.node.MeshNode
import mesh.node.NodeConfig
import mesh.protocol.NodeId
import mesh.sim.SimNetwork
import mesh.sim.SimTransport

data class SimNodeEntry(
    val index: Int,
    val id: NodeId,
    val transport: SimTransport,
    val node: MeshNode
)

/**
 * Creates a deterministic 8-byte [NodeId] for simulation node [index].
 */
fun simNodeId(index: Int): NodeId {
    val bytes = ByteArray(8)
    bytes[0] = ((index shr 24) and 0xFF).toByte()
    bytes[1] = ((index shr 16) and 0xFF).toByte()
    bytes[2] = ((index shr 8) and 0xFF).toByte()
    bytes[3] = (index and 0xFF).toByte()
    bytes[4] = 0xAA.toByte()
    bytes[5] = 0xBB.toByte()
    bytes[6] = 0xCC.toByte()
    bytes[7] = ((index + 1) and 0xFF).toByte()
    return NodeId(bytes)
}

/**
 * Common abstraction for network topologies.
 */
interface Topology {
    val network: SimNetwork
    val nodes: List<SimNodeEntry>

    fun getNode(index: Int): MeshNode = nodes[index].node
    fun getNodeId(index: Int): NodeId = nodes[index].id
    fun getTransport(index: Int): SimTransport = nodes[index].transport

    /**
     * Instantiates [count] simulation nodes in [network].
     */
    fun createNodes(
        count: Int,
        configFactory: (Int) -> NodeConfig = { NodeConfig(displayName = "Node-$it") }
    ): List<SimNodeEntry> {
        val list = mutableListOf<SimNodeEntry>()
        for (i in 0 until count) {
            val id = simNodeId(i)
            val transport = SimTransport(id, network)
            val config = configFactory(i)
            val node = MeshNode(
                nodeId = id,
                transport = transport,
                clock = network.clock,
                config = config
            )
            network.registerNode(id, transport, node)
            list.add(SimNodeEntry(i, id, transport, node))
        }
        return list
    }
}

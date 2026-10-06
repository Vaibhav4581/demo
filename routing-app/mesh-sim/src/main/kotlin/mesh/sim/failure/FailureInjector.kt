package mesh.sim.failure

import mesh.protocol.NodeId
import mesh.sim.SimNetwork

/**
 * Injects scheduled node and link failure events (kills, revivals, partitions, and healing).
 */
class FailureInjector(val network: SimNetwork) {

    fun killNode(nodeId: NodeId) {
        network.killNode(nodeId)
    }

    fun reviveNode(nodeId: NodeId) {
        network.reviveNode(nodeId)
    }

    fun scheduleNodeKill(nodeId: NodeId, atTimeMs: Long) {
        network.scheduleAt(atTimeMs, "Kill node $nodeId") {
            killNode(nodeId)
        }
    }

    fun scheduleNodeRevive(nodeId: NodeId, atTimeMs: Long) {
        network.scheduleAt(atTimeMs, "Revive node $nodeId") {
            reviveNode(nodeId)
        }
    }

    /**
     * Severs all links between [groupA] and [groupB], creating a network partition.
     */
    fun partition(groupA: Set<NodeId>, groupB: Set<NodeId>) {
        for (a in groupA) {
            for (b in groupB) {
                if (network.isConnected(a, b)) {
                    network.disconnect(a, b)
                }
            }
        }
    }

    /**
     * Restores severed links between [groupA] and [groupB], healing the partition.
     */
    fun heal(
        groupA: Set<NodeId>,
        groupB: Set<NodeId>,
        latencyMs: Long = 10L,
        lossRate: Double = 0.0
    ) {
        for (a in groupA) {
            for (b in groupB) {
                network.connect(a, b, latencyMs, lossRate)
            }
        }
    }

    fun schedulePartition(groupA: Set<NodeId>, groupB: Set<NodeId>, atTimeMs: Long) {
        network.scheduleAt(atTimeMs, "Partition network groups") {
            partition(groupA, groupB)
        }
    }

    fun scheduleHeal(
        groupA: Set<NodeId>,
        groupB: Set<NodeId>,
        atTimeMs: Long,
        latencyMs: Long = 10L,
        lossRate: Double = 0.0
    ) {
        network.scheduleAt(atTimeMs, "Heal network partition") {
            heal(groupA, groupB, latencyMs, lossRate)
        }
    }
}

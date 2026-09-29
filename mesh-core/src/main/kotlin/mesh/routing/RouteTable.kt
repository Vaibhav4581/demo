package mesh.routing

import mesh.protocol.NodeId
import java.util.concurrent.ConcurrentHashMap

/**
 * A learned distance-vector routing entry toward a destination.
 *
 * @property dest the final destination node ID
 * @property nextHop the direct 1-hop neighbour to forward packets through
 * @property cost the distance cost in hops
 * @property lastSeenMs the timestamp in epoch ms when this route was last observed or refreshed
 */
data class RouteEntry(
    val dest: NodeId,
    val nextHop: NodeId,
    val cost: Int,
    var lastSeenMs: Long
)

/**
 * Distance-vector routing table learned opportunistically from incoming network traffic.
 */
class RouteTable(val defaultRouteTtlMs: Long = DEFAULT_ROUTE_TTL_MS) {

    private val routes = ConcurrentHashMap<NodeId, RouteEntry>()

    /**
     * Learns or updates a route to [dest] via [via] neighbour with distance [cost].
     *
     * Adopts new route if:
     * - No route exists
     * - The route is from the same nextHop (cost refresh)
     * - The new route has lower cost (shorter path)
     * - The existing route has expired
     *
     * @return true if route table was modified or refreshed
     */
    @Synchronized
    fun learnRoute(
        dest: NodeId,
        via: NodeId,
        cost: Int,
        nowMs: Long,
        routeTtlMs: Long = defaultRouteTtlMs
    ): Boolean {
        val existing = routes[dest]
        if (existing == null) {
            routes[dest] = RouteEntry(dest, via, cost, nowMs)
            return true
        }

        val isExpired = nowMs - existing.lastSeenMs > routeTtlMs
        if (isExpired || existing.nextHop == via || cost < existing.cost) {
            routes[dest] = RouteEntry(dest, via, cost, nowMs)
            return true
        } else if (cost == existing.cost) {
            existing.lastSeenMs = nowMs
            return true
        }
        return false
    }

    /**
     * Retrieves an active, non-expired route toward [dest].
     */
    fun getRoute(dest: NodeId, nowMs: Long, routeTtlMs: Long = defaultRouteTtlMs): RouteEntry? {
        val entry = routes[dest] ?: return null
        if (nowMs - entry.lastSeenMs > routeTtlMs) {
            routes.remove(dest)
            return null
        }
        return entry
    }

    /**
     * Invalidates and removes all routes whose next hop is [nextHop].
     *
     * Called when a physical neighbour disconnects or fails link transmissions.
     * @return number of invalidated routes
     */
    @Synchronized
    fun invalidateRoutesThrough(nextHop: NodeId): Int {
        var count = 0
        val iterator = routes.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.value.nextHop == nextHop) {
                iterator.remove()
                count++
            }
        }
        return count
    }

    /**
     * Explicitly invalidates a single destination route (e.g. after a unicast failure).
     */
    @Synchronized
    fun invalidateRoute(dest: NodeId): Boolean {
        return routes.remove(dest) != null
    }

    /**
     * Returns all currently valid, non-expired routes.
     */
    fun getAllValidRoutes(nowMs: Long, routeTtlMs: Long = defaultRouteTtlMs): List<RouteEntry> {
        return routes.values.filter { nowMs - it.lastSeenMs <= routeTtlMs }
    }

    /**
     * Purges expired routes from the table.
     */
    @Synchronized
    fun purgeExpired(nowMs: Long, routeTtlMs: Long = defaultRouteTtlMs): Int {
        var count = 0
        val iterator = routes.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMs - entry.value.lastSeenMs > routeTtlMs) {
                iterator.remove()
                count++
            }
        }
        return count
    }

    @Synchronized
    fun clear() {
        routes.clear()
    }

    companion object {
        const val DEFAULT_ROUTE_TTL_MS = 30 * 60 * 1000L // 30 minutes
    }
}

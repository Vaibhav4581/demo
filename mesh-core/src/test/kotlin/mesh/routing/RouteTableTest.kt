package mesh.routing

import assertk.assertThat
import assertk.assertions.isEqualTo
import assertk.assertions.isNotNull
import assertk.assertions.isNull
import mesh.protocol.NodeId
import org.junit.jupiter.api.Test

class RouteTableTest {

    private val targetDest = NodeId(byteArrayOf(9, 9, 9, 9, 9, 9, 9, 9))
    private val nextHopB = NodeId(byteArrayOf(2, 2, 2, 2, 2, 2, 2, 2))
    private val nextHopC = NodeId(byteArrayOf(3, 3, 3, 3, 3, 3, 3, 3))

    @Test
    fun `learns route and prefers lower cost path`() {
        val table = RouteTable()
        val now = 1000L

        // Learned via B with cost 3
        table.learnRoute(dest = targetDest, via = nextHopB, cost = 3, nowMs = now)
        val route1 = table.getRoute(targetDest, now)
        assertThat(route1).isNotNull()
        assertThat(route1!!.nextHop).isEqualTo(nextHopB)
        assertThat(route1.cost).isEqualTo(3)

        // Worse route arrives via C with cost 4 -> should NOT replace
        table.learnRoute(dest = targetDest, via = nextHopC, cost = 4, nowMs = now + 100)
        val route2 = table.getRoute(targetDest, now + 100)
        assertThat(route2!!.nextHop).isEqualTo(nextHopB)
        assertThat(route2.cost).isEqualTo(3)

        // Shorter route arrives via C with cost 2 -> should replace
        table.learnRoute(dest = targetDest, via = nextHopC, cost = 2, nowMs = now + 200)
        val route3 = table.getRoute(targetDest, now + 200)
        assertThat(route3!!.nextHop).isEqualTo(nextHopC)
        assertThat(route3.cost).isEqualTo(2)
    }

    @Test
    fun `route expires after routeTtlMs`() {
        val ttl = 10_000L
        val table = RouteTable(defaultRouteTtlMs = ttl)
        val now = 5_000L

        table.learnRoute(dest = targetDest, via = nextHopB, cost = 2, nowMs = now)
        assertThat(table.getRoute(targetDest, now + 5_000L)).isNotNull()
        // Expired after 10s
        assertThat(table.getRoute(targetDest, now + 10_001L)).isNull()
    }

    @Test
    fun `invalidateRoutesThrough removes dependent routes`() {
        val table = RouteTable()
        val dest1 = NodeId(byteArrayOf(10, 0, 0, 0, 0, 0, 0, 0))
        val dest2 = NodeId(byteArrayOf(20, 0, 0, 0, 0, 0, 0, 0))

        table.learnRoute(dest = dest1, via = nextHopB, cost = 2, nowMs = 1000L)
        table.learnRoute(dest = dest2, via = nextHopC, cost = 1, nowMs = 1000L)

        val count = table.invalidateRoutesThrough(nextHopB)
        assertThat(count).isEqualTo(1)
        assertThat(table.getRoute(dest1, 1000L)).isNull()
        assertThat(table.getRoute(dest2, 1000L)).isNotNull()
    }
}

package org.mesh.emergency.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mesh.node.MeshNode
import mesh.protocol.NodeId
import mesh.routing.RouteEntry
import org.mesh.emergency.ui.theme.Cyan400
import org.mesh.emergency.ui.theme.Emerald500
import org.mesh.emergency.ui.theme.Purple400
import org.mesh.emergency.ui.theme.Slate400
import org.mesh.emergency.ui.theme.Slate700
import org.mesh.emergency.ui.theme.Slate800
import org.mesh.emergency.ui.theme.Slate900
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun MeshTopologyScreen(
    meshNode: MeshNode,
    connectedPeers: List<NodeId>,
    onOpenDirectChat: (NodeId) -> Unit,
    modifier: Modifier = Modifier
) {
    val directNeighbourInfos = remember(connectedPeers) {
        meshNode.router.neighbourTable.getAllNeighbours()
    }
    val routes = remember(connectedPeers) {
        meshNode.getKnownRoutes()
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // KPI Overview
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Slate900),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(text = "1-HOP PEERS", fontSize = 11.sp, color = Slate400, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${connectedPeers.size}",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black,
                            color = Emerald500
                        )
                        Text(text = "Direct links", fontSize = 10.sp, color = Slate400)
                    }
                }

                Card(
                    colors = CardDefaults.cardColors(containerColor = Slate900),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(text = "MULTI-HOP", fontSize = 11.sp, color = Slate400, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "${routes.size}",
                            fontSize = 22.sp,
                            fontWeight = FontWeight.Black,
                            color = Cyan400
                        )
                        Text(text = "Learned routes", fontSize = 10.sp, color = Slate400)
                    }
                }
            }
        }

        // Visual Topology Graph
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Slate900),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Visual Mesh Topology",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = Cyan400
                        )
                        Icon(
                            imageVector = Icons.Default.Hub,
                            contentDescription = "Topology Graph",
                            tint = Cyan400,
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    MeshVisualCanvas(
                        directNeighbours = connectedPeers,
                        routes = routes,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        LegendItem(color = Cyan400, label = "Local (You)")
                        LegendItem(color = Emerald500, label = "1-Hop Direct")
                        LegendItem(color = Purple400, label = "Multi-Hop")
                    }
                }
            }
        }

        // Section: Direct Neighbours (1-Hop)
        item {
            Text(
                text = "DIRECT 1-HOP NEIGHBOURS (${connectedPeers.size})",
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 1.sp,
                color = Emerald500
            )
        }

        if (connectedPeers.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Slate900),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No direct peers connected. Ensure Nearby Connections radio is active and nearby phones are within BLE / Wi-Fi range.",
                        fontSize = 12.sp,
                        color = Slate400,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(connectedPeers, key = { it.toHex() }) { peerId ->
                val neighbour = directNeighbourInfos.find { it.nodeId == peerId }
                DirectNeighbourCard(
                    peerId = peerId,
                    displayName = neighbour?.displayName,
                    lastSeenMs = neighbour?.lastSeenMs ?: 0L,
                    onChatClick = { onOpenDirectChat(peerId) }
                )
            }
        }

        // Section: Known Multi-Hop Routes
        item {
            Text(
                text = "KNOWN REACHABLE DESTINATIONS (${routes.size})",
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 1.sp,
                color = Cyan400
            )
        }

        if (routes.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Slate900),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No multi-hop routes learned yet. Distance-vector routes are discovered opportunistically as packets hop across nodes.",
                        fontSize = 12.sp,
                        color = Slate400,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        } else {
            items(routes, key = { "${it.dest.toHex()}-${it.nextHop.toHex()}" }) { route ->
                MultiHopRouteCard(
                    route = route,
                    onChatClick = { onOpenDirectChat(route.dest) }
                )
            }
        }
    }
}

@Composable
fun MeshVisualCanvas(
    directNeighbours: List<NodeId>,
    routes: List<RouteEntry>,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f

        // Draw local node at center
        val localRadius = 14f
        drawCircle(
            color = Cyan400,
            radius = localRadius,
            center = Offset(centerX, centerY)
        )

        // Draw 1-hop neighbours in an inner ring
        val directCount = directNeighbours.size
        val directDistance = size.height * 0.35f
        val directOffsets = mutableListOf<Offset>()

        directNeighbours.forEachIndexed { i, _ ->
            val angle = (2 * Math.PI * i / directCount.coerceAtLeast(1)) - Math.PI / 2
            val x = (centerX + directDistance * cos(angle)).toFloat()
            val y = (centerY + directDistance * sin(angle)).toFloat()
            val offset = Offset(x, y)
            directOffsets.add(offset)

            // Draw link line
            drawLine(
                color = Emerald500.copy(alpha = 0.6f),
                start = Offset(centerX, centerY),
                end = offset,
                strokeWidth = 2f
            )

            // Draw direct peer node
            drawCircle(
                color = Emerald500,
                radius = 10f,
                center = offset
            )
        }

        // Draw multi-hop routes in an outer arc
        val multiHopDestinations = routes.filter { r -> !directNeighbours.contains(r.dest) }
        val multiCount = multiHopDestinations.size
        val multiDistance = size.height * 0.46f

        multiHopDestinations.forEachIndexed { i, _ ->
            val angle = (2 * Math.PI * i / multiCount.coerceAtLeast(1)) + Math.PI / 4
            val x = (centerX + multiDistance * cos(angle)).toFloat()
            val y = (centerY + multiDistance * sin(angle)).toFloat()
            val offset = Offset(x, y)

            // Connect to nearest direct neighbour if possible
            val connectionStart = directOffsets.firstOrNull() ?: Offset(centerX, centerY)
            drawLine(
                color = Purple400.copy(alpha = 0.4f),
                start = connectionStart,
                end = offset,
                strokeWidth = 1.5f
            )

            // Draw multi-hop node
            drawCircle(
                color = Purple400,
                radius = 7f,
                center = offset
            )
        }
    }
}

@Composable
fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(color, CircleShape)
        )
        Spacer(modifier = Modifier.width(4.dp))
        Text(text = label, fontSize = 10.sp, color = Slate400)
    }
}

@Composable
fun DirectNeighbourCard(
    peerId: NodeId,
    displayName: String?,
    lastSeenMs: Long,
    onChatClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Slate900),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName ?: "Direct Peer",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "ID: ${peerId.toHex()}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = Slate400
                )
                Text(
                    text = "Link: P2P_CLUSTER (Active 1-hop link)",
                    fontSize = 10.sp,
                    color = Emerald500
                )
            }

            Button(
                onClick = onChatClick,
                colors = ButtonDefaults.buttonColors(containerColor = Emerald500, contentColor = Color.Black),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Chat,
                    contentDescription = "Chat",
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Chat", fontSize = 11.sp)
            }
        }
    }
}

@Composable
fun MultiHopRouteCard(
    route: RouteEntry,
    onChatClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Slate900),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Dest: ${route.dest.toHex().takeLast(6)}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF2E1A47), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "${route.cost} HOPS",
                            color = Purple400,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Text(
                    text = "Destination ID: ${route.dest.toHex()}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = Slate400
                )

                Text(
                    text = "Next Hop Gateway: ${route.nextHop.toHex()}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = Cyan400
                )
            }

            Button(
                onClick = onChatClick,
                colors = ButtonDefaults.buttonColors(containerColor = Cyan400, contentColor = Color.Black),
                shape = RoundedCornerShape(8.dp)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Chat,
                    contentDescription = "Chat",
                    modifier = Modifier.size(14.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text("Chat", fontSize = 11.sp)
            }
        }
    }
}

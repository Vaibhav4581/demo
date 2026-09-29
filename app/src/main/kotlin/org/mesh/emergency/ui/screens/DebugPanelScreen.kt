package org.mesh.emergency.ui.screens

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mesh.android.power.DutyCycleController
import mesh.android.transport.NearbyTransport
import mesh.android.transport.TransportEventType
import mesh.android.transport.TransportLogEvent
import mesh.node.MeshNode
import org.mesh.emergency.ui.components.DutyCycleBadge
import org.mesh.emergency.ui.theme.Amber500
import org.mesh.emergency.ui.theme.Cyan400
import org.mesh.emergency.ui.theme.Emerald500
import org.mesh.emergency.ui.theme.Purple400
import org.mesh.emergency.ui.theme.Rose500
import org.mesh.emergency.ui.theme.Slate400
import org.mesh.emergency.ui.theme.Slate800
import org.mesh.emergency.ui.theme.Slate900

@Composable
fun DebugPanelScreen(
    meshNode: MeshNode,
    nearbyTransport: NearbyTransport,
    dutyCycleController: DutyCycleController,
    isServiceRunning: Boolean,
    recentEvents: List<TransportLogEvent>,
    onToggleService: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val duplicatesDropped = meshNode.router.dedupManager.duplicatesDropped
    val retransmissions = meshNode.outbox.totalRetransmissions
    val queueLength = meshNode.outbox.getPendingCount()
    val dutyState = dutyCycleController.currentState
    val batteryPercent = dutyCycleController.batteryLevelProvider()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Section: Network Health Metrics (2x2 Grid)
        item {
            Text(
                text = "NETWORK ENGINE HEALTH",
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 1.sp,
                color = Cyan400
            )
            Spacer(modifier = Modifier.height(8.dp))

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricCard(
                        title = "DUPLICATES DROPPED",
                        value = "$duplicatesDropped",
                        subtitle = "Anti-storm dedup filter",
                        color = Amber500,
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        title = "RETRANSMISSIONS",
                        value = "$retransmissions",
                        subtitle = "Outbox retry backoffs",
                        color = Purple400,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MetricCard(
                        title = "OUTBOX QUEUE",
                        value = "$queueLength",
                        subtitle = "Pending packets",
                        color = Cyan400,
                        modifier = Modifier.weight(1f)
                    )
                    MetricCard(
                        title = "BATTERY LEVEL",
                        value = "$batteryPercent%",
                        subtitle = "Adaptive threshold",
                        color = Emerald500,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // Section: Duty Cycle & Power FSM
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
                            text = "Duty-Cycle State Machine",
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        DutyCycleBadge(state = dutyState)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "FSM adapts radio advertising and scanning intervals to conserve battery while maintaining high packet delivery reliability in disaster zones.",
                        fontSize = 12.sp,
                        color = Slate400
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = { onToggleService(true) },
                            enabled = !isServiceRunning,
                            colors = ButtonDefaults.buttonColors(containerColor = Emerald500, contentColor = Color.Black),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Start Service", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = { onToggleService(false) },
                            enabled = isServiceRunning,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Stop Service", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Section: Manual Diagnostics Actions
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Slate900),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(
                        text = "Diagnostics & Maintenance",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                meshNode.sendHello()
                                Toast.makeText(context, "HELLO broadcasted to direct peers", Toast.LENGTH_SHORT).show()
                                onRefresh()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Cyan400, contentColor = Color.Black),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Force HELLO", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                val purged = meshNode.messageStore.purgeExpired(System.currentTimeMillis())
                                Toast.makeText(context, "Purged $purged expired items", Toast.LENGTH_SHORT).show()
                                onRefresh()
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Purge Expired", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // Section: Link-Layer Event Log
        item {
            Text(
                text = "LINK LAYER LOG (${recentEvents.size})",
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 1.sp,
                color = Slate400
            )
        }

        if (recentEvents.isEmpty()) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Slate900),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "No transport link events logged yet.",
                        fontSize = 12.sp,
                        color = Slate400,
                        modifier = Modifier.padding(14.dp)
                    )
                }
            }
        } else {
            items(recentEvents.take(25)) { event ->
                LinkEventRow(event = event)
            }
        }
    }
}

@Composable
fun MetricCard(
    title: String,
    value: String,
    subtitle: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Slate900),
        shape = RoundedCornerShape(10.dp),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text = title, fontSize = 10.sp, fontWeight = FontWeight.Bold, color = Slate400)
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = value, fontSize = 22.sp, fontWeight = FontWeight.Black, color = color)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = subtitle, fontSize = 10.sp, color = Slate400)
        }
    }
}

@Composable
fun LinkEventRow(event: TransportLogEvent) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Slate900),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = event.eventType.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = when (event.eventType) {
                        TransportEventType.PEER_CONNECTED,
                        TransportEventType.PACKET_RECEIVED,
                        TransportEventType.PACKET_SENT -> Emerald500
                        TransportEventType.CONNECTION_FAILED,
                        TransportEventType.PACKET_REJECTED_OVERSIZE -> Rose500
                        else -> Slate400
                    }
                )
                Text(
                    text = "${event.timestampMs % 100000}ms",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = Slate400
                )
            }

            val summary = event.packetSummary ?: event.details ?: event.peerIdHex
            if (summary != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = summary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

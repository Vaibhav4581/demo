package org.mesh.emergency

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mesh.android.identity.NodeIdentityManager
import mesh.android.permission.MeshPermissions
import mesh.android.transport.NearbyTransport
import mesh.android.transport.TransportEventType
import mesh.android.transport.TransportLogEvent
import mesh.protocol.NodeId
import mesh.protocol.Packet
import mesh.protocol.PacketFactory
import mesh.transport.TransportListener
import org.koin.android.ext.android.inject

class MainActivity : ComponentActivity() {

    private val identityManager: NodeIdentityManager by inject()
    private val nearbyTransport: NearbyTransport by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MeshScreen(
                        identityManager = identityManager,
                        nearbyTransport = nearbyTransport
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Do not stop nearbyTransport here if MeshService is running in the background
        if (!mesh.android.service.MeshService.isServiceRunning) {
            nearbyTransport.stop()
        }
    }
}

@Composable
fun MeshScreen(
    identityManager: NodeIdentityManager,
    nearbyTransport: NearbyTransport
) {
    val context = LocalContext.current
    var hasPermissions by remember { mutableStateOf(MeshPermissions.hasAllPermissions(context)) }
    var isRadioActive by remember { mutableStateOf(mesh.android.service.MeshService.isServiceRunning || nearbyTransport.isRunning) }

    val connectedPeers = remember { mutableStateListOf<NodeId>() }
    val recentEvents = remember { mutableStateListOf<TransportLogEvent>() }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasPermissions = results.values.all { it }
        if (hasPermissions) {
            Toast.makeText(context, "Permissions granted. Ready to start radio.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Some permissions were denied. Mesh radio may fail.", Toast.LENGTH_LONG).show()
        }
    }

    DisposableEffect(nearbyTransport) {
        val listener = object : TransportListener {
            override fun onPeerConnected(peer: NodeId) {
                if (!connectedPeers.contains(peer)) {
                    connectedPeers.add(peer)
                }
                recentEvents.clear()
                recentEvents.addAll(nearbyTransport.logger.getRecentEvents().takeLast(20).reversed())
            }

            override fun onPeerDisconnected(peer: NodeId) {
                connectedPeers.remove(peer)
                recentEvents.clear()
                recentEvents.addAll(nearbyTransport.logger.getRecentEvents().takeLast(20).reversed())
            }

            override fun onPacketReceived(fromPeer: NodeId, packet: Packet) {
                recentEvents.clear()
                recentEvents.addAll(nearbyTransport.logger.getRecentEvents().takeLast(20).reversed())
            }
        }

        nearbyTransport.registerListener(listener)
        connectedPeers.clear()
        connectedPeers.addAll(nearbyTransport.getConnectedPeers())
        recentEvents.addAll(nearbyTransport.logger.getRecentEvents().takeLast(20).reversed())

        onDispose {
            nearbyTransport.unregisterListener(listener)
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Emergency Mesh Network",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Phase 5: Nearby Connections Transport",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.secondary
            )
        }

        // Onboarding card for missing permissions
        if (!hasPermissions) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Permissions Required for Offline Radio",
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "To form an ad-hoc mesh network without cellular or Wi-Fi infrastructure, the app requires Bluetooth scan/advertise and local Nearby Wi-Fi permissions.",
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                permissionLauncher.launch(MeshPermissions.getRequiredPermissions())
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                        ) {
                            Text("Grant Mesh Permissions")
                        }
                    }
                }
            }
        }

        // Node Identity Card
        item {
            Card(
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Local Node Identity",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(text = "Name: ${identityManager.displayName}", fontSize = 15.sp)
                    Text(
                        text = "Node ID: ${identityManager.nodeId.toHex()}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Encrypted Local Store: 256-bit AES (SQLCipher)",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }

        // Transport Radio Controller Card
        item {
            Card(
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "P2P Cluster Radio",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 16.sp
                        )
                        Box(
                            modifier = Modifier
                                .background(
                                    color = if (isRadioActive) Color(0xFF2E7D32) else Color.Gray,
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Text(
                                text = if (isRadioActive) "ACTIVE" else "STOPPED",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Strategy: Google Nearby Connections P2P_CLUSTER (BLE + Wi-Fi Direct)",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.outline
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                if (hasPermissions) {
                                    mesh.android.service.MeshService.startService(context)
                                    isRadioActive = true
                                    recentEvents.clear()
                                    recentEvents.addAll(nearbyTransport.logger.getRecentEvents().takeLast(20).reversed())
                                } else {
                                    permissionLauncher.launch(MeshPermissions.getRequiredPermissions())
                                }
                            },
                            enabled = !isRadioActive
                        ) {
                            Text("Start Service")
                        }

                        OutlinedButton(
                            onClick = {
                                mesh.android.service.MeshService.stopService(context)
                                isRadioActive = false
                                connectedPeers.clear()
                                recentEvents.clear()
                                recentEvents.addAll(nearbyTransport.logger.getRecentEvents().takeLast(20).reversed())
                            },
                            enabled = isRadioActive
                        ) {
                            Text("Stop Service")
                        }
                    }
                }
            }
        }

        // Connected Direct Peers Card
        item {
            Card(
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Connected Peers (${connectedPeers.size})",
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 15.sp
                        )

                        Button(
                            onClick = {
                                val testPacket = PacketFactory.createData(
                                    origin = identityManager.nodeId,
                                    dest = null, // Broadcast
                                    payload = "PING from ${identityManager.displayName}".toByteArray()
                                )
                                val count = nearbyTransport.broadcast(testPacket)
                                Toast.makeText(context, "Sent test packet to $count peers", Toast.LENGTH_SHORT).show()
                                recentEvents.clear()
                                recentEvents.addAll(nearbyTransport.logger.getRecentEvents().takeLast(20).reversed())
                            },
                            enabled = isRadioActive && connectedPeers.isNotEmpty()
                        ) {
                            Text("Send Test Ping")
                        }
                    }

                    if (connectedPeers.isEmpty()) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (isRadioActive) "Searching for nearby mesh nodes..." else "Radio is stopped.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    } else {
                        Spacer(modifier = Modifier.height(8.dp))
                        connectedPeers.forEach { peer ->
                            Text(
                                text = "• Peer: ${peer.toHex()}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            }
        }

        // Transport Link Log Events (Diagnostics)
        item {
            Card(
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Link Layer Event Log",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    if (recentEvents.isEmpty()) {
                        Text(
                            text = "No events logged yet.",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.outline
                        )
                    } else {
                        recentEvents.take(15).forEach { event ->
                            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = event.eventType.name,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        color = when (event.eventType) {
                                            TransportEventType.PEER_CONNECTED,
                                            TransportEventType.PACKET_RECEIVED,
                                            TransportEventType.PACKET_SENT -> MaterialTheme.colorScheme.primary
                                            TransportEventType.CONNECTION_FAILED,
                                            TransportEventType.PACKET_REJECTED_OVERSIZE -> MaterialTheme.colorScheme.error
                                            else -> MaterialTheme.colorScheme.secondary
                                        }
                                    )
                                    Text(
                                        text = "${event.timestampMs % 100000}ms",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.outline
                                    )
                                }
                                val summary = event.packetSummary ?: event.details ?: event.peerIdHex
                                if (summary != null) {
                                    Text(
                                        text = summary,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 2.dp))
                        }
                    }
                }
            }
        }
    }
}

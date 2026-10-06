package org.mesh.emergency.ui.screens

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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mesh.node.MeshNode
import mesh.protocol.NodeId
import mesh.storage.MessageRecord
import org.mesh.emergency.ui.components.DeliveryStatusChip
import org.mesh.emergency.ui.theme.Amber500
import org.mesh.emergency.ui.theme.Cyan400
import org.mesh.emergency.ui.theme.Emerald500
import org.mesh.emergency.ui.theme.Rose500
import org.mesh.emergency.ui.theme.Slate400
import org.mesh.emergency.ui.theme.Slate800
import org.mesh.emergency.ui.theme.Slate900

data class PeerConversationSummary(
    val peerId: NodeId,
    val displayName: String?,
    val isDirectNeighbour: Boolean,
    val multiHopCost: Int?,
    val lastMessage: MessageRecord?
)

@Composable
fun InboxScreen(
    meshNode: MeshNode,
    connectedPeers: List<NodeId>,
    allMessages: List<MessageRecord>,
    onOpenBroadcast: () -> Unit,
    onOpenDirectChat: (NodeId) -> Unit,
    modifier: Modifier = Modifier
) {
    var showNewChatDialog by remember { mutableStateOf(false) }

    // Aggregate known peers from neighbours, routes, and past messages
    val directNeighbourSet = connectedPeers.toSet()
    val routes = meshNode.getKnownRoutes()
    val routeCostMap = routes.associate { it.dest to it.cost }

    val peerIds = remember(allMessages, connectedPeers, routes) {
        val set = mutableSetOf<NodeId>()
        set.addAll(connectedPeers)
        routes.forEach { set.add(it.dest) }
        allMessages.forEach { msg ->
            val destination = msg.dest
            if (destination != null) {
                if (msg.isIncoming) set.add(msg.origin) else set.add(destination)
            }
        }
        set.filter { it != meshNode.nodeId }
    }

    val peerSummaries = remember(peerIds, allMessages, directNeighbourSet, routeCostMap) {
        peerIds.map { peer ->
            val peerMessages = allMessages.filter {
                (it.dest == peer && !it.isIncoming) || (it.origin == peer && it.isIncoming)
            }
            val lastMsg = peerMessages.maxByOrNull { it.createdAtMs }
            val neighbourInfo = meshNode.router.neighbourTable.getNeighbour(peer)
            PeerConversationSummary(
                peerId = peer,
                displayName = neighbourInfo?.displayName,
                isDirectNeighbour = directNeighbourSet.contains(peer),
                multiHopCost = routeCostMap[peer],
                lastMessage = lastMsg
            )
        }.sortedByDescending { it.lastMessage?.createdAtMs ?: 0L }
    }

    val broadcastMessages = remember(allMessages) {
        allMessages.filter { it.dest == null }
    }
    val latestBroadcast = broadcastMessages.maxByOrNull { it.createdAtMs }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Color.Transparent,
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showNewChatDialog = true },
                containerColor = Cyan400,
                contentColor = Color.Black
            ) {
                Icon(imageVector = Icons.Default.Add, contentDescription = "New Direct Chat")
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 🚨 Emergency Broadcast Channel Card
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF261217)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenBroadcast() }
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(44.dp)
                                .background(Rose500, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Campaign,
                                contentDescription = "Emergency Broadcast",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Emergency Broadcast Channel",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 15.sp,
                                    color = Color.White
                                )
                                Text(
                                    text = "ALL NODES",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Black,
                                    color = Rose500
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            Text(
                                text = latestBroadcast?.let {
                                    "${if (it.isIncoming) "Incoming: " else "You: "}${String(it.payload)}"
                                } ?: "No emergency broadcasts yet. Tap to transmit to all nodes.",
                                fontSize = 13.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = Slate400
                            )
                        }
                    }
                }
            }

            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "DIRECT 1:1 CONVERSATIONS (${peerSummaries.size})",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            if (peerSummaries.isEmpty()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Slate900),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "No Direct Chats Yet",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 15.sp,
                                color = Slate400
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Nearby nodes discovered over Bluetooth/Wi-Fi will appear here automatically, or tap '+' to start a direct message.",
                                fontSize = 12.sp,
                                color = Slate400,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            } else {
                items(peerSummaries, key = { it.peerId.toHex() }) { summary ->
                    PeerConversationCard(
                        summary = summary,
                        onClick = { onOpenDirectChat(summary.peerId) }
                    )
                }
            }
        }
    }

    if (showNewChatDialog) {
        NewChatDialog(
            knownPeers = peerIds,
            onPeerSelected = { peer ->
                showNewChatDialog = false
                onOpenDirectChat(peer)
            },
            onDismiss = { showNewChatDialog = false }
        )
    }
}

@Composable
fun PeerConversationCard(
    summary: PeerConversationSummary,
    onClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Slate900),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .background(Slate800, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = "Peer Avatar",
                    tint = Cyan400,
                    modifier = Modifier.size(22.dp)
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = summary.displayName ?: "Node ${summary.peerId.toHex().takeLast(6)}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    // Reachability badge
                    when {
                        summary.isDirectNeighbour -> {
                            Text(
                                text = "⚡ 1-HOP DIRECT",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Emerald500
                            )
                        }
                        summary.multiHopCost != null -> {
                            Text(
                                text = "🔀 ${summary.multiHopCost} HOPS",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Cyan400
                            )
                        }
                        else -> {
                            Text(
                                text = "STORE-FORWARD",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Medium,
                                color = Slate400
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = "ID: ${summary.peerId.toHex()}",
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    color = Slate400
                )

                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = summary.lastMessage?.let {
                            "${if (it.isIncoming) "Them: " else "You: "}${String(it.payload)}"
                        } ?: "Tap to start conversation",
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )

                    summary.lastMessage?.let {
                        Spacer(modifier = Modifier.width(8.dp))
                        DeliveryStatusChip(state = it.deliveryState)
                    }
                }
            }
        }
    }
}

@Composable
fun NewChatDialog(
    knownPeers: List<NodeId>,
    onPeerSelected: (NodeId) -> Unit,
    onDismiss: () -> Unit
) {
    var hexInput by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Slate900,
        title = { Text("Start Direct Chat", fontWeight = FontWeight.Bold, color = Cyan400) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Enter the 16-hex-character Node ID of the destination device:",
                    fontSize = 13.sp,
                    color = Slate400
                )

                OutlinedTextField(
                    value = hexInput,
                    onValueChange = {
                        hexInput = it.filter { char -> char.isLetterOrDigit() }.take(16)
                        errorMessage = null
                    },
                    label = { Text("Node ID (16 Hex Digits)") },
                    singleLine = true,
                    isError = errorMessage != null,
                    modifier = Modifier.fillMaxWidth()
                )

                errorMessage?.let {
                    Text(text = it, color = Rose500, fontSize = 11.sp)
                }

                if (knownPeers.isNotEmpty()) {
                    Text(
                        text = "Or choose a discovered node:",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.secondary
                    )
                    knownPeers.take(5).forEach { peer ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Slate800),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPeerSelected(peer) }
                        ) {
                            Text(
                                text = peer.toHex(),
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = hexInput.trim()
                    if (trimmed.length != 16) {
                        errorMessage = "Node ID must be exactly 16 hexadecimal characters (8 bytes)."
                        return@Button
                    }
                    try {
                        val bytes = trimmed.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        onPeerSelected(NodeId(bytes))
                    } catch (_: Exception) {
                        errorMessage = "Invalid hexadecimal string."
                    }
                }
            ) {
                Text("Open Chat")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

package org.mesh.emergency.ui.screens

import android.widget.Toast
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import mesh.node.MeshNode
import mesh.protocol.NodeId
import mesh.storage.MessageRecord
import org.mesh.emergency.ui.components.DeliveryStatusChip
import org.mesh.emergency.ui.components.HopCountChip
import org.mesh.emergency.ui.theme.Cyan400
import org.mesh.emergency.ui.theme.Emerald500
import org.mesh.emergency.ui.theme.Rose500
import org.mesh.emergency.ui.theme.Slate400
import org.mesh.emergency.ui.theme.Slate800
import org.mesh.emergency.ui.theme.Slate900
import org.mesh.emergency.ui.theme.Slate950
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationScreen(
    peerId: NodeId?, // null = Broadcast
    meshNode: MeshNode,
    allMessages: List<MessageRecord>,
    onBack: () -> Unit,
    onMessageSent: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }

    val isBroadcast = peerId == null
    val conversationMessages = remember(allMessages, peerId) {
        if (isBroadcast) {
            allMessages.filter { it.dest == null }.sortedBy { it.createdAtMs }
        } else {
            allMessages.filter {
                (it.dest == peerId && !it.isIncoming) || (it.origin == peerId && it.isIncoming)
            }.sortedBy { it.createdAtMs }
        }
    }

    val peerNeighbourInfo = remember(peerId) {
        peerId?.let { meshNode.router.neighbourTable.getNeighbour(it) }
    }
    val peerRoute = remember(peerId) {
        peerId?.let { meshNode.getKnownRoutes().find { r -> r.dest == it } }
    }
    val isDirect = remember(peerId) {
        peerId != null && meshNode.getDirectNeighbours().contains(peerId)
    }

    val title = when {
        isBroadcast -> "Emergency Broadcast"
        peerNeighbourInfo?.displayName != null -> peerNeighbourInfo.displayName!!
        else -> "Peer: ${peerId?.toHex()?.takeLast(8)}"
    }

    val subtitle = when {
        isBroadcast -> "Transmitting unencrypted to ALL nodes in range"
        isDirect -> "Direct Link (1 Hop away)"
        peerRoute != null -> "Multi-Hop Path (${peerRoute.cost} hops via ${peerRoute.nextHop.toHex().takeLast(4)})"
        else -> "Offline Store-and-Forward (Will relay when path discovered)"
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = Slate950,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = title,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = if (isBroadcast) Rose500 else Cyan400
                        )
                        Text(
                            text = subtitle,
                            fontSize = 11.sp,
                            color = Slate400
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Slate900)
            )
        },
        bottomBar = {
            Surface(
                color = Slate900,
                tonalElevation = 8.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        placeholder = {
                            Text(
                                text = if (isBroadcast) "Broadcast emergency message..." else "Message direct peer...",
                                fontSize = 13.sp,
                                color = Slate400
                            )
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = Slate800,
                            unfocusedContainerColor = Slate800,
                            focusedBorderColor = Cyan400,
                            unfocusedBorderColor = Color.Transparent,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.weight(1f),
                        maxLines = 4
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    IconButton(
                        onClick = {
                            val trimmed = inputText.trim()
                            if (trimmed.isEmpty()) return@IconButton

                            try {
                                if (isBroadcast) {
                                    meshNode.broadcast(trimmed.toByteArray())
                                    Toast.makeText(context, "Broadcast queued in mesh outbox", Toast.LENGTH_SHORT).show()
                                } else {
                                    meshNode.send(peerId!!, trimmed.toByteArray())
                                    Toast.makeText(context, "Direct message queued", Toast.LENGTH_SHORT).show()
                                }
                                inputText = ""
                                onMessageSent()
                            } catch (e: Exception) {
                                Toast.makeText(context, "Error sending: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        },
                        modifier = Modifier
                            .size(44.dp)
                            .background(
                                color = if (inputText.trim().isNotEmpty()) (if (isBroadcast) Rose500 else Cyan400) else Slate800,
                                shape = CircleShape
                            )
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Send Message",
                            tint = if (inputText.trim().isNotEmpty()) Color.Black else Slate400,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        if (conversationMessages.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (isBroadcast) "No Emergency Broadcasts" else "No Messages Yet",
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = Slate400
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (isBroadcast)
                            "Broadcasts propagate to all reachable nodes across multiple hops."
                        else
                            "Messages to ${peerId?.toHex()} are end-to-end delivered and acknowledged.",
                        fontSize = 12.sp,
                        color = Slate400,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(conversationMessages, key = { it.msgId.joinToString("") { b -> "%02x".format(b) } }) { msg ->
                    MessageBubble(message = msg, isBroadcastChannel = isBroadcast)
                }
            }
        }
    }
}

@Composable
fun MessageBubble(
    message: MessageRecord,
    isBroadcastChannel: Boolean
) {
    val isOutgoing = !message.isIncoming
    val timeFormatter = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
    val formattedTime = remember(message.createdAtMs) { timeFormatter.format(Date(message.createdAtMs)) }

    val bubbleColor = when {
        isOutgoing -> Color(0xFF0F3E50) // Teal/Cyan container for outgoing
        isBroadcastChannel -> Color(0xFF2E1720) // Deep crimson tint for incoming broadcast
        else -> Slate800 // Slate container for incoming direct
    }

    val horizontalAlignment = if (isOutgoing) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = horizontalAlignment
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = bubbleColor),
            shape = RoundedCornerShape(
                topStart = 14.dp,
                topEnd = 14.dp,
                bottomStart = if (isOutgoing) 14.dp else 2.dp,
                bottomEnd = if (isOutgoing) 2.dp else 14.dp
            ),
            modifier = Modifier.fillMaxWidth(0.85f)
        ) {
            Column(modifier = Modifier.padding(10.dp)) {
                if (message.isIncoming) {
                    Text(
                        text = "From: ${message.origin.toHex()}",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        color = Cyan400
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }

                Text(
                    text = String(message.payload),
                    fontSize = 14.sp,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(6.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Per-message status chip (QUEUED, SENT, RELAYED, DELIVERED, EXPIRED)
                        DeliveryStatusChip(state = message.deliveryState)

                        // Hop count badge
                        HopCountChip(
                            hopCount = message.hopCount,
                            isBroadcast = message.dest == null
                        )
                    }

                    Text(
                        text = formattedTime,
                        fontSize = 10.sp,
                        color = Slate400
                    )
                }
            }
        }
    }
}

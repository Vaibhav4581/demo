package org.mesh.emergency

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import mesh.android.identity.NodeIdentityManager
import mesh.android.permission.MeshPermissions
import mesh.android.power.DutyCycleController
import mesh.android.service.MeshService
import mesh.android.transport.NearbyTransport
import mesh.android.transport.TransportLogEvent
import mesh.node.MeshNode
import mesh.protocol.NodeId
import mesh.protocol.Packet
import mesh.storage.MessageRecord
import mesh.transport.TransportListener
import org.koin.android.ext.android.inject
import org.mesh.emergency.ui.components.HeaderBar
import org.mesh.emergency.ui.components.OnboardingDialog
import org.mesh.emergency.ui.screens.ConversationScreen
import org.mesh.emergency.ui.screens.DebugPanelScreen
import org.mesh.emergency.ui.screens.InboxScreen
import org.mesh.emergency.ui.screens.MeshTopologyScreen
import org.mesh.emergency.ui.theme.Cyan400
import org.mesh.emergency.ui.theme.MeshTheme
import org.mesh.emergency.ui.theme.Rose500
import org.mesh.emergency.ui.theme.Slate400
import org.mesh.emergency.ui.theme.Slate800
import org.mesh.emergency.ui.theme.Slate900
import org.mesh.emergency.ui.theme.Slate950

enum class MainNavTab {
    INBOX,
    TOPOLOGY,
    DEBUG
}

sealed class NavigationTarget {
    object Main : NavigationTarget()
    object BroadcastConversation : NavigationTarget()
    data class DirectConversation(val peerId: NodeId) : NavigationTarget()
}

class MainActivity : ComponentActivity() {

    private val identityManager: NodeIdentityManager by inject()
    private val nearbyTransport: NearbyTransport by inject()
    private val meshNode: MeshNode by inject()
    private val dutyCycleController: DutyCycleController by inject()
    private val trafficGenerator: mesh.android.testmode.TrafficGenerator by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MeshTheme {
                MainAppContent(
                    identityManager = identityManager,
                    nearbyTransport = nearbyTransport,
                    meshNode = meshNode,
                    dutyCycleController = dutyCycleController,
                    trafficGenerator = trafficGenerator
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Do not stop nearbyTransport here if MeshService is running in the background
        if (!MeshService.isServiceRunning) {
            nearbyTransport.stop()
        }
    }
}

@Composable
fun MainAppContent(
    identityManager: NodeIdentityManager,
    nearbyTransport: NearbyTransport,
    meshNode: MeshNode,
    dutyCycleController: DutyCycleController,
    trafficGenerator: mesh.android.testmode.TrafficGenerator? = null
) {
    val context = LocalContext.current
    var hasPermissions by remember { mutableStateOf(MeshPermissions.hasAllPermissions(context)) }
    var isServiceRunning by remember { mutableStateOf(MeshService.isServiceRunning || nearbyTransport.isRunning) }

    var selectedTab by remember { mutableStateOf(MainNavTab.INBOX) }
    var currentNavTarget by remember { mutableStateOf<NavigationTarget>(NavigationTarget.Main) }
    var showProfileDialog by remember { mutableStateOf(false) }

    val connectedPeers = remember { mutableStateListOf<NodeId>() }
    val allMessages = remember { mutableStateListOf<MessageRecord>() }
    val recentEvents = remember { mutableStateListOf<TransportLogEvent>() }

    fun refreshState() {
        try {
            connectedPeers.clear()
            connectedPeers.addAll(nearbyTransport.getConnectedPeers())
            allMessages.clear()
            allMessages.addAll(meshNode.messageStore.getAllMessages())
            recentEvents.clear()
            recentEvents.addAll(nearbyTransport.logger.getRecentEvents().takeLast(25).reversed())
        } catch (e: Throwable) {
            android.util.Log.e("MainActivity", "Error refreshing state", e)
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        hasPermissions = results.values.all { it }
        if (hasPermissions) {
            Toast.makeText(context, "Permissions granted.", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Some permissions were denied.", Toast.LENGTH_LONG).show()
        }
    }

    DisposableEffect(nearbyTransport) {
        val listener = object : TransportListener {
            override fun onPeerConnected(peer: NodeId) {
                refreshState()
            }

            override fun onPeerDisconnected(peer: NodeId) {
                refreshState()
            }

            override fun onPacketReceived(fromPeer: NodeId, packet: Packet) {
                refreshState()
            }
        }

        nearbyTransport.registerListener(listener)
        meshNode.onMessageReceived { _, _, _ -> refreshState() }
        meshNode.onDelivered { _, _ -> refreshState() }

        refreshState()

        onDispose {
            nearbyTransport.unregisterListener(listener)
        }
    }

    when (val target = currentNavTarget) {
        is NavigationTarget.BroadcastConversation -> {
            ConversationScreen(
                peerId = null,
                meshNode = meshNode,
                allMessages = allMessages,
                onBack = { currentNavTarget = NavigationTarget.Main },
                onMessageSent = { refreshState() }
            )
        }

        is NavigationTarget.DirectConversation -> {
            ConversationScreen(
                peerId = target.peerId,
                meshNode = meshNode,
                allMessages = allMessages,
                onBack = { currentNavTarget = NavigationTarget.Main },
                onMessageSent = { refreshState() }
            )
        }

        NavigationTarget.Main -> {
            Scaffold(
                containerColor = Slate950,
                topBar = {
                    Column {
                        HeaderBar(
                            identityManager = identityManager,
                            isRadioActive = isServiceRunning,
                            onEditProfileClick = { showProfileDialog = true }
                        )

                        if (!hasPermissions) {
                            PermissionsBanner(
                                onRequestPermissions = {
                                    permissionLauncher.launch(MeshPermissions.getRequiredPermissions())
                                }
                            )
                        }
                    }
                },
                bottomBar = {
                    NavigationBar(
                        containerColor = Slate900,
                        tonalElevation = 6.dp
                    ) {
                        NavigationBarItem(
                            selected = selectedTab == MainNavTab.INBOX,
                            onClick = { selectedTab = MainNavTab.INBOX },
                            icon = { Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Inbox") },
                            label = { Text("Inbox") },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color.Black,
                                selectedTextColor = Cyan400,
                                indicatorColor = Cyan400,
                                unselectedIconColor = Slate400,
                                unselectedTextColor = Slate400
                            )
                        )

                        NavigationBarItem(
                            selected = selectedTab == MainNavTab.TOPOLOGY,
                            onClick = { selectedTab = MainNavTab.TOPOLOGY },
                            icon = { Icon(Icons.Default.Hub, contentDescription = "Topology") },
                            label = { Text("Mesh") },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color.Black,
                                selectedTextColor = Cyan400,
                                indicatorColor = Cyan400,
                                unselectedIconColor = Slate400,
                                unselectedTextColor = Slate400
                            )
                        )

                        NavigationBarItem(
                            selected = selectedTab == MainNavTab.DEBUG,
                            onClick = { selectedTab = MainNavTab.DEBUG },
                            icon = { Icon(Icons.Default.BugReport, contentDescription = "Debug") },
                            label = { Text("Debug") },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color.Black,
                                selectedTextColor = Cyan400,
                                indicatorColor = Cyan400,
                                unselectedIconColor = Slate400,
                                unselectedTextColor = Slate400
                            )
                        )
                    }
                }
            ) { padding ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    when (selectedTab) {
                        MainNavTab.INBOX -> {
                            InboxScreen(
                                meshNode = meshNode,
                                connectedPeers = connectedPeers,
                                allMessages = allMessages,
                                onOpenBroadcast = {
                                    currentNavTarget = NavigationTarget.BroadcastConversation
                                },
                                onOpenDirectChat = { peer ->
                                    currentNavTarget = NavigationTarget.DirectConversation(peer)
                                }
                            )
                        }

                        MainNavTab.TOPOLOGY -> {
                            MeshTopologyScreen(
                                meshNode = meshNode,
                                connectedPeers = connectedPeers,
                                onOpenDirectChat = { peer ->
                                    currentNavTarget = NavigationTarget.DirectConversation(peer)
                                }
                            )
                        }

                        MainNavTab.DEBUG -> {
                            DebugPanelScreen(
                                meshNode = meshNode,
                                nearbyTransport = nearbyTransport,
                                dutyCycleController = dutyCycleController,
                                isServiceRunning = isServiceRunning,
                                recentEvents = recentEvents,
                                trafficGenerator = trafficGenerator,
                                onToggleService = { start ->
                                    if (start) {
                                        if (hasPermissions) {
                                            MeshService.startService(context)
                                            isServiceRunning = true
                                        } else {
                                            permissionLauncher.launch(MeshPermissions.getRequiredPermissions())
                                        }
                                    } else {
                                        MeshService.stopService(context)
                                        isServiceRunning = false
                                    }
                                    refreshState()
                                },
                                onRefresh = { refreshState() }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showProfileDialog) {
        OnboardingDialog(
            identityManager = identityManager,
            hasPermissions = hasPermissions,
            onRequestPermissions = {
                permissionLauncher.launch(MeshPermissions.getRequiredPermissions())
            },
            onDismiss = { showProfileDialog = false }
        )
    }
}

@Composable
fun PermissionsBanner(onRequestPermissions: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF3B151E))
            .clickable { onRequestPermissions() }
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = "Warning",
                tint = Rose500,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Missing permissions for offline mesh radio. Tap to grant.",
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                color = Rose500
            )
        }
    }
}

package mesh.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import mesh.android.power.DutyCycleController
import mesh.android.power.DutyCycleListener
import mesh.android.power.DutyCycleState
import mesh.android.transport.NearbyTransport
import mesh.node.MeshNode
import mesh.protocol.NodeId
import mesh.protocol.Packet
import mesh.transport.TransportListener
import org.koin.android.ext.android.inject

/**
 * Foreground service that owns and sustains the [MeshNode], [NearbyTransport], and [DutyCycleController].
 * Ensures continuous background packet relaying even when the phone screen is turned off.
 */
class MeshService : Service() {

    companion object {
        const val NOTIFICATION_ID = 9001
        const val CHANNEL_ID = "mesh_service_channel"
        const val CHANNEL_NAME = "Emergency Mesh Relay"

        const val ACTION_START = "org.mesh.action.START_SERVICE"
        const val ACTION_STOP = "org.mesh.action.STOP_SERVICE"

        @Volatile
        var isServiceRunning: Boolean = false
            private set

        fun startService(context: Context) {
            val intent = Intent(context, MeshService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, MeshService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val meshNode: MeshNode by inject()
    private val nearbyTransport: NearbyTransport by inject()
    private val dutyCycleController: DutyCycleController by inject()

    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private var evaluationJob: Job? = null

    private var currentBatteryPercent: Int = 100

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) {
                    currentBatteryPercent = (level * 100) / scale
                }
            }
        }
    }

    private val transportListener = object : TransportListener {
        override fun onPeerConnected(peer: NodeId) {
            dutyCycleController.onPeerConnected(peer)
            updateNotification()
        }

        override fun onPeerDisconnected(peer: NodeId) {
            dutyCycleController.onPeerDisconnected(peer)
            updateNotification()
        }

        override fun onPacketReceived(fromPeer: NodeId, packet: Packet) {
            dutyCycleController.onTraffic()
        }
    }

    private val dutyCycleListener = object : DutyCycleListener {
        override fun onStateChanged(newState: DutyCycleState) {
            updateNotification()
        }

        override fun onRadioScanStateChanged(enableScanning: Boolean) {
            if (enableScanning) {
                nearbyTransport.resumeDiscovery()
            } else {
                nearbyTransport.pauseDiscovery()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopMesh()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startMesh()
                return START_STICKY
            }
        }
    }

    private fun startMesh() {
        if (isServiceRunning) return
        isServiceRunning = true

        startForegroundNotification()

        // Register battery monitor
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerReceiver(batteryReceiver, filter)

        // Configure duty cycle controller
        dutyCycleController.batteryLevelProvider = { currentBatteryPercent }
        dutyCycleController.listener = dutyCycleListener
        dutyCycleController.start()

        // Start transport and register listeners
        nearbyTransport.registerListener(transportListener)
        nearbyTransport.start()

        // Start periodic evaluation loop for duty cycle
        evaluationJob?.cancel()
        evaluationJob = serviceScope.launch {
            while (isActive) {
                delay(2000L)
                dutyCycleController.evaluate()
            }
        }
    }

    private fun stopMesh() {
        if (!isServiceRunning) return
        isServiceRunning = false

        evaluationJob?.cancel()
        evaluationJob = null

        try {
            unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            // Ignore if already unregistered
        }

        dutyCycleController.stop()
        nearbyTransport.unregisterListener(transportListener)
        nearbyTransport.stop()

        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    override fun onDestroy() {
        stopMesh()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps disaster mesh active for background packet relaying"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun startForegroundNotification() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun updateNotification() {
        if (!isServiceRunning) return
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = if (launchIntent != null) {
            PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        } else null

        val peerCount = nearbyTransport.getConnectedPeers().size
        val stateName = dutyCycleController.currentState.name

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Emergency Mesh Active")
            .setContentText("Connected peers: $peerCount · Mode: $stateName · Battery: $currentBatteryPercent%")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
}

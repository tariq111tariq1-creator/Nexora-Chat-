package com.example.service

import android.Manifest
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.example.OfflineChatApplication
import com.example.notifications.NotificationOrchestrator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MeshSessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wifiLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val notification = NotificationOrchestrator.buildServiceNotification(this, 0)
        startForegroundCompat(notification)
        acquireRadioLocks()

        val app = application as? OfflineChatApplication ?: return
        scope.launch {
            app.repository.connectedPeerIds.collectLatest { connected ->
                val updated = NotificationOrchestrator.buildServiceNotification(
                    this@MeshSessionService,
                    connected.size
                )
                val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                mgr.notify(NotificationOrchestrator.NOTIF_ID_SERVICE, updated)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun acquireRadioLocks() {
        runCatching {
            val wifiMgr = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            } else {
                WifiManager.WIFI_MODE_FULL_HIGH_PERF
            }
            wifiLock = wifiMgr?.createWifiLock(mode, "OfflineChat:MeshWifiLock")?.apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "OfflineChat:MeshWakeLock")?.apply {
                setReferenceCounted(false)
                acquire(60 * 60 * 1000L) // 1 hour safety timeout
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as? OfflineChatApplication
        val count = app?.repository?.connectedPeerIds?.value?.size ?: 0
        startForegroundCompat(NotificationOrchestrator.buildServiceNotification(this, count))
        app?.repository?.ensureMeshStarted()
        return START_STICKY
    }

    private fun startForegroundCompat(notification: android.app.Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            var fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
            ) {
                fgsType = fgsType or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            }
            runCatching {
                startForeground(NotificationOrchestrator.NOTIF_ID_SERVICE, notification, fgsType)
            }.onFailure {
                startForeground(
                    NotificationOrchestrator.NOTIF_ID_SERVICE,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NotificationOrchestrator.NOTIF_ID_SERVICE,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NotificationOrchestrator.NOTIF_ID_SERVICE, notification)
        }
    }

    override fun onDestroy() {
        runCatching { if (wifiLock?.isHeld == true) wifiLock?.release() }
        runCatching { if (wakeLock?.isHeld == true) wakeLock?.release() }
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, MeshSessionService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, MeshSessionService::class.java))
        }
    }
}

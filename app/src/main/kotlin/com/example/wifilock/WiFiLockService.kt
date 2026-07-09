package com.example.wifilock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat

class WiFiLockService : Service() {

    private val CHANNEL_ID = "wifi_lock_channel"
    private lateinit var wifiManager: WifiManager
    private var lockedSsid: String? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val CHECK_INTERVAL_MS = 5000L

    override fun onCreate() {
        super.onCreate()
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lockedSsid = intent?.getStringExtra("LOCKED_SSID") ?: return START_NOT_STICKY

        try {
            val notif = buildNotification("Locking to $lockedSsid")
            startForeground(1, notif)
        } catch (e: Exception) {
            Log.e("WiFiLockService", "startForeground failed: ${e.message}")
            stopSelf()
            return START_NOT_STICKY
        }

        startMonitoring()
        return START_STICKY
    }

    private fun startMonitoring() {
        thread?.quitSafely()
        thread = HandlerThread("wifi-lock-monitor").also {
            it.start()
            handler = Handler(it.looper)
            handler?.post(object : Runnable {
                override fun run() {
                    checkConnection()
                    handler?.postDelayed(this, CHECK_INTERVAL_MS)
                }
            })
        }
    }

    private fun currentSsid(): String? {
        return try {
            val info = wifiManager.connectionInfo ?: return null
            val ssid = info.ssid ?: return null
            ssid.removeSurrounding("\"")
        } catch (_: Exception) { null }
    }

    private fun checkConnection() {
        val target = lockedSsid ?: return
        val current = currentSsid()
        if (current != target) {
            reconnect(target)
            updateNotification("Reconnecting to $target (was $current)")
        } else {
            updateNotification("Locked to $target")
        }
    }

    private fun reconnect(ssid: String) {
        try {
            val configured = wifiManager.configuredNetworks?.firstOrNull {
                it.SSID.removeSurrounding("\"") == ssid
            }
            if (configured != null) {
                wifiManager.enableNetwork(configured.networkId, true)
                wifiManager.reassociate()
                return
            }
        } catch (_: Exception) { }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val specifier = android.net.wifi.WifiNetworkSpecifier.Builder()
                    .setSsid(ssid)
                    .build()
                val request = android.net.NetworkRequest.Builder()
                    .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                    .setNetworkSpecifier(specifier)
                    .build()
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE)
                        as android.net.ConnectivityManager
                cm.requestNetwork(request, object : android.net.ConnectivityManager.NetworkCallback() {})
            } catch (_: Exception) { }
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "WiFi Lock Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WiFi Lock Active")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_wifi_notification)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(status: String) {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(1, buildNotification(status))
        } catch (_: Exception) { }
    }

    override fun onDestroy() {
        thread?.quitSafely()
        thread = null
        handler = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

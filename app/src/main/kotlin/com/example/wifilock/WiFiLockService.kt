package com.example.wifilock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WiFiLockService : Service() {

    private val CHANNEL_ID = "wifi_lock_channel"
    private lateinit var wifiManager: WifiManager
    private lateinit var connectivityManager: ConnectivityManager
    private var lockedSsid: String? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val CHECK_INTERVAL_MS = 5000L
    private var consecutiveFailures = 0

    override fun onCreate() {
        super.onCreate()
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        connectivityManager = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        createChannel()
        // Must call startForeground immediately so the system doesn't kill us
        try {
            val notif = buildNotification(
                if (lockedSsid != null) "Locked to $lockedSsid" else "Idle — no network locked"
            )
            startForeground(1, notif)
        } catch (e: Exception) {
            log("startForeground FAILED: ${e.message}")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val newSsid = intent?.getStringExtra("LOCKED_SSID")
        val unlock = intent?.getBooleanExtra("UNLOCK", false) ?: false

        when {
            unlock -> {
                log("Unlock requested — stopping service")
                stopSelf()
                return START_NOT_STICKY
            }
            newSsid != null -> {
                lockedSsid = newSsid
                log("Service locked to: $lockedSsid")
                // Immediately try to connect
                reconnect(newSsid)
                startMonitoring()
                updateNotification("Locked to $lockedSsid")
            }
            else -> {
                updateNotification("Idle — no network locked")
            }
        }
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
            val info = wifiManager.connectionInfo ?: run {
                log("connectionInfo is null")
                return null
            }
            val raw = info.ssid ?: run {
                log("ssid is null")
                return null
            }
            val cleaned = raw.removeSurrounding("\"")
            if (cleaned == "<unknown ssid>" || cleaned == "0x" || cleaned.isBlank()) {
                log("not connected (ssid=$raw)")
                null
            } else {
                cleaned
            }
        } catch (e: Exception) {
            log("currentSsid exception: ${e.message}")
            null
        }
    }

    private fun isWifiOn(): Boolean {
        return try {
            wifiManager.isWifiEnabled
        } catch (_: Exception) { false }
    }

    private fun checkConnection() {
        val target = lockedSsid ?: return

        if (!isWifiOn()) {
            updateNotification("WiFi is OFF — turn it on to lock to $target")
            return
        }

        val current = currentSsid()

        if (current == target) {
            consecutiveFailures = 0
            updateNotification("Locked to $target")
            return
        }

        consecutiveFailures++
        log("Not on target. current=$current target=$target attempt=$consecutiveFailures")

        val msg = if (current != null) {
            "Reconnecting to $target (was on $current)…"
        } else {
            "Connecting to $target…"
        }
        updateNotification(msg)

        reconnect(target)
    }

    private fun reconnect(ssid: String) {
        // Strategy 1: enableNetwork for a SAVED network (keeps device internet working)
        try {
            val networks = wifiManager.configuredNetworks
            if (!networks.isNullOrEmpty()) {
                val match = networks.firstOrNull {
                    it.SSID.removeSurrounding("\"") == ssid
                }
                if (match != null) {
                    log("Found saved network: ${match.networkId}, calling enableNetwork(true)")
                    // true = make it the highest-priority and connect now
                    wifiManager.enableNetwork(match.networkId, true)
                    wifiManager.reassociate()
                    log("enableNetwork+reassociate called")
                    return
                }
                log("Saved networks exist but none match '$ssid'")
            } else {
                log("configuredNetworks is empty (normal on Android 10+)")
            }
        } catch (e: Exception) {
            log("enableNetwork failed: ${e.message}")
        }

        // Strategy 2 (API 29+): NetworkSpecifier — connects device to the network
        // WITHOUT bindProcessToNetwork (which would kill normal internet for the app)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                log("Trying WifiNetworkSpecifier for '$ssid' (no process bind)")
                val specifier = android.net.wifi.WifiNetworkSpecifier.Builder()
                    .setSsid(ssid)
                    .build()
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .setNetworkSpecifier(specifier)
                    .build()

                // Just let the device connect — do NOT bindProcessToNetwork
                connectivityManager.requestNetwork(request, object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        log("Specifier: network available $network (device connected, not bound)")
                    }
                    override fun onUnavailable() {
                        log("Specifier: network unavailable")
                    }
                    override fun onLost(network: Network) {
                        log("Specifier: network lost $network")
                    }
                }, Handler(mainLooper))
                log("requestNetwork called with specifier")
            } catch (e: Exception) {
                log("WifiNetworkSpecifier failed: ${e.message}")
            }
        }

        // After several failed attempts, offer a tap-to-open-WiFi-settings action
        if (consecutiveFailures >= 6) {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val pi = PendingIntent.getActivity(this, 0, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

            val notif = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("WiFi Lock — Can't auto-reconnect")
                .setContentText("Tap to open WiFi settings and connect to $ssid")
                .setSmallIcon(R.drawable.ic_wifi_notification)
                .setOngoing(true)
                .setContentIntent(pi)
                .build()
            try {
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                nm.notify(1, notif)
            } catch (_: Exception) { }
        }
    }

    private fun log(msg: String) {
        Log.d("WiFiLockService", msg)
        try {
            val dir = File(filesDir, "crashlogs")
            dir.mkdirs()
            val logFile = File(dir, "service_log.txt")
            val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            FileWriter(logFile, true).use { w ->
                w.write("[$ts] $msg\n")
            }
        } catch (_: Exception) { }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "WiFi Lock Service",
                NotificationManager.IMPORTANCE_LOW
            )
            channel.setShowBadge(true)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val contentIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pi = PendingIntent.getActivity(this, 0, contentIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WiFi Lock")
            .setContentText(status)
            .setSmallIcon(R.drawable.ic_wifi_notification)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(status: String) {
        try {
            val contentIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            val pi = PendingIntent.getActivity(this, 0, contentIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val notif = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("WiFi Lock")
                .setContentText(status)
                .setSmallIcon(R.drawable.ic_wifi_notification)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(pi)
                .build()
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(1, notif)
        } catch (_: Exception) { }
    }

    override fun onDestroy() {
        thread?.quitSafely()
        thread = null
        handler = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        log("Service destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

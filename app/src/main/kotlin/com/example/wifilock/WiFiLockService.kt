package com.example.wifilock

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.wifi.WifiManager
import android.net.wifi.WifiConfiguration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.provider.Settings
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WiFiLockService : Service() {

    private val CHANNEL_ID = "wifi_lock_channel"
    private val PREFS_NAME = "wifi_lock_prefs"
    private val KEY_LOCKED_SSID = "locked_ssid"
    private lateinit var wifiManager: WifiManager
    private lateinit var prefs: SharedPreferences
    private var lockedSsid: String? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private val CHECK_INTERVAL_MS = 2000L
    private var consecutiveFailures = 0

    override fun onCreate() {
        super.onCreate()
        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        prefs = applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        // Restore locked SSID from prefs (survives app/service restart)
        lockedSsid = prefs.getString(KEY_LOCKED_SSID, null)
        if (!lockedSsid.isNullOrBlank()) {
            log("Restored locked SSID from prefs: $lockedSsid")
        }
        createChannel()
        try {
            val notif = buildNotification(
                if (lockedSsid != null) "Locked to $lockedSsid" else "Idle — no network locked"
            )
            startForeground(1, notif)
        } catch (e: Exception) {
            log("startForeground FAILED: ${e.message}")
        }
        if (!lockedSsid.isNullOrBlank()) {
            startMonitoring()
        }
    }


    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val newSsid = intent?.getStringExtra("LOCKED_SSID")
        val unlock = intent?.getBooleanExtra("UNLOCK", false) ?: false

        when {
            unlock -> {
                log("Unlock requested — stopping service")
                prefs.edit().remove(KEY_LOCKED_SSID).apply()
                lockedSsid = null
                stopSelf()
                return START_NOT_STICKY
            }
            newSsid != null -> {
                lockedSsid = newSsid
                prefs.edit().putString(KEY_LOCKED_SSID, newSsid).apply()
                log("Service locked to: $lockedSsid (saved to prefs)")
                reconnect(newSsid)
                startMonitoring()
                updateNotification("Locked to $lockedSsid")
            }
            else -> {
                updateNotification(
                    if (lockedSsid != null) "Locked to $lockedSsid" else "Idle — no network locked"
                )
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

    @Suppress("OverloadResolutionAmbiguity")
    private fun reconnect(ssid: String) {
        // SYSTEM-BASED RECONNECT — work with the OS, not against it.
        // On Android 10+, enableNetwork/reconnect are deprecated and may silently fail.
        // Instead we use ConnectivityManager and WifiNetworkSuggestion APIs.

        log("=== SYSTEM RECONNECT to $ssid ===")

        // 1) ConnectivityManager.requestNetwork() — tells the system "I need WiFi"
        //    This triggers the system's own reconnection logic for any saved network.
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    log("ConnectivityManager: network available: $network")
                }
                override fun onUnavailable() {
                    log("ConnectivityManager: network unavailable")
                }
            }
            cm.requestNetwork(request, cb)
            log("ConnectivityManager.requestNetwork() called — system will handle reconnect")
        } catch (e: Exception) {
            log("ConnectivityManager.requestNetwork failed: ${e.message}")
        }

        // 2) addNetworkSuggestions (Android 12+) — silently suggest the locked network
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val suggestion = android.net.wifi.WifiNetworkSuggestion.Builder()
                    .setSsid(ssid)
                    .setIsAppInteractionRequired(false)
                    .setIsUserInteractionRequired(false)
                    .build()
                val suggestionsList = listOf(suggestion)
                val status = wifiManager.addNetworkSuggestions(suggestionsList)
                when (status) {
                    WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS -> {
                        log("addNetworkSuggestions: SUCCESS for '$ssid'")
                    }
                    WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE -> {
                        log("addNetworkSuggestions: duplicate (already suggested)")
                    }
                    else -> {
                        log("addNetworkSuggestions: status=$status")
                    }
                }
            } catch (e: Exception) {
                log("addNetworkSuggestions failed: ${e.message}")
            }
        }

        // 3) Also try the legacy approach as supplementary (works on some OEMs)
        try {
            val networks = wifiManager.configuredNetworks
            if (!networks.isNullOrEmpty()) {
                val match = networks.firstOrNull {
                    it.SSID.removeSurrounding("\"") == ssid
                }
                if (match != null) {
                    log("Legacy: enabling saved network ${match.networkId}")
                    wifiManager.enableNetwork(match.networkId, true)
                    wifiManager.reassociate()
                    wifiManager.reconnect()
                }
            }
        } catch (e: Exception) {
            log("Legacy reconnect failed: ${e.message}")
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
                NotificationManager.IMPORTANCE_DEFAULT
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(status: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WiFi Lock Active")
            .setContentText(status)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(status: String) {
        try {
            val notif = buildNotification(status)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(1, notif)
        } catch (e: Exception) {
            log("updateNotification failed: ${e.message}")
        }
    }
}

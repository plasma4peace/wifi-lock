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
import android.os.Build
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
    private val CHECK_INTERVAL_MS = 5000L
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

    private fun reconnect(ssid: String) {
        // Silent reconnect: no popups, no dialogs, just keep retrying forever.
        // This runs every 5s until we reconnect to the target network.
        try {
            val networks = wifiManager.configuredNetworks
            if (!networks.isNullOrEmpty()) {
                val match = networks.firstOrNull {
                    it.SSID.removeSurrounding("\"") == ssid
                }
                if (match != null) {
                    log("Found saved network: ${match.networkId}, calling enableNetwork")
                    wifiManager.enableNetwork(match.networkId, true)
                    wifiManager.reassociate()
                    log("enableNetwork+reassociate called")
                    return
                }
                log("Saved networks exist but none match '$ssid' (will retry next cycle)")
            } else {
                log("configuredNetworks empty (Android 10+), retrying enableNetwork for any match")
            }
        } catch (e: Exception) {
            log("enableNetwork failed: ${e.message}")
        }
        try {
            wifiManager.reassociate()
            wifiManager.reconnect()
        } catch (e: Exception) {
            log("reconnect fallback failed: ${e.message}")
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
            .setSmallIcon(R.mipmap.ic_launcher_foreground)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification(status: String) {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(1, buildNotification(status))
        } catch (_: Exception) { }
    }

    override fun onDestroy() {
        super.onDestroy()
        thread?.quitSafely()
        thread = null
        handler = null
    }
}

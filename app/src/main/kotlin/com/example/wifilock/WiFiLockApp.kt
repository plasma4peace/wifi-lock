package com.example.wifilock

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class WiFiLockApp : Application() {

    override fun onCreate() {
        super.onCreate()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val crashDir = File(filesDir, "crashlogs")
                crashDir.mkdirs()
                val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                val crashFile = File(crashDir, "crash_$timestamp.txt")
                FileWriter(crashFile).use { w ->
                    w.write("=== CRASH REPORT ===\n")
                    w.write("Time: $timestamp\n")
                    w.write("Thread: ${thread.name} (${thread.id})\n")
                    w.write("Exception: ${throwable.javaClass.name}: ${throwable.message}\n")
                    w.write("Stack trace:\n")
                    throwable.stackTrace?.forEach { w.write("\tat $it\n") }
                    throwable.cause?.let { cause ->
                        w.write("Caused by: ${cause.javaClass.name}: ${cause.message}\n")
                        cause.stackTrace?.forEach { w.write("\tat $it\n") }
                    }
                    w.write("=== END ===\n")
                }
            } catch (_: Exception) { }

            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    fun checkAndNotifyCrash() {
        val crashDir = File(filesDir, "crashlogs")
        val files = crashDir.listFiles()?.filter { it.name.startsWith("crash_") } ?: return
        if (files.isEmpty()) return

        val latest = files.maxByOrNull { it.lastModified() } ?: return
        val content = latest.readText()

        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channelId = "wifi_lock_crash_channel"
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val channel = android.app.NotificationChannel(
                    channelId,
                    "WiFi Lock Crash Reports",
                    NotificationManager.IMPORTANCE_HIGH
                )
                nm.createNotificationChannel(channel)
            }
            val exLine = content.lines().firstOrNull { it.startsWith("Exception:") } ?: "Unknown"
            val traceLines = content.lines().filter { it.startsWith("\tat ") }
            val shortTrace = traceLines.take(10).joinToString("\n")

            val notif = NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.drawable.ic_wifi_notification)
                .setContentTitle("WiFi Lock Crashed")
                .setContentText(exLine.removePrefix("Exception: "))
                .setStyle(NotificationCompat.BigTextStyle().bigText("$exLine\n$shortTrace"))
                .setAutoCancel(true)
                .build()
            nm.notify(1001, notif)

            latest.delete()
        } catch (_: Exception) { }
    }
}

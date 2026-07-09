package com.wifiscanner;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import androidx.core.app.NotificationCompat;

public class WifiLockService extends Service {
    private static final String TAG = "WifiLockService";
    private static final String CHANNEL_ID = "wifi_lock_channel";
    private static final int NOTIFICATION_ID = 1;
    
    private WifiManager wifiManager;
    private String targetBssid;
    private String targetSsid;
    private Handler handler;

    private final BroadcastReceiver connectivityReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            checkConnection();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        handler = new Handler(Looper.getMainLooper());
        createNotificationChannel();
        registerReceiver(connectivityReceiver,
                new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "WiFi Lock Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription("Keeps WiFi locked to a specific network");
            NotificationManager manager = getSystemService(NotificationManager.class);
            if (manager != null) manager.createNotificationChannel(channel);
        }
    }

    private void startForegroundNotification() {
        Intent notificationIntent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0,
                notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("WiFi Lock Active")
                .setContentText("Locked to: " + (targetSsid != null ? targetSsid : "Unknown"))
                .setSmallIcon(android.R.drawable.ic_wifi_signal_0)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();

        startForeground(NOTIFICATION_ID, notification);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            targetBssid = intent.getStringExtra("bssid");
            targetSsid = intent.getStringExtra("ssid");
            Log.i(TAG, "Locking to " + targetSsid + " (" + targetBssid + ")");
            startForegroundNotification();
            startReconnectLoop();
        }
        return START_STICKY;
    }

    private void startReconnectLoop() {
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                reconnectIfNeeded();
                handler.postDelayed(this, 5000);
            }
        }, 1000);
    }

    private void reconnectIfNeeded() {
        if (!isOnTarget()) {
            Log.i(TAG, "Not on " + targetSsid + ", toggling WiFi...");
            wifiManager.setWifiEnabled(false);
            handler.postDelayed(() -> {
                wifiManager.setWifiEnabled(true);
            }, 2000);
        }
    }

    private boolean isOnTarget() {
        String current = wifiManager.getConnectionInfo().getBSSID();
        return current != null && current.equals(targetBssid);
    }

    private void checkConnection() {
        Log.i(TAG, "Connection check: " + (isOnTarget() ? "on target" : "off target"));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(connectivityReceiver); } catch (Exception ignored) {}
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}

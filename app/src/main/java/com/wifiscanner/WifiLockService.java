package com.wifiscanner;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

public class WifiLockService extends Service {
    private static final String TAG = "WifiLockService";
    private static final String CHANNEL_ID = "wifi_lock_channel";
    private static final int NOTIFICATION_ID = 1001;

    private WifiManager wifiManager;
    private ConnectivityManager connectivityManager;
    private String targetBssid;
    private String targetSsid;
    private Handler handler;
    private boolean isConnected = false;

    private BroadcastReceiver connectivityReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            checkConnection();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        handler = new Handler(Looper.getMainLooper());

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "WiFi Lock Service",
                NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }

        registerReceiver(connectivityReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            targetBssid = intent.getStringExtra("bssid");
            targetSsid = intent.getStringExtra("ssid");
            Log.i(TAG, "Starting service for BSSID: " + targetBssid + ", SSID: " + targetSsid);

            // Show foreground notification (required for Android 8+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification notification = new Notification.Builder(this, CHANNEL_ID)
                    .setContentTitle("WiFi Lock")
                    .setContentText("Locked to: " + targetSsid)
                    .setSmallIcon(android.R.drawable.ic_lock_lock)
                    .setOngoing(true)
                    .build();
                startForeground(NOTIFICATION_ID, notification);
            }

            startReconnectionLoop();
        }
        return START_STICKY;
    }

    private void startReconnectionLoop() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                checkAndReconnect();
                handler.postDelayed(this, 5000);
            }
        }, 1000);
    }

    private void checkAndReconnect() {
        if (!isConnectedToTarget()) {
            Log.i(TAG, "Not connected to target network, attempting reconnect...");
            reconnectToTarget();
        }
    }

    private boolean isConnectedToTarget() {
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        if (wifiInfo == null) return false;

        String currentBssid = wifiInfo.getBSSID();
        return targetBssid != null && targetBssid.equals(currentBssid);
    }

    private void reconnectToTarget() {
        // Toggle WiFi to force reconnection
        wifiManager.setWifiEnabled(false);
        handler.postDelayed(() -> {
            wifiManager.setWifiEnabled(true);
            Log.i(TAG, "WiFi re-enabled, attempting to reconnect to " + targetSsid);
        }, 3000);
    }

    private void checkConnection() {
        isConnected = isConnectedToTarget();
        Log.i(TAG, "Connection status: " + (isConnected ? "connected" : "disconnected"));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(connectivityReceiver);
        } catch (IllegalArgumentException ignored) {}
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

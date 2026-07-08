package com.wifiscanner;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
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
    private String targetBssid;
    private String targetSsid;
    private Handler handler;
    private int reconnectAttempts = 0;

    private BroadcastReceiver connectivityReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction() != null && intent.getAction().equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                Log.i(TAG, "Network state changed");
            }
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        handler = new Handler(Looper.getMainLooper());

        // Create notification channel (safe even if already exists)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "WiFi Lock Service",
                NotificationManager.IMPORTANCE_LOW
            );
            channel.setShowBadge(false);
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(channel);
        }

        IntentFilter filter = new IntentFilter(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(connectivityReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(connectivityReceiver, filter);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            targetBssid = intent.getStringExtra("bssid");
            targetSsid = intent.getStringExtra("ssid");
            String ssidDisplay = (targetSsid != null && !targetSsid.isEmpty()) ? targetSsid : "selected network";
            Log.i(TAG, "Starting service for BSSID: " + targetBssid);

            // Show foreground notification (required for Android 8+)
            Notification notification = buildNotification("Locked to: " + ssidDisplay);
            startForeground(NOTIFICATION_ID, notification);

            startReconnectionLoop();
        }
        return START_STICKY;
    }

    private Notification buildNotification(String text) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("WiFi Lock")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setOngoing(true)
                .build();
        } else {
            // Legacy for pre-O devices
            return new Notification.Builder(this)
                .setContentTitle("WiFi Lock")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setOngoing(true)
                .build();
        }
    }

    private void startReconnectionLoop() {
        handler.removeCallbacksAndMessages(null);
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
            reconnectAttempts++;
            Log.i(TAG, "Disconnected from target (" + reconnectAttempts + " attempts), reconnecting...");
            // Only do the aggressive toggle every 3rd attempt to avoid wearing radios
            if (reconnectAttempts % 3 == 0) {
                toggleWiFi();
            }
        } else {
            reconnectAttempts = 0;
        }
    }

    private boolean isConnectedToTarget() {
        if (targetBssid == null) return false;
        try {
            WifiInfo wifiInfo = wifiManager.getConnectionInfo();
            if (wifiInfo == null) return false;
            String currentBssid = wifiInfo.getBSSID();
            return targetBssid.equals(currentBssid);
        } catch (Exception e) {
            Log.e(TAG, "Error checking connection", e);
            return false;
        }
    }

    private void toggleWiFi() {
        try {
            wifiManager.setWifiEnabled(false);
            handler.postDelayed(() -> {
                wifiManager.setWifiEnabled(true);
                Log.i(TAG, "WiFi toggled, reconnecting to " +
                    (targetSsid != null ? targetSsid : "target"));
            }, 3000);
        } catch (Exception e) {
            Log.e(TAG, "Error toggling WiFi", e);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(connectivityReceiver);
        } catch (Exception ignored) {}
        handler.removeCallbacksAndMessages(null);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

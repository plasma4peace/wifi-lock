package com.wifiscanner;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

public class WifiLockService extends Service {
    private static final String TAG = "WifiLockService";
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
        registerReceiver(connectivityReceiver,
                new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            targetBssid = intent.getStringExtra("bssid");
            targetSsid = intent.getStringExtra("ssid");
            Log.i(TAG, "Locking to " + targetSsid + " (" + targetBssid + ")");
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

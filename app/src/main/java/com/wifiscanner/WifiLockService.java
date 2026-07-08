package com.wifiscanner;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

public class WifiLockService extends Service {
    private static final String TAG = "WifiLockService";
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
        
        registerReceiver(connectivityReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            targetBssid = intent.getStringExtra("bssid");
            targetSsid = intent.getStringExtra("ssid");
            Log.i(TAG, "Starting service for BSSID: " + targetBssid + ", SSID: " + targetSsid);
            
            // Start aggressive reconnection loop
            startReconnectionLoop();
        }
        return START_STICKY;
    }

    private void startReconnectionLoop() {
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                checkAndReconnect();
                handler.postDelayed(this, 5000); // Check every 5 seconds
            }
        }, 0);
    }

    private void checkAndReconnect() {
        if (!isConnectedToTarget()) {
            Log.i(TAG, "Not connected to target network, attempting reconnect...");
            reconnectToTarget();
        }
    }

    private boolean isConnectedToTarget() {
        Network activeNetwork = connectivityManager.getActiveNetwork();
        if (activeNetwork == null) {
            return false;
        }
        
        NetworkCapabilities caps = connectivityManager.getNetworkCapabilities(activeNetwork);
        if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return false;
        }
        
        WifiManager wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        String currentBssid = wifiManager.getConnectionInfo().getBSSID();
        return targetBssid != null && targetBssid.equals(currentBssid);
    }

    private void reconnectToTarget() {
        // Disable WiFi briefly to force reconnection
        wifiManager.setWifiEnabled(false);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                wifiManager.setWifiEnabled(true);
                Log.i(TAG, "WiFi re-enabled, attempting to reconnect to " + targetSsid);
            }
        }, 2000);
    }

    private void checkConnection() {
        isConnected = isConnectedToTarget();
        Log.i(TAG, "Connection status: " + isConnected);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        unregisterReceiver(connectivityReceiver);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}

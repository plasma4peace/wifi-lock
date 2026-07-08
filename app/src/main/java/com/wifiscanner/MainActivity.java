package com.wifiscanner;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private WifiManager wifiManager;
    private ConnectivityManager connectivityManager;
    private ListView lvNetworks;
    private Button btnScan, btnLock;
    private TextView tvStatus;
    private List<ScanResult> networks = new ArrayList<>();
    private int selectedNetworkIndex = -1;
    private String lockedBssid = null;
    private Handler handler = new Handler(Looper.getMainLooper());
    private boolean isServiceRunning = false;

    private BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            List<ScanResult> results = wifiManager.getScanResults();
            networks.clear();
            networks.addAll(results);
            // Sort by signal strength (weakest first - higher dBm is weaker)
            Collections.sort(networks, (a, b) -> Integer.compare(a.level, b.level));
            updateListView();
            tvStatus.setText("Status: Scanned " + networks.size() + " networks");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        lvNetworks = findViewById(R.id.lvNetworks);
        btnScan = findViewById(R.id.btnScan);
        btnLock = findViewById(R.id.btnLock);
        tvStatus = findViewById(R.id.tvStatus);

        btnScan.setOnClickListener(v -> requestPermissionsAndScan());
        btnLock.setOnClickListener(v -> lockToSelectedNetwork());

        registerReceiver(scanReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
    }

    private void requestPermissionsAndScan() {
        String[] permissions = new String[]{
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_WIFI_STATE,
            Manifest.permission.CHANGE_WIFI_STATE
        };
        
        boolean allGranted = true;
        for (String perm : permissions) {
            if (ActivityCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
                allGranted = false;
                break;
            }
        }
        
        if (!allGranted) {
            ActivityCompat.requestPermissions(this, permissions, 1);
        } else {
            startScan();
        }
    }

    private void startScan() {
        wifiManager.startScan();
        tvStatus.setText("Status: Scanning...");
    }

    private void updateListView() {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
            android.R.layout.simple_list_item_single_choice,
            new ArrayList<>());
        
        for (ScanResult result : networks) {
            String item = result.SSID + " (" + result.level + " dBm) - " + result.BSSID;
            adapter.add(item);
        }
        
        lvNetworks.setAdapter(adapter);
        lvNetwork.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        
        lvNetworks.setOnItemClickListener((parent, view, position, id) -> {
            selectedNetworkIndex = position;
            lockedBssid = networks.get(position).BSSID;
            tvStatus.setText("Selected: " + networks.get(position).SSID);
        });
    }

    private void lockToSelectedNetwork() {
        if (selectedNetworkIndex < 0 || networks.size() == 0) {
            tvStatus.setText("Error: No network selected");
            return;
        }
        
        ScanResult selected = networks.get(selectedNetworkIndex);
        lockedBssid = selected.BSSID;
        
        startWifiLockService(selected.BSSID, selected.SSID);
        
        tvStatus.setText("Locked to: " + selected.SSID + " - Reconnecting aggressively");
    }

    private void startWifiLockService(String bssid, String ssid) {
        Intent serviceIntent = new Intent(this, WifiLockService.class);
        serviceIntent.putExtra("bssid", bssid);
        serviceIntent.putExtra("ssid", ssid);
        startService(serviceIntent);
        isServiceRunning = true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == 1) {
            boolean allGranted = true;
            for (int result : grantResults) {
                if (result != PackageManager.PERMISSION_GRANTED) {
                    allGranted = false;
                    break;
                }
            }
            if (allGranted) {
                startScan();
            } else {
                tvStatus.setText("Error: Permissions required for WiFi scanning");
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(scanReceiver);
    }
}

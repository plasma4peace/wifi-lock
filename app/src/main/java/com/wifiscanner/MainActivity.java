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
import android.widget.Toast;
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
    private NetworkAdapter networkAdapter;

    private BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            List<ScanResult> results = wifiManager.getScanResults();
            networks.clear();
            networks.addAll(results);
            // Sort by signal strength (weakest first - lower dBm is weaker)
            Collections.sort(networks, (a, b) -> Integer.compare(a.level, b.level));
            networkAdapter.notifyDataSetChanged();
            tvStatus.setText("Scanned " + networks.size() + " networks");
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

        networkAdapter = new NetworkAdapter();
        lvNetworks.setAdapter(networkAdapter);
        lvNetworks.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        lvNetworks.setOnItemClickListener((parent, view, position, id) -> {
            selectedNetworkIndex = position;
            lockedBssid = networks.get(position).BSSID;
            tvStatus.setText("Selected: " + networks.get(position).SSID);
        });

        btnScan.setOnClickListener(v -> requestPermissionsAndScan());
        btnLock.setOnClickListener(v -> lockToSelectedNetwork());

        registerReceiver(scanReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
    }

    private void requestPermissionsAndScan() {
        String[] permissions;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions = new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            };
        } else {
            permissions = new String[]{
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            };
        }

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
        if (!wifiManager.isWifiEnabled()) {
            tvStatus.setText("WiFi is disabled");
            Toast.makeText(this, "Please enable WiFi", Toast.LENGTH_SHORT).show();
            return;
        }
        wifiManager.startScan();
        tvStatus.setText("Scanning...");
    }

    private void lockToSelectedNetwork() {
        if (selectedNetworkIndex < 0 || networks.isEmpty()) {
            tvStatus.setText("No network selected");
            Toast.makeText(this, "Select a network first", Toast.LENGTH_SHORT).show();
            return;
        }

        ScanResult selected = networks.get(selectedNetworkIndex);
        lockedBssid = selected.BSSID;

        startWifiLockService(selected.BSSID, selected.SSID);
        tvStatus.setText("Locked to: " + selected.SSID);
    }

    private void startWifiLockService(String bssid, String ssid) {
        Intent serviceIntent = new Intent(this, WifiLockService.class);
        serviceIntent.putExtra("bssid", bssid);
        serviceIntent.putExtra("ssid", ssid);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        isServiceRunning = true;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
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
                tvStatus.setText("Location permission required for WiFi scanning");
                Toast.makeText(this, "Location permission is required to scan WiFi networks", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(scanReceiver);
        } catch (IllegalArgumentException ignored) {}
    }

    private class NetworkAdapter extends ArrayAdapter<String> {
        NetworkAdapter() {
            super(MainActivity.this, android.R.layout.simple_list_item_single_choice, new ArrayList<>());
        }

        @Override
        public int getCount() {
            return networks.size();
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            TextView tv = (TextView) super.getView(position, convertView, parent);
            ScanResult result = networks.get(position);
            String ssid = result.SSID.isEmpty() ? "<hidden>" : result.SSID;
            String security = getSecurity(result);
            tv.setText(ssid + "  |  " + result.level + " dBm  " + security);
            return tv;
        }

        private String getSecurity(ScanResult result) {
            String caps = result.capabilities;
            if (caps.contains("WPA3")) return "🔒WPA3";
            if (caps.contains("WPA2")) return "🔒WPA2";
            if (caps.contains("WPA")) return "🔒WPA";
            if (caps.contains("WEP")) return "🔒WEP";
            return "🔓Open";
        }
    }
}

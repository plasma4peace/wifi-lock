package com.wifiscanner;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private WifiManager wifiManager;
    private ListView lvNetworks;
    private Button btnScan, btnLock;
    private TextView tvStatus;
    private List<ScanResult> networks = new ArrayList<>();
    private int selectedNetworkIndex = -1;
    private NetworkAdapter networkAdapter;

    private BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction() != null && intent.getAction().equals(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) {
                boolean success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false);
                if (success) {
                    List<ScanResult> results = wifiManager.getScanResults();
                    networks.clear();
                    networks.addAll(results);
                    Collections.sort(networks, (a, b) -> Integer.compare(a.level, b.level));
                    networkAdapter.notifyDataSetChanged();
                    tvStatus.setText("Scanned " + networks.size() + " networks");
                } else {
                    tvStatus.setText("Scan failed, try again");
                }
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        lvNetworks = findViewById(R.id.lvNetworks);
        btnScan = findViewById(R.id.btnScan);
        btnLock = findViewById(R.id.btnLock);
        tvStatus = findViewById(R.id.tvStatus);

        networkAdapter = new NetworkAdapter();
        lvNetworks.setAdapter(networkAdapter);
        lvNetworks.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        lvNetworks.setOnItemClickListener((parent, view, position, id) -> {
            if (position >= 0 && position < networks.size()) {
                selectedNetworkIndex = position;
                String ssid = networks.get(position).SSID;
                tvStatus.setText("Selected: " + (ssid.isEmpty() ? "<hidden>" : ssid));
            }
        });

        btnScan.setOnClickListener(v -> requestPermissionsAndScan());
        btnLock.setOnClickListener(v -> lockToSelectedNetwork());

        // Android 14+ requires RECEIVER_NOT_EXPORTED for registered receivers
        IntentFilter filter = new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(scanReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(scanReceiver, filter);
        }
    }

    private void requestPermissionsAndScan() {
        List<String> needed = new ArrayList<>();

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION);
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    needed.toArray(new String[0]), 1);
        } else {
            startScan();
        }
    }

    private void startScan() {
        if (!wifiManager.isWifiEnabled()) {
            tvStatus.setText("WiFi is disabled");
            Toast.makeText(this, "Please enable WiFi in settings", Toast.LENGTH_SHORT).show();
            return;
        }
        boolean started = wifiManager.startScan();
        if (started) {
            tvStatus.setText("Scanning...");
        } else {
            tvStatus.setText("Could not start scan");
            Toast.makeText(this, "Scan failed to start", Toast.LENGTH_SHORT).show();
        }
    }

    private void lockToSelectedNetwork() {
        if (selectedNetworkIndex < 0 || selectedNetworkIndex >= networks.size()) {
            tvStatus.setText("No network selected");
            Toast.makeText(this, "Select a network from the list first", Toast.LENGTH_SHORT).show();
            return;
        }

        ScanResult selected = networks.get(selectedNetworkIndex);
        startWifiLockService(selected.BSSID, selected.SSID);
        tvStatus.setText("Locked to: " + selected.SSID);
    }

    private void startWifiLockService(String bssid, String ssid) {
        Intent serviceIntent = new Intent(this, WifiLockService.class);
        serviceIntent.putExtra("bssid", bssid);
        serviceIntent.putExtra("ssid", ssid);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent);
            } else {
                startService(serviceIntent);
            }
        } catch (Exception e) {
            tvStatus.setText("Error: " + e.getMessage());
            Toast.makeText(this, "Failed to start lock service", Toast.LENGTH_SHORT).show();
        }
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
                tvStatus.setText("Permissions required");
                Toast.makeText(this, "Location permission is needed to scan WiFi", Toast.LENGTH_LONG).show();
            }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try {
            unregisterReceiver(scanReceiver);
        } catch (Exception ignored) {}
    }

    private class NetworkAdapter extends ArrayAdapter<String> {
        NetworkAdapter() {
            super(MainActivity.this, android.R.layout.simple_list_item_single_choice, new ArrayList<>());
        }

        @Override
        public int getCount() {
            return networks.size();
        }

        @Override
        public String getItem(int position) {
            if (position < 0 || position >= networks.size()) return "";
            ScanResult r = networks.get(position);
            String ssid = r.SSID.isEmpty() ? "<hidden>" : r.SSID;
            String security = getSecurity(r);
            return String.format("%s (%d dBm) %s", ssid, r.level, security);
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            TextView tv = (TextView) super.getView(position, convertView, parent);
            tv.setText(getItem(position));
            return tv;
        }

        private String getSecurity(ScanResult result) {
            String caps = result.capabilities;
            if (caps == null) return "";
            if (caps.contains("WPA3")) return "WPA3";
            if (caps.contains("WPA2")) return "WPA2";
            if (caps.contains("WPA")) return "WPA";
            if (caps.contains("WEP")) return "WEP";
            return "Open";
        }
    }
}

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
import androidx.core.content.ContextCompat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private WifiManager wifiManager;
    private ListView lvNetworks;
    private Button btnScan, btnLock;
    private TextView tvStatus;
    private List<ScanResult> networks = new ArrayList<>();
    private int selectedIndex = -1;

    private final BroadcastReceiver scanReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            List<ScanResult> results = wifiManager.getScanResults();
            networks.clear();
            networks.addAll(results);
            Collections.sort(networks, (a, b) -> Integer.compare(a.level, b.level));
            ((NetworkAdapter) lvNetworks.getAdapter()).updateData(networks);
            tvStatus.setText("Scanned " + networks.size() + " networks");
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

        lvNetworks.setAdapter(new NetworkAdapter(this, networks));
        lvNetworks.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        lvNetworks.setOnItemClickListener((parent, view, position, id) -> {
            selectedIndex = position;
            tvStatus.setText("Selected: " + networks.get(position).SSID);
        });

        btnScan.setOnClickListener(v -> scan());
        btnLock.setOnClickListener(v -> lock());

        registerReceiver(scanReceiver, new IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION));
    }

    private void scan() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.ACCESS_FINE_LOCATION,
                                Manifest.permission.ACCESS_COARSE_LOCATION}, 1);
                return;
            }
        }
        if (!wifiManager.isWifiEnabled()) {
            tvStatus.setText("WiFi is off");
            Toast.makeText(this, "Turn on WiFi first", Toast.LENGTH_SHORT).show();
            return;
        }
        wifiManager.startScan();
        tvStatus.setText("Scanning...");
    }

    private void lock() {
        if (selectedIndex < 0) {
            tvStatus.setText("Select a network first");
            return;
        }
        ScanResult selected = networks.get(selectedIndex);
        Intent intent = new Intent(this, WifiLockService.class);
        intent.putExtra("bssid", selected.BSSID);
        intent.putExtra("ssid", selected.SSID);
        startService(intent);
        tvStatus.setText("Locked to " + selected.SSID);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            scan();
        } else {
            tvStatus.setText("Location permission required");
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        try { unregisterReceiver(scanReceiver); } catch (Exception ignored) {}
    }

    private static class NetworkAdapter extends ArrayAdapter<ScanResult> {
        NetworkAdapter(Context ctx, List<ScanResult> data) {
            super(ctx, android.R.layout.simple_list_item_1, data);
        }

        void updateData(List<ScanResult> data) {
            clear();
            addAll(data);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent) {
            TextView tv = (TextView) super.getView(position, convertView, parent);
            ScanResult r = getItem(position);
            if (r != null) {
                String ssid = r.SSID.isEmpty() ? "<hidden>" : r.SSID;
                tv.setText(ssid + "  |  " + r.level + " dBm  " + emoji(r.capabilities));
            }
            return tv;
        }

        private String emoji(String caps) {
            if (caps.contains("WPA3")) return "WPA3";
            if (caps.contains("WPA2")) return "WPA2";
            if (caps.contains("WPA")) return "WPA";
            if (caps.contains("WEP")) return "WEP";
            return "OPEN";
        }
    }
}

package com.example.wifilock

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {

    private lateinit var wifiManager: WifiManager
    private val scanResults = mutableStateListOf<ScanResult>()
    private val permissionsGranted = mutableStateOf(false)
    private var scanReceiver: BroadcastReceiver? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        permissionsGranted.value = hasRequiredPermissions()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        scanReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                    scanResults.clear()
                    scanResults.addAll(wifiManager.scanResults ?: emptyList())
                }
            }
        }
        registerReceiver(
            scanReceiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        )

        if (!hasRequiredPermissions()) {
            requestPermissionLauncher.launch(getRequiredPermissions())
        } else {
            permissionsGranted.value = true
        }

        setContent {
            val granted = permissionsGranted.value
            var selectedSsid by remember { mutableStateOf<String?>(null) }
            var lockedSsid by remember { mutableStateOf<String?>(null) }

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (granted) {
                        MainScreen(
                            results = scanResults,
                            selectedSsid = selectedSsid,
                            lockedSsid = lockedSsid,
                            onScan = {
                                if (hasRequiredPermissions()) {
                                    wifiManager.startScan()
                                } else {
                                    requestPermissionLauncher.launch(getRequiredPermissions())
                                }
                            },
                            onSelect = { selectedSsid = it },
                            onLock = {
                                selectedSsid?.let { ssid ->
                                    lockedSsid = ssid
                                    val intent = Intent(this, WiFiLockService::class.java).apply {
                                        putExtra("LOCKED_SSID", ssid)
                                    }
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                        startForegroundService(intent)
                                    } else {
                                        @Suppress("DEPRECATION")
                                        startService(intent)
                                    }
                                }
                            },
                            onUnlock = {
                                stopService(Intent(this, WiFiLockService::class.java))
                                lockedSsid = null
                            }
                        )
                    } else {
                        PermissionPrompt(onRetry = {
                            requestPermissionLauncher.launch(getRequiredPermissions())
                        })
                    }
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scanReceiver?.let { unregisterReceiver(it) }
    }

    private fun hasRequiredPermissions(): Boolean {
        return getRequiredPermissions().all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun getRequiredPermissions(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
        return permissions.toTypedArray()
    }
}

@Composable
fun MainScreen(
    results: List<ScanResult>,
    selectedSsid: String?,
    lockedSsid: String?,
    onScan: () -> Unit,
    onSelect: (String) -> Unit,
    onLock: () -> Unit,
    onUnlock: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Text("WiFi Lock", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(onClick = onScan, modifier = Modifier.weight(1f)) {
                Text("Scan")
            }
            Button(
                onClick = onLock,
                enabled = selectedSsid != null,
                modifier = Modifier.weight(1f)
            ) {
                Text(if (lockedSsid != null) "Locked" else "Lock")
            }
        }
        if (lockedSsid != null) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onUnlock,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Unlock  ($lockedSsid)")
            }
        }
        Spacer(Modifier.height(12.dp))
        if (results.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No networks found.\nTap Scan (location must be on).",
                    textAlign = TextAlign.Center
                )
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(results) { r ->
                    val ssid = r.SSID
                    val isSelected = ssid == selectedSsid
                    val isLocked = ssid == lockedSsid
                    Surface(
                        tonalElevation = if (isSelected || isLocked) 4.dp else 0.dp,
                        color = when {
                            isLocked -> MaterialTheme.colorScheme.primaryContainer
                            isSelected -> MaterialTheme.colorScheme.secondaryContainer
                            else -> MaterialTheme.colorScheme.surface
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(ssid) }
                    ) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    if (ssid.isEmpty()) "<hidden>" else ssid,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                                Text(
                                    "${r.level} dBm",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (isLocked) Text("\uD83D\uDD12")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionPrompt(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "Location Permission Required",
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "WiFi scanning needs location access.\nGrant the permission and reopen the app.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Grant Permission") }
    }
}

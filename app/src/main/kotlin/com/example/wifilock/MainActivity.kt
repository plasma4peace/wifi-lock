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
import android.os.Handler
import android.os.Looper
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
    private val isScanning = mutableStateOf(false)
    private val isLocking = mutableStateOf(false)
    private var scanReceiver: BroadcastReceiver? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanTimeout = Runnable { isScanning.value = false }
    private val pendingLockSsid = mutableListOf<String>()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        // Check required (location) permissions
        val allGranted = getRequiredPermissions().all {
            result.getOrDefault(it, false) || ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        // Check notification permission (needed for foreground service)
        val notifOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            result.getOrDefault(Manifest.permission.POST_NOTIFICATIONS, false)
                    || ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        permissionsGranted.value = allGranted
        if (allGranted && notifOk && pendingLockSsid.isNotEmpty()) {
            val ssid = pendingLockSsid.removeFirst()
            doLock(ssid)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        (application as WiFiLockApp).checkAndNotifyCrash()

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

        scanReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                    mainHandler.removeCallbacks(scanTimeout)
                    scanResults.clear()
                    scanResults.addAll(wifiManager.scanResults ?: emptyList())
                    isScanning.value = false
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
                            isScanning = isScanning.value,
                            isLocking = isLocking.value,
                            onScan = {
                                if (hasRequiredPermissions()) {
                                    isScanning.value = true
                                    mainHandler.removeCallbacks(scanTimeout)
                                    mainHandler.postDelayed(scanTimeout, 6000)
                                    wifiManager.startScan()
                                } else {
                                    requestPermissionLauncher.launch(getRequiredPermissions())
                                }
                            },
                            onSelect = { selectedSsid = it },
                            onLock = {
                                selectedSsid?.let { ssid ->
                                    if (hasNotificationPermission()) {
                                        isLocking.value = true
                                        doLock(ssid)
                                        lockedSsid = ssid
                                        isLocking.value = false
                                    } else {
                                        pendingLockSsid.add(ssid)
                                        requestPermissionLauncher.launch(
                                            arrayOf(Manifest.permission.POST_NOTIFICATIONS)
                                        )
                                    }
                                }
                            },
                            onUnlock = {
                                isLocking.value = true
                                stopService(Intent(this, WiFiLockService::class.java))
                                lockedSsid = null
                                isLocking.value = false
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

    private fun doLock(ssid: String) {
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

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(scanTimeout)
        scanReceiver?.let { unregisterReceiver(it) }
    }

    private fun hasNotificationPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
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
    isScanning: Boolean,
    isLocking: Boolean,
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
            Button(
                onClick = onScan,
                enabled = !isScanning,
                modifier = Modifier.weight(1f)
            ) {
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Scanning")
                } else {
                    Text("Scan")
                }
            }
            Button(
                onClick = onLock,
                enabled = selectedSsid != null && !isLocking && lockedSsid == null,
                modifier = Modifier.weight(1f)
            ) {
                if (isLocking && lockedSsid == null) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Locking")
                } else {
                    Text(if (lockedSsid != null) "Locked" else "Lock")
                }
            }
        }
        if (lockedSsid != null) {
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = onUnlock,
                enabled = !isLocking,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isLocking) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onError
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Unlocking")
                } else {
                    Text("Unlock  ($lockedSsid)")
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        if (results.isEmpty() && !isScanning) {
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

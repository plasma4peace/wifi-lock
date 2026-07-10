package com.example.wifilock

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.location.LocationManager
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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ExperimentalMaterialApi
import androidx.compose.material.pullrefresh.PullRefreshIndicator
import androidx.compose.material.pullrefresh.pullRefresh
import androidx.compose.material.pullrefresh.rememberPullRefreshState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.provider.Settings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var wifiManager: WifiManager
    private lateinit var prefs: SharedPreferences
    private val scanResults = mutableStateListOf<ScanResult>()
    private val permissionsGranted = mutableStateOf(false)
    private val isScanning = mutableStateOf(false)
    private var scanReceiver: BroadcastReceiver? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val scanTimeout = Runnable { isScanning.value = false }
    private val pendingLockSsid = mutableListOf<String>()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val allGranted = getRequiredPermissions().all {
            result.getOrDefault(it, false) ||
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        val notifOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            result.getOrDefault(Manifest.permission.POST_NOTIFICATIONS, false) ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true
        permissionsGranted.value = allGranted
        if (allGranted && notifOk) {
            if (wifiManager.isWifiEnabled) startScan()
            startServiceSafe()
            if (pendingLockSsid.isNotEmpty()) {
                pendingLockSsid.removeFirst().let(this::doLock)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        (application as WiFiLockApp).checkAndNotifyCrash()

        wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        prefs = applicationContext.getSharedPreferences("wifi_lock_prefs", Context.MODE_PRIVATE)
        val savedSsid = prefs.getString("locked_ssid", null)

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
        registerReceiver(scanReceiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))

        if (!hasRequiredPermissions()) {
            requestPermissionLauncher.launch(getRequiredPermissions())
        } else {
            permissionsGranted.value = true
            if (wifiManager.isWifiEnabled) startScan()
            startServiceSafe()
        }

        setContent {
            val granted = permissionsGranted.value
            val lockedSsid = remember { mutableStateOf(savedSsid) }
            var locationEnabled by remember { mutableStateOf(isLocationEnabled()) }

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (granted) {
                        WiFiLockScreen(
                            results = scanResults,
                            lockedSsid = lockedSsid.value,
                            isScanning = isScanning.value,
                            locationEnabled = locationEnabled,
                            onLock = { ssid -> doLock(ssid); lockedSsid.value = ssid },
                            onUnlock = { doUnlock(); lockedSsid.value = null },
                            onRefresh = {
                                locationEnabled = isLocationEnabled()
                                if (hasRequiredPermissions()) startScan()
                            },
                            onConnect = { ssid -> systemConnect(ssid) },
                        )
                    } else {
                        PermissionPrompt {
                            requestPermissionLauncher.launch(getRequiredPermissions())
                        }
                    }
                }
            }
        }
    }

    private fun startServiceSafe() {
        if (!hasNotificationPermission()) return
        val i = Intent(this, WiFiLockService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i)
        else startService(i)
    }

    private fun startScan() {
        isScanning.value = true
        mainHandler.removeCallbacks(scanTimeout)
        mainHandler.postDelayed(scanTimeout, 6000)
        try {
            wifiManager.startScan()
        } catch (e: Exception) {
            isScanning.value = false
        }
    }

    private fun doLock(ssid: String) {
        val i = Intent(this, WiFiLockService::class.java).apply { putExtra("LOCKED_SSID", ssid) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
    }

    private fun doUnlock() {
        val i = Intent(this, WiFiLockService::class.java).apply { putExtra("UNLOCK", true) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
    }

    private fun isLocationEnabled(): Boolean {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    @Suppress("OverloadResolutionAmbiguity")
    private fun systemConnect(ssid: String) {
        // REAL system-level connect — not a virtual app-scoped connection.
        // Uses WifiManager + ConnectivityManager to make a persistent system connection
        // that shows in the system WiFi list.

        log("systemConnect: connecting to $ssid")

        // 1) Try direct enableNetwork for saved networks (works on most devices)
        try {
            val networks = wifiManager.configuredNetworks
            if (!networks.isNullOrEmpty()) {
                val match = networks.firstOrNull {
                    it.SSID.removeSurrounding("\"") == ssid
                }
                if (match != null) {
                    log("systemConnect: found saved network ${match.networkId}, enabling")
                    // Disconnect first to force fresh connection
                    wifiManager.disconnect()
                    wifiManager.enableNetwork(match.networkId, true)
                    wifiManager.reassociate()
                    wifiManager.reconnect()
                    log("systemConnect: enableNetwork+reassociate+reconnect done")
                    return
                }
            }
        } catch (e: Exception) {
            log("systemConnect enableNetwork failed: ${e.message}")
        }

        // 2) Try addNetwork + enableNetwork for non-saved open networks
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            try {
                val config = android.net.wifi.WifiConfiguration().apply {
                    SSID = "\"$ssid\""
                    allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE)
                }
                val netId = wifiManager.addNetwork(config)
                if (netId != -1) {
                    log("systemConnect: addNetwork id=$netId, enabling")
                    wifiManager.disconnect()
                    wifiManager.enableNetwork(netId, true)
                    wifiManager.reassociate()
                    wifiManager.reconnect()
                    return
                }
            } catch (e: Exception) {
                log("systemConnect addNetwork failed: ${e.message}")
            }
        }

        // 3) Fallback: open system WiFi settings so user can tap manually
        log("systemConnect: opening WiFi settings for $ssid")
        try {
            val intent = Intent(Settings.Panel.ACTION_WIFI)
            startActivity(intent)
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
    }

    private fun log(msg: String) {
        android.util.Log.d("WiFiLock", msg)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(scanTimeout)
        scanReceiver?.let { unregisterReceiver(it) }
    }

    private fun hasNotificationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else true

    private fun hasRequiredPermissions(): Boolean =
        getRequiredPermissions().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun getRequiredPermissions(): Array<String> {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            p.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            p.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        return p.toTypedArray()
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun WiFiLockScreen(
    results: List<ScanResult>,
    lockedSsid: String?,
    isScanning: Boolean,
    locationEnabled: Boolean,
    onLock: (String) -> Unit,
    onUnlock: () -> Unit,
    onRefresh: () -> Unit,
    onConnect: (String) -> Unit,
) {
    val pullRefreshState = rememberPullRefreshState(refreshing = isScanning, onRefresh = onRefresh)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .pullRefresh(pullRefreshState)
    ) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                Text("WiFi Lock", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
            }

            if (lockedSsid != null) {
                item {
                    HoldToActionCard(
                        ssid = lockedSsid,
                        holdMs = 5000,
                        onClick = { onConnect(lockedSsid) },
                        onAction = onUnlock,
                        accentColor = MaterialTheme.colorScheme.error,
                    ) {
                        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("🔒 $lockedSsid", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Hold 5s to unlock",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            if (results.isEmpty() && !isScanning) {
                item {
                    Box(
                        Modifier.fillMaxWidth().height(300.dp)
                            .clickable(enabled = lockedSsid == null) { onRefresh() },
                        contentAlignment = Alignment.Center
                    ) {
                        if (!locationEnabled) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Location is OFF", textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyLarge)
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    "Enable Location scanning in Settings to see WiFi networks.",
                                    textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        } else {
                            Text(
                                if (lockedSsid != null) "Scanning..." else "Pull down or tap to scan.",
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                }
            }

            items(results, key = { it.SSID + it.BSSID }) { r ->
                val ssid = r.SSID
                if (ssid.isBlank()) return@items
                val isLocked = ssid == lockedSsid
                if (!isLocked) {
                    HoldToActionCard(
                        ssid = ssid,
                        holdMs = 3000,
                        onClick = { onConnect(ssid) },
                        onAction = { onLock(ssid) },
                        accentColor = MaterialTheme.colorScheme.primary,
                    ) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(if (ssid.isEmpty()) "<hidden>" else ssid, style = MaterialTheme.typography.bodyLarge)
                                Text("${r.level} dBm", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }

        PullRefreshIndicator(
            refreshing = isScanning,
            state = pullRefreshState,
            modifier = Modifier.align(Alignment.TopCenter),
            contentColor = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
fun HoldToActionCard(
    ssid: String,
    holdMs: Long,
    onClick: () -> Unit = {},
    onAction: () -> Unit,
    accentColor: Color,
    content: @Composable () -> Unit,
) {
    val progress = remember { mutableStateOf(0f) }
    val scope = rememberCoroutineScope()

    Surface(
        tonalElevation = 6.dp,
        color = MaterialTheme.colorScheme.primaryContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .pointerInput(ssid) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    progress.value = 0f
                    try {
                        val job = scope.launch {
                            val steps = 60
                            for (i in 1..steps) {
                                delay(holdMs / steps)
                                progress.value = i.toFloat() / steps
                            }
                        }
                        val up = waitForUpOrCancellation()
                        if (up != null) {
                            if (progress.value >= 0.99f) {
                                onAction()
                            } else if (progress.value > 0f) {
                                onClick()
                            }
                        }
                        job.cancel()
                    } finally {
                        progress.value = 0f
                    }
                }
            }
    ) {
        Column(Modifier.fillMaxWidth()) {
            content()
            if (progress.value > 0f) {
                LinearProgressIndicator(
                    progress = progress.value.coerceIn(0f, 1f),
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = accentColor,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeCap = StrokeCap.Round,
                )
            }
        }
    }
}

@Composable
fun PermissionPrompt(onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Location Permission Required", style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(16.dp))
        Text(
            "WiFi scanning needs location access.\nGrant the permission and reopen the app.",
            style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Grant Permission") }
    }
}

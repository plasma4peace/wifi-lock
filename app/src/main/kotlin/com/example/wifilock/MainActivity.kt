package com.example.wifilock

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
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
        }

        // Start persistent service
        val si = Intent(this, WiFiLockService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(si)
        else startService(si)

        setContent {
            val granted = permissionsGranted.value
            val lockedSsid = remember { mutableStateOf(savedSsid) }

            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (granted) {
                        WiFiLockScreen(
                            results = scanResults,
                            lockedSsid = lockedSsid.value,
                            isScanning = isScanning.value,
                            onLock = { ssid -> doLock(ssid); lockedSsid.value = ssid },
                            onUnlock = { doUnlock(); lockedSsid.value = null },
                            onRefresh = { if (hasRequiredPermissions()) startScan() },
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

    private fun startScan() {
        isScanning.value = true
        mainHandler.removeCallbacks(scanTimeout)
        mainHandler.postDelayed(scanTimeout, 6000)
        wifiManager.startScan()
    }

    private fun doLock(ssid: String) {
        val i = Intent(this, WiFiLockService::class.java).apply { putExtra("LOCKED_SSID", ssid) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
    }

    private fun doUnlock() {
        val i = Intent(this, WiFiLockService::class.java).apply { putExtra("UNLOCK", true) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(i) else startService(i)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(scanTimeout)
        scanReceiver?.let { unregisterReceiver(it) }
    }

    private fun hasRequiredPermissions(): Boolean =
        getRequiredPermissions().all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }

    private fun getRequiredPermissions(): Array<String> {
        val p = mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) p.add(Manifest.permission.NEARBY_WIFI_DEVICES)
        return p.toTypedArray()
    }
}

@OptIn(ExperimentalMaterialApi::class)
@Composable
fun WiFiLockScreen(
    results: List<ScanResult>,
    lockedSsid: String?,
    isScanning: Boolean,
    onLock: (String) -> Unit,
    onUnlock: () -> Unit,
    onRefresh: () -> Unit,
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

            // ===== LOCKED NETWORK (pinned at top) =====
            if (lockedSsid != null) {
                item {
                    HoldToActionCard(
                        ssid = lockedSsid,
                        holdMs = 5000,
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

            // ===== NETWORK LIST =====
            if (results.isEmpty() && !isScanning) {
                item {
                    Box(Modifier.fillMaxWidth().height(300.dp), contentAlignment = Alignment.Center) {
                        Text(
                            if (lockedSsid != null) "Scanning..." else "No networks found.\nPull down to scan.",
                            textAlign = TextAlign.Center
                        )
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
                    var fired = false
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
                                fired = true
                                onAction()
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

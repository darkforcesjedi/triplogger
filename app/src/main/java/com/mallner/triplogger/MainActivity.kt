package com.mallner.triplogger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mallner.triplogger.ui.theme.TripLoggerTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TripLoggerTheme {
                TripLoggerApp()
            }
        }
    }
}

@Composable
fun TripLoggerApp() {
    var currentDestination by rememberSaveable { mutableStateOf(AppDestinations.HOME) }
    val context = LocalContext.current
    val appSettingsRepository = remember { AppSettingsRepository(context) }

    val permissionsToRequest = remember {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            permissions.add(Manifest.permission.BLUETOOTH)
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
        }
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissions.toTypedArray()
    }

    var permissionsGranted by rememberSaveable {
        mutableStateOf(permissionsToRequest.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        })
    }

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissionsResult ->
        permissionsGranted = permissionsResult.values.all { it }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionsGranted = permissionsToRequest.all {
                    ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            AppDestinations.entries.forEach { destination ->
                item(
                    icon = {
                        Icon(
                            destination.icon,
                            contentDescription = destination.label
                        )
                    },
                    label = { Text(destination.label) },
                    selected = destination == currentDestination,
                    onClick = { currentDestination = destination }
                )
            }
        }
    ) {
        Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
            if (currentDestination == AppDestinations.HOME) {
                HomeScreen(modifier = Modifier.padding(innerPadding), permissionsGranted = permissionsGranted, onRequestPermissions = { launcher.launch(permissionsToRequest) })
            } else {
                SettingsScreen(modifier = Modifier.padding(innerPadding), appSettingsRepository)
            }
        }
    }
}

@Composable
fun HomeScreen(modifier: Modifier = Modifier, permissionsGranted: Boolean, onRequestPermissions: () -> Unit) {
    val context = LocalContext.current
    val appSettingsRepository = remember { AppSettingsRepository(context) }
    val appSettings by appSettingsRepository.appSettingsFlow.collectAsState(initial = AppSettings("", 10, 1024 * 1024, false, "", false))
    val coroutineScope = rememberCoroutineScope()
    val isConnected by DrivingStatus.isConnected.collectAsState()
    val isLogging by DrivingStatus.isLogging.collectAsState()
    val currentLogFile by DrivingStatus.currentLogFile.collectAsState()
    val lastLocation by DrivingStatus.lastLocation.collectAsState()
    val serviceEnabled by DrivingStatus.isEnabled.collectAsState()
    val obdEnabled = appSettings.obdEnabled
    val obdConnected by DrivingStatus.isObdConnected.collectAsState()
    val obdValues by DrivingStatus.obdValues.collectAsState()

    // Use LaunchedEffect to start the service based on the initial appSettings state
    LaunchedEffect(key1 = appSettings.serviceEnabled, key2 = permissionsGranted) {
        if (appSettings.serviceEnabled && permissionsGranted) {
            context.startService(Intent(context, DrivingService::class.java))
        }
    }

    Column(modifier = modifier.padding(16.dp)) {
        if (permissionsGranted) {
            Row (modifier = Modifier.fillMaxWidth()){
                Text(
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .weight(5f),
                    text = "Service Enabled"
                )
                Switch(
                    checked = appSettings.serviceEnabled,
                    onCheckedChange = { enabled ->
                        coroutineScope.launch {
                            appSettingsRepository.updateServiceEnabled(enabled)
                            if (enabled) {
                                context.startService(Intent(context, DrivingService::class.java))
                            } else {
                                context.stopService(Intent(context, DrivingService::class.java))
                            }
                        }
                    }
                )
            }
            Row {
                Text(
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .weight(1f),
                    text = "Force Logging"
                )
                Switch(
                    checked = appSettings.forceLogging,
                    onCheckedChange = { coroutineScope.launch { appSettingsRepository.updateForceLogging(it) } }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = {
                context.stopService(Intent(context, DrivingService::class.java))
                context.startService(Intent(context, DrivingService::class.java))
            }) {
                Text("New Log File")
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Status:",
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp
            )
            if (serviceEnabled) {
                Text("Bluetooth Connected: $isConnected")
            } else {
                Text("Bluetooth Connected: (not watching, service disabled)")
            }

            Text("Logging: $isLogging")
            Text("Current Log File:\n${currentLogFile ?: "N/A"}")
            Text("Last Location: ${lastLocation?.latitude}, ${lastLocation?.longitude}")
            Text("Speed: ${lastLocation?.speed?.times(2.23694f)} MPH")

            if (obdEnabled) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "OBD-II:",
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Text("Adapter Connected: $obdConnected")
                if (obdConnected) {
                    val speedMph = obdValues["0D"]?.let { String.format("%.0f", it * 0.621371) }
                    val rpm = obdValues["0C"]?.toInt()
                    val coolant = obdValues["05"]?.toInt()
                    Text("Speed (OBD): ${speedMph ?: "-"} MPH")
                    Text("Engine RPM: ${rpm ?: "-"}")
                    Text("Coolant Temp: ${coolant ?: "-"} °C")
                    val extras = obdValues
                        .filterKeys { it !in setOf("0D", "0C", "05") }
                        .mapNotNull { (hex, value) ->
                            val pid = ObdPids.byHex(hex) ?: return@mapNotNull null
                            "${pid.name}: ${String.format("%.1f", value)} ${pid.unit}"
                        }
                        .take(6)
                        .joinToString("\n")
                    if (extras.isNotEmpty()) {
                        Text(extras)
                    }
                } else {
                    Text("Connecting to adapter… (make sure the car is on)")
                }
            }
        } else {
            Text("Permissions not granted. Please grant permissions for the app to function correctly.")
            Button(onClick = onRequestPermissions) {
                Text("Enable Permissions")
            }
        }
    }
}

enum class AppDestinations(
    val label: String,
    val icon: ImageVector,
) {
    HOME("Home", Icons.Default.Home),
    SETTINGS("Settings", Icons.Default.Settings),
}
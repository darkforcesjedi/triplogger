package com.mallner.triplogger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.annotation.StringRes
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.mallner.triplogger.ui.theme.TripLoggerTheme
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
        // keep content above the soft keyboard instead of letting the window pan
        modifier = Modifier.imePadding(),
        navigationSuiteItems = {
            AppDestinations.entries.forEach { destination ->
                item(
                    icon = {
                        Icon(
                            destination.icon,
                            contentDescription = stringResource(destination.labelRes)
                        )
                    },
                    label = { Text(stringResource(destination.labelRes)) },
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
    val appSettings by appSettingsRepository.appSettingsFlow.collectAsState(initial = AppSettings("", 10, false, 10 * 1024, false, "", serviceEnabled = false))
    val coroutineScope = rememberCoroutineScope()
    val isLogging by DrivingStatus.isLogging.collectAsState()
    val serviceEnabled by DrivingStatus.isEnabled.collectAsState()
    val triggeredBy by DrivingStatus.triggeredBy.collectAsState()
    val lastLocation by DrivingStatus.lastLocation.collectAsState()
    val currentLogFile by DrivingStatus.currentLogFile.collectAsState()
    val obdLink by DrivingStatus.obdLink.collectAsState()
    val obdValues by DrivingStatus.obdValues.collectAsState()

    // Use LaunchedEffect to start the service based on the initial appSettings state
    LaunchedEffect(key1 = appSettings.serviceEnabled, key2 = permissionsGranted, key3 = appSettings.tempLocationUri) {
        if (appSettings.serviceEnabled && permissionsGranted && appSettings.tempLocationUri.isNotEmpty()) {
            context.startService(Intent(context, DrivingService::class.java))
        }
    }

    val tempLocationMissing = appSettings.tempLocationUri.isEmpty()

    Column(
        modifier = modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        if (!permissionsGranted) {
            Text(stringResource(R.string.permissions_missing))
            Button(onClick = onRequestPermissions) {
                Text(stringResource(R.string.permissions_enable))
            }
            return@Column
        }

        // ------------------------------------------------------------- Controls
        SwitchRow(
            label = stringResource(R.string.home_service_enabled),
            caption = if (tempLocationMissing) stringResource(R.string.home_service_needs_temp) else null,
            checked = appSettings.serviceEnabled && !tempLocationMissing,
            enabled = !tempLocationMissing,
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
        if (tempLocationMissing) {
            Text(
                text = stringResource(R.string.home_logging_blocked),
                color = MaterialTheme.colorScheme.error,
                fontSize = 13.sp
            )
        }
        SwitchRow(
            label = stringResource(R.string.home_force_logging),
            caption = stringResource(R.string.home_force_logging_caption),
            checked = appSettings.forceLogging,
            onCheckedChange = { coroutineScope.launch { appSettingsRepository.updateForceLogging(it) } }
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ---------------------------------------------------------- Trigger light
        Row(verticalAlignment = Alignment.CenterVertically) {
            val lightColor = when {
                !serviceEnabled -> MaterialTheme.colorScheme.outline
                isLogging && triggeredBy != null -> Color(0xFF43A047) // green: logging
                else -> Color(0xFFFFB300) // amber: armed, waiting for trigger
            }
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .background(lightColor, CircleShape)
            )
            Spacer(modifier = Modifier.width(10.dp))
            val trigger = triggeredBy
            Column {
                Text(
                    text = when {
                        !serviceEnabled -> stringResource(R.string.home_light_idle)
                        isLogging && trigger == "manual" -> stringResource(R.string.home_light_manual)
                        isLogging && trigger != null -> stringResource(R.string.home_light_triggered, trigger)
                        isLogging -> stringResource(R.string.home_light_logging)
                        else -> stringResource(R.string.home_light_armed)
                    },
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    text = currentLogFile?.let { stringResource(R.string.home_recording, it) }
                        ?: stringResource(R.string.home_no_trip),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // -------------------------------------------------------------- New Trip
        Button(
            enabled = isLogging,
            onClick = {
                context.startService(
                    Intent(context, DrivingService::class.java).apply { action = DrivingService.ACTION_NEW_TRIP }
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.home_new_trip))
        }

        // ------------------------------------------------------------- GPS tile
        if (appSettings.gpsEnabled) {
            val location = lastLocation
            val gpsLight = when {
                !serviceEnabled -> MaterialTheme.colorScheme.outline
                isLogging && location != null -> Color(0xFF43A047)
                else -> Color(0xFFFFB300)
            }
            val gpsStatus = when {
                !serviceEnabled -> stringResource(R.string.tile_service_off)
                !isLogging -> stringResource(R.string.tile_waiting_trigger)
                location == null -> stringResource(R.string.tile_waiting_fix)
                else -> null // connected: no status line
            }
            Spacer(modifier = Modifier.height(20.dp))
            StatusTile(title = stringResource(R.string.gps_tile_title), light = gpsLight, statusText = gpsStatus) {
                if (location != null) {
                    val us = appSettings.usUnits
                    val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(location.time))
                    StatusLine(stringResource(R.string.gps_last_fix), time)
                    StatusLine(
                        stringResource(R.string.gps_position),
                        String.format(Locale.getDefault(), "%.5f, %.5f", location.latitude, location.longitude)
                    )
                    StatusLine(
                        stringResource(R.string.gps_speed),
                        stringResource(
                            R.string.gps_speed_value,
                            String.format(Locale.getDefault(), "%.1f", UnitFormat.mpsToDisplay(location.speed.toDouble(), us)),
                            stringResource(if (us) R.string.unit_mph else R.string.unit_kph)
                        )
                    )
                    if (location.hasAccuracy()) {
                        StatusLine(stringResource(R.string.gps_accuracy), stringResource(R.string.gps_accuracy_value, location.accuracy))
                    }
                }
            }
        }

        // ------------------------------------------------------------- OBD tile
        if (appSettings.obdEnabled) {
            val connected = serviceEnabled && obdLink == ObdLinkState.CONNECTED
            val obdLight = when {
                !serviceEnabled || obdLink == ObdLinkState.IDLE -> MaterialTheme.colorScheme.outline
                connected -> Color(0xFF43A047)
                obdLink == ObdLinkState.TIMED_OUT -> Color(0xFFE53935)
                else -> Color(0xFFFFB300)
            }
            val obdStatus: String? = when {
                !serviceEnabled -> stringResource(R.string.tile_service_off)
                connected -> null // hidden when connected
                obdLink == ObdLinkState.IDLE -> stringResource(R.string.tile_idle)
                obdLink == ObdLinkState.WAITING_FOR_DEVICE -> stringResource(R.string.tile_waiting_trigger)
                obdLink == ObdLinkState.CONNECTING -> stringResource(R.string.tile_connecting)
                else -> stringResource(R.string.tile_timed_out)
            }
            Spacer(modifier = Modifier.height(20.dp))
            StatusTile(
                title = stringResource(R.string.obd_tile_title),
                light = obdLight,
                statusText = obdStatus,
                trailing = if (appSettings.showHybridCharge || appSettings.showFuelLevel) {
                    {
                        if (appSettings.showHybridCharge) {
                            ObdIndicator(R.drawable.ic_hybrid_battery, obdValues["5B"])
                        }
                        if (appSettings.showFuelLevel) {
                            ObdIndicator(R.drawable.ic_fuel_level, obdValues["2F"])
                        }
                    }
                } else null
            ) {
                val speedUnit = stringResource(if (appSettings.usUnits) R.string.unit_mph else R.string.unit_kph)
                val speedMax = (if (appSettings.usUnits) appSettings.speedGaugeMaxUs else appSettings.speedGaugeMaxMetric).toDouble()
                val speedMph = if (connected) obdValues["0D"]?.let { UnitFormat.convert("km/h", it, appSettings.usUnits).first } else null
                val rpm = if (connected) obdValues["0C"] else null
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    Gauge(label = speedUnit.uppercase(), value = speedMph, maxValue = speedMax, color = Color(0xFF1464FF))
                    Gauge(label = stringResource(R.string.obd_gauge_rpm), value = rpm, maxValue = appSettings.rpmGaugeMax.toDouble(), color = Color(0xFFE53935))
                }
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

// ------------------------------------------------------------------------------------------------
// Home composables
// ------------------------------------------------------------------------------------------------

@Composable
private fun SwitchRow(
    label: String,
    caption: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 16.sp)
            if (caption != null) {
                Text(caption, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(modifier = Modifier.size(8.dp))
        Switch(checked = checked, enabled = enabled, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun StatusTile(
    title: String,
    light: Color,
    statusText: String? = null,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .background(light, CircleShape)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(title, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(modifier = Modifier.weight(1f))
                if (trailing != null) {
                    trailing()
                }
            }
            if (statusText != null) {
                Text(
                    text = statusText,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp, start = 24.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            content()
        }
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = label,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(90.dp)
        )
        Text(value, fontSize = 14.sp)
    }
}

/** Compact icon + value shown in the top-right corner of the OBD-II card. */
@Composable
private fun ObdIndicator(iconRes: Int, value: Double?) {
    val text = if (value == null) stringResource(R.string.obd_indicator_none)
    else stringResource(R.string.obd_indicator_percent, value)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(start = 10.dp)
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.width(2.dp))
        Text(
            text = text,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Canvas arc gauge (240° sweep, open at the bottom) with the value in the middle.
 * Shows "--" when there is no reading yet.
 */
@Composable
private fun Gauge(
    label: String,
    value: Double?,
    maxValue: Double,
    color: Color
) {
    val fraction = ((value ?: 0.0) / maxValue).coerceIn(0.0, 1.0).toFloat()
    val trackColor = MaterialTheme.colorScheme.outlineVariant
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier.size(150.dp),
            contentAlignment = Alignment.Center
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val stroke = size.minDimension * 0.085f
                val arcSize = Size(size.width - stroke, size.height - stroke)
                val topLeft = Offset(stroke / 2f, stroke / 2f)
                drawArc(
                    color = trackColor,
                    startAngle = 150f,
                    sweepAngle = 240f,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                if (fraction > 0f) {
                    drawArc(
                        color = color,
                        startAngle = 150f,
                        sweepAngle = 240f * fraction,
                        useCenter = false,
                        topLeft = topLeft,
                        size = arcSize,
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = value?.let { String.format(Locale.getDefault(), "%.0f", it) } ?: "--",
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
    }
}

enum class AppDestinations(
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    HOME(R.string.nav_home, Icons.Default.Home),
    SETTINGS(R.string.nav_settings, Icons.Default.Settings),
}
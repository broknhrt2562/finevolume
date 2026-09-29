package dev.finevolume.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dagger.hilt.android.AndroidEntryPoint
import dev.finevolume.app.domain.model.BackendType
import dev.finevolume.app.domain.model.VolumeCapabilities
import dev.finevolume.app.ui.theme.FineVolumeTheme
import dev.finevolume.app.ui.viewmodel.MainViewModel

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            FineVolumeTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val settings by viewModel.settings.collectAsState()
                    val capabilities by viewModel.activeBackendCapabilities.collectAsState()
                    val shizukuConnected by viewModel.isShizukuConnected.collectAsState()
                    val shizukuPermission by viewModel.hasShizukuPermission.collectAsState()

                    MainScreen(
                        settings = settings,
                        capabilities = capabilities,
                        shizukuConnected = shizukuConnected,
                        shizukuPermission = shizukuPermission,
                        onToggleEnabled = viewModel::updateIsEnabled,
                        onOpenAccessibilitySettings = {
                            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        },
                        onRequestShizuku = { viewModel.requestShizukuPermission() },
                        onResolutionChange = viewModel::updateMediaResolution,
                        onIncrementChange = viewModel::updateMediaIncrement,
                        onLongPressIntervalChange = viewModel::updateLongPressRepeatInterval,
                        onToggleHaptic = viewModel::updateHapticEnabled
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    settings: dev.finevolume.app.data.AppSettings,
    capabilities: VolumeCapabilities,
    shizukuConnected: Boolean,
    shizukuPermission: Boolean,
    onToggleEnabled: (Boolean) -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestShizuku: () -> Unit,
    onResolutionChange: (Float) -> Unit,
    onIncrementChange: (Float) -> Unit,
    onLongPressIntervalChange: (Float) -> Unit,
    onToggleHaptic: (Boolean) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FineVolume", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            AppToggleCard(
                isEnabled = settings.isEnabled,
                onToggle = onToggleEnabled
            )

            BankingCompatibilityCard()

            StatusCard(
                shizukuConnected = shizukuConnected,
                shizukuPermission = shizukuPermission,
                capabilities = capabilities,
                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                onRequestShizuku = onRequestShizuku
            )

            ConfigurationCard(
                title = "Volume Resolution",
                value = settings.mediaResolution.toFloat(),
                range = 15f..500f,
                effectiveValue = capabilities.effectiveResolution.coerceAtMost(settings.mediaResolution),
                description = "How many levels between 0 and Max.",
                onValueChange = onResolutionChange
            )

            ConfigurationCard(
                title = "Standard Increment",
                value = settings.mediaIncrement.toFloat(),
                range = 1f..50f,
                effectiveValue = null,
                description = "Steps applied per volume button press (and per tick while holding).",
                onValueChange = onIncrementChange
            )

            ConfigurationCard(
                title = "Long-Press Speed",
                value = settings.longPressRepeatIntervalMs.toFloat(),
                range = 20f..500f,
                effectiveValue = null,
                description = "Interval between volume steps while holding a button (ms). Lower = faster.",
                onValueChange = onLongPressIntervalChange,
                // Invert display: show it as "speed" — lower ms = faster, higher ms = slower
                displayTransform = { ms -> ms.toInt().toString() + " ms" }
            )

            HapticFeedbackCard(
                hapticEnabled = settings.hapticLevel > 0,
                onToggle = onToggleHaptic
            )
        }
    }
}

@Composable
fun StatusCard(
    shizukuConnected: Boolean,
    shizukuPermission: Boolean,
    capabilities: VolumeCapabilities,
    onOpenAccessibilitySettings: () -> Unit,
    onRequestShizuku: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                "System Status",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))

            // Backend Status
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (capabilities.backendType == BackendType.SHIZUKU_PLAYER) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (capabilities.backendType == BackendType.SHIZUKU_PLAYER) Color(0xFF4CAF50) else Color(0xFFFF9800),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text("Active Backend", fontWeight = FontWeight.Medium)
                    Text(
                        text = capabilities.backendType.name,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f))
            Spacer(modifier = Modifier.height(12.dp))

            // Shizuku
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Shizuku Permission", fontWeight = FontWeight.Medium)
                    Text(
                        if (shizukuPermission) "Granted" else if (shizukuConnected) "Action Required" else "Not Connected",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (!shizukuPermission && shizukuConnected) {
                    Button(onClick = onRequestShizuku, shape = RoundedCornerShape(12.dp)) {
                        Text("Grant")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onOpenAccessibilitySettings,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Enable Accessibility Service")
            }
        }
    }
}

@Composable
fun ConfigurationCard(
    title: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    effectiveValue: Int?,
    description: String,
    onValueChange: (Float) -> Unit,
    displayTransform: ((Float) -> String)? = null
) {
    var sliderValue by androidx.compose.runtime.remember(value) {
        androidx.compose.runtime.mutableFloatStateOf(value)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = displayTransform?.invoke(sliderValue) ?: sliderValue.toInt().toString(),
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

            Spacer(modifier = Modifier.height(16.dp))
            Slider(
                value = sliderValue,
                onValueChange = { sliderValue = it },
                onValueChangeFinished = { onValueChange(sliderValue) },
                valueRange = range,
                steps = 0
            )

            AnimatedVisibility(visible = effectiveValue != null && effectiveValue < sliderValue.toInt()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.errorContainer)
                        .padding(12.dp)
                ) {
                    Text(
                        "Hardware limited to $effectiveValue. Enable Shizuku for full resolution.",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
fun AppToggleCard(
    isEnabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isEnabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        ),
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (isEnabled) "FineVolume Active" else "FineVolume Paused",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isEnabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isEnabled) {
                        "Active — Intercepting volume keys and displaying overlay."
                    } else {
                        "Paused — Default Android volume in use. Volume keys and overlays are not intercepted (safe for banking apps)."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isEnabled) MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Switch(
                checked = isEnabled,
                onCheckedChange = onToggle
            )
        }
    }
}

@Composable
fun HapticFeedbackCard(
    hapticEnabled: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = RoundedCornerShape(24.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Haptic Feedback",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (hapticEnabled) "Enabled — Tactile feedback on volume level changes" else "Disabled — Silent volume adjustment",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = hapticEnabled,
                onCheckedChange = onToggle
            )
        }
    }
}

@Composable
fun BankingCompatibilityCard() {
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Rounded.Security,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "App Compatibility & Security",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "If banking or security apps prompt you to disable accessibility or stop working due to FineVolume, you can use the Pause toggle above, or use:",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(14.dp))
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = Color(0xFF1A73E8).copy(alpha = 0.12f),
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .clickable {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/T31n/Geto")).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            context.startActivity(intent)
                        } catch (_: Exception) {}
                    }
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "GETO",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = 1.5.sp,
                        color = Color(0xFF1A73E8)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                        contentDescription = "Open Geto",
                        tint = Color(0xFF1A73E8),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
    }
}

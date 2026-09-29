package dev.finevolume.app.ui.overlay

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material.icons.automirrored.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.text.style.TextOverflow
import dev.finevolume.app.domain.AppVolumeManager
import dev.finevolume.app.service.HapticFeedbackManager
import dev.finevolume.app.data.SettingsRepository
import dev.finevolume.app.domain.model.AppVolumeSession
import kotlin.math.roundToInt
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VolumeOverlayController @Inject constructor(
    @ApplicationContext private val context: Context,
    val hapticFeedbackManager: HapticFeedbackManager,
    val settingsRepository: SettingsRepository,
    val appVolumeManager: AppVolumeManager
) : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private var windowManager: WindowManager? = null
    private var serviceContext: Context? = null
    private var collapsedView: ComposeView? = null
    private var expandedView: ComposeView? = null

    private val _volumeLevel = MutableStateFlow(0f)
    private val _isVisible = MutableStateFlow(false)
    private val _isExpanded = MutableStateFlow(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var hideJob: Job? = null
    private var dismissExpandedHandler: (() -> Unit)? = null
    // When true, dismissExpandedInternal() will fire onSettingsClick after removing the window
    private var pendingSettingsOpen = false

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedStateRegistryController.savedStateRegistry
    override val viewModelStore: ViewModelStore get() = store

    var onVolumeChange: ((Float) -> Unit)? = null
    var onSettingsClick: (() -> Unit)? = null
    var onMuteClick: (() -> Unit)? = null

    init {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED

        scope.launch {
            settingsRepository.settings.collect { settings ->
                if (!settings.isEnabled) {
                    detachCollapsedView()
                    dismissExpandedInternal()
                    _isVisible.value = false
                    _isExpanded.value = false
                }
            }
        }
    }

    fun attachContext(context: Context) {
        this.serviceContext = context
        this.windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    fun detachContext() {
        hideJob?.cancel()
        hideJob = null
        dismissExpandedHandler = null
        appVolumeManager.stopObserving()
        detachCollapsedView()
        dismissExpandedInternal()
        this.serviceContext = null
        this.windowManager = null
        this.onVolumeChange = null
        this.onSettingsClick = null
        this.onMuteClick = null
    }

    fun show(volume: Float) {
        if (serviceContext == null) return
        if (!settingsRepository.cachedSettings.isEnabled) return

        _volumeLevel.value = volume.coerceIn(0f, 1f)

        // If the expanded panel is open, just update volume and reset timer (don't recreate it)
        if (_isExpanded.value) {
            resetHideTimer()
            return
        }

        scope.launch {
            if (!_isVisible.value) {
                _isVisible.value = true
                attachCollapsedView()
            }
        }

        resetHideTimer()
    }

    private fun resetHideTimer() {
        hideJob?.cancel()
        val timeout = if (_isExpanded.value) 12000L else 3500L
        hideJob = scope.launch {
            delay(timeout)
            hide()
        }
    }

    private suspend fun hide() {
        withContext(Dispatchers.Main) {
            if (_isExpanded.value) {
                // Trigger the Compose animation-based close; it will call dismissExpandedInternal()
                dismissExpandedHandler?.invoke()
            } else {
                detachCollapsedView()
                _isVisible.value = false
            }
        }
    }

    val isExpanded: Boolean get() = _isExpanded.value

    fun dismissExpanded() {
        scope.launch {
            if (_isExpanded.value) {
                dismissExpandedHandler?.invoke() ?: dismissExpandedInternal()
            }
        }
    }

    private fun expandDialog() {
        if (!settingsRepository.cachedSettings.isEnabled) return
        if (_isExpanded.value) return
        _isExpanded.value = true
        resetHideTimer()

        scope.launch {
            attachExpandedView()
            delay(80)
            detachCollapsedView()
            _isVisible.value = false
        }
    }

    private fun attachCollapsedView() {
        if (collapsedView != null || expandedView != null || serviceContext == null) return
        if (!settingsRepository.cachedSettings.isEnabled) return

        try {
            // Ensure lifecycle is at least STARTED before creating ComposeView
            if (lifecycleRegistry.currentState < Lifecycle.State.STARTED) {
                lifecycleRegistry.currentState = Lifecycle.State.STARTED
            }
            if (lifecycleRegistry.currentState < Lifecycle.State.RESUMED) {
                lifecycleRegistry.currentState = Lifecycle.State.RESUMED
            }

            collapsedView = ComposeView(serviceContext!!).apply {
                setViewTreeLifecycleOwner(this@VolumeOverlayController)
                setViewTreeSavedStateRegistryOwner(this@VolumeOverlayController)
                setViewTreeViewModelStoreOwner(this@VolumeOverlayController)

                setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_OUTSIDE) {
                        scope.launch {
                            detachCollapsedView()
                            _isVisible.value = false
                        }
                        true
                    } else {
                        false
                    }
                }

                setContent {
                    PixelVolumeTheme {
                        val settings = settingsRepository.cachedSettings
                        CollapsedVolumeOverlay(
                            volumeFlow = _volumeLevel,
                            onVolumeChange = { v ->
                                _volumeLevel.value = v
                                onVolumeChange?.invoke(v)
                                resetHideTimer()
                            },
                            onTuneClick = {
                                expandDialog()
                            },
                            onInteraction = {
                                resetHideTimer()
                            },
                            resolution = settings.mediaResolution,
                            hapticLevel = settings.hapticLevel,
                            hapticFeedbackManager = hapticFeedbackManager
                        )
                    }
                }
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER_VERTICAL or Gravity.END
                x = (12 * serviceContext!!.resources.displayMetrics.density).toInt()
                y = 0
            }

            windowManager?.addView(collapsedView, params)
        } catch (t: Throwable) {
            t.printStackTrace()
            collapsedView = null
        }
    }

    private fun detachCollapsedView() {
        collapsedView?.let {
            try {
                windowManager?.removeView(it)
            } catch (t: Throwable) {
                t.printStackTrace()
            }
            collapsedView = null
        }
    }

    private fun attachExpandedView() {
        if (expandedView != null || serviceContext == null) return
        if (!settingsRepository.cachedSettings.isEnabled) return

        try {
            if (lifecycleRegistry.currentState < Lifecycle.State.STARTED) {
                lifecycleRegistry.currentState = Lifecycle.State.STARTED
            }
            if (lifecycleRegistry.currentState < Lifecycle.State.RESUMED) {
                lifecycleRegistry.currentState = Lifecycle.State.RESUMED
            }

            appVolumeManager.startObserving()

            val view = ComposeView(serviceContext!!).apply {
                setViewTreeLifecycleOwner(this@VolumeOverlayController)
                setViewTreeSavedStateRegistryOwner(this@VolumeOverlayController)
                setViewTreeViewModelStoreOwner(this@VolumeOverlayController)

                setContent {
                    PixelVolumeTheme {
                        ExpandedVolumeDialog(
                            volumeFlow = _volumeLevel,
                            onVolumeChange = { v ->
                                _volumeLevel.value = v
                                onVolumeChange?.invoke(v)
                                resetHideTimer()
                            },
                            onClose = {
                                dismissExpandedInternal()
                            },
                            onSettingsClick = {
                                pendingSettingsOpen = true
                                dismissExpandedHandler?.invoke() ?: dismissExpandedInternal()
                            },
                            onInteraction = {
                                resetHideTimer()
                            },
                            onRegisterDismissHandler = { handler ->
                                dismissExpandedHandler = handler
                            },
                            appVolumeManager = appVolumeManager,
                            settingsRepository = settingsRepository,
                            hapticFeedbackManager = hapticFeedbackManager
                        )
                    }
                }
            }

            expandedView = view

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.FILL
                x = 0
                y = 0
            }

            windowManager?.addView(expandedView, params)
        } catch (t: Throwable) {
            t.printStackTrace()
            expandedView = null
            _isExpanded.value = false
            _isVisible.value = false
        }
    }

    private fun dismissExpandedInternal() {
        appVolumeManager.stopObserving()
        val view = expandedView ?: return
        expandedView = null
        dismissExpandedHandler = null
        try {
            windowManager?.removeView(view)
        } catch (t: Throwable) {
            t.printStackTrace()
        }
        _isExpanded.value = false
        _isVisible.value = false
        // Fire settings AFTER the window is fully removed from WindowManager
        if (pendingSettingsOpen) {
            pendingSettingsOpen = false
            try {
                onSettingsClick?.invoke()
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
    }
}

object LiveCaptionHelper {
    fun isEnabled(context: Context): Boolean {
        return try {
            val odi = Settings.Secure.getInt(context.contentResolver, "odi_captions_enabled", -1)
            if (odi != -1) {
                odi == 1
            } else {
                val acc = Settings.Secure.getInt(context.contentResolver, "accessibility_captioning_enabled", -1)
                if (acc != -1) {
                    acc == 1
                } else {
                    val captioning = context.getSystemService(Context.CAPTIONING_SERVICE) as? android.view.accessibility.CaptioningManager
                    captioning?.isEnabled ?: false
                }
            }
        } catch (_: Throwable) {
            false
        }
    }

    fun toggle(context: Context, onStateChanged: (Boolean) -> Unit) {
        val currentState = isEnabled(context)
        val targetState = !currentState
        val targetVal = if (targetState) 1 else 0
        var toggled = false

        // 1. Try direct Settings.Secure write (works if WRITE_SECURE_SETTINGS is granted)
        try {
            Settings.Secure.putInt(context.contentResolver, "odi_captions_enabled", targetVal)
            Settings.Secure.putInt(context.contentResolver, "accessibility_captioning_enabled", targetVal)
            toggled = true
        } catch (_: Throwable) {}

        // 2. Try Shizuku shell process via reflection if available and granted
        if (!toggled) {
            try {
                if (rikka.shizuku.Shizuku.pingBinder() &&
                    rikka.shizuku.Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    val newProcMethod = rikka.shizuku.Shizuku::class.java.getDeclaredMethod(
                        "newProcess",
                        Array<String>::class.java,
                        Array<String>::class.java,
                        String::class.java
                    )
                    newProcMethod.isAccessible = true
                    val p = newProcMethod.invoke(
                        null,
                        arrayOf("settings", "put", "secure", "odi_captions_enabled", targetVal.toString()),
                        null,
                        null
                    ) as? java.lang.Process
                    p?.waitFor()

                    val p2 = newProcMethod.invoke(
                        null,
                        arrayOf("settings", "put", "secure", "accessibility_captioning_enabled", targetVal.toString()),
                        null,
                        null
                    ) as? java.lang.Process
                    p2?.waitFor()
                    toggled = true
                }
            } catch (_: Throwable) {}
        }

        // 3. Try Root su if not toggled
        if (!toggled) {
            try {
                val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "settings put secure odi_captions_enabled $targetVal; settings put secure accessibility_captioning_enabled $targetVal"))
                if (p.waitFor() == 0) {
                    toggled = true
                }
            } catch (_: Throwable) {}
        }

        if (toggled) {
            onStateChanged(targetState)
            Toast.makeText(
                context,
                if (targetState) "Live Caption On" else "Live Caption Off",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        // 4. Intent fallback: Launch system Live Caption / caption settings directly
        val intents = listOf(
            Intent("com.android.settings.action.LIVE_CAPTION"),
            Intent(Settings.ACTION_CAPTIONING_SETTINGS),
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        )
        for (intent in intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                Toast.makeText(context, "Opening Live Caption Settings", Toast.LENGTH_SHORT).show()
                return
            } catch (_: Throwable) {}
        }
    }
}

// ─── Theme & Colors ───────────────────────────────────────────────────────────

data class PixelColors(
    val panelBg: Color,
    val activeColor: Color,
    val inactiveColor: Color,
    val onActiveColor: Color,
    val onInactiveColor: Color
)

@Composable
fun PixelVolumeTheme(content: @Composable () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val darkTheme = isSystemInDarkTheme()
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (darkTheme) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

@Composable
fun rememberPixelColors(): PixelColors {
    val isDark = isSystemInDarkTheme()
    val scheme = MaterialTheme.colorScheme
    return remember(isDark, scheme) {
        PixelColors(
            panelBg = scheme.surfaceContainerHigh,
            activeColor = scheme.primary,
            inactiveColor = scheme.surfaceContainerLowest,
            onActiveColor = scheme.onPrimary,
            onInactiveColor = scheme.onSurface
        )
    }
}

// ─── Collapsed Overlay ───────────────────────────────────────────────────────

@Composable
fun CollapsedVolumeOverlay(
    volumeFlow: MutableStateFlow<Float>,
    onVolumeChange: (Float) -> Unit,
    onTuneClick: () -> Unit,
    onInteraction: () -> Unit,
    resolution: Int = 120,
    hapticLevel: Int = 1,
    hapticFeedbackManager: HapticFeedbackManager? = null
) {
    // Collect the volume reactively so recomposition happens on every update
    val volume by volumeFlow.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    var isCaptionActive by remember { mutableStateOf(LiveCaptionHelper.isEnabled(context)) }

    DisposableEffect(context) {
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                isCaptionActive = LiveCaptionHelper.isEnabled(context)
            }
        }
        try {
            val uri = Settings.Secure.getUriFor("odi_captions_enabled")
                ?: Settings.Secure.getUriFor("accessibility_captioning_enabled")
            if (uri != null) {
                context.contentResolver.registerContentObserver(uri, false, observer)
            }
        } catch (_: Throwable) {}
        onDispose {
            try { context.contentResolver.unregisterContentObserver(observer) } catch (_: Throwable) {}
        }
    }

    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    var currentRingerMode by remember { mutableStateOf(audioManager.ringerMode) }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                try { currentRingerMode = audioManager.ringerMode } catch (_: Exception) {}
            }
        }
        val filter = IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        } catch (e: Exception) { e.printStackTrace() }
        onDispose {
            try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }

    val colors = rememberPixelColors()

    Surface(
        modifier = Modifier.padding(end = 4.dp),
        shape = RoundedCornerShape(18.dp),
        color = colors.panelBg,
        shadowElevation = 8.dp
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .width(64.dp)
                .padding(horizontal = 8.dp, vertical = 10.dp)
        ) {
            // 1. Ringer button (Top)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.activeColor)
                    .clickable {
                        onInteraction()
                        currentRingerMode = cycleRingerMode(context, audioManager)
                    }
            ) {
                Icon(
                    imageVector = when (currentRingerMode) {
                        AudioManager.RINGER_MODE_SILENT -> Icons.Rounded.NotificationsOff
                        AudioManager.RINGER_MODE_VIBRATE -> Icons.Rounded.Vibration
                        else -> Icons.Rounded.Notifications
                    },
                    contentDescription = "Ringer Mode",
                    tint = colors.onActiveColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 2. Vertical volume slider — receives the live volume value
            PixelVerticalSlider(
                value = volume,
                onVolumeChange = onVolumeChange,
                onInteraction = onInteraction,
                colors = colors,
                streamIcon = Icons.Rounded.MusicNote,
                resolution = resolution,
                hapticLevel = hapticLevel,
                hapticFeedbackManager = hapticFeedbackManager,
                modifier = Modifier
                    .width(48.dp)
                    .height(230.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 3. Live Caption button
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (isCaptionActive) colors.activeColor else colors.inactiveColor)
                    .clickable {
                        onInteraction()
                        LiveCaptionHelper.toggle(context) { newState ->
                            isCaptionActive = newState
                        }
                    }
            ) {
                Icon(
                    imageVector = if (isCaptionActive) Icons.Rounded.ClosedCaption else Icons.Rounded.ClosedCaptionOff,
                    contentDescription = "Live Caption",
                    tint = if (isCaptionActive) colors.onActiveColor else colors.onInactiveColor,
                    modifier = Modifier.size(24.dp)
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            // 4. Tune / expand button (Bottom)
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable { onTuneClick() }
            ) {
                Icon(
                    imageVector = Icons.Rounded.Tune,
                    contentDescription = "Expand Volume",
                    tint = colors.onInactiveColor,
                    modifier = Modifier.size(26.dp)
                )
            }
        }
    }
}

// ─── Expanded Dialog ─────────────────────────────────────────────────────────

@Composable
fun ExpandedVolumeDialog(
    volumeFlow: MutableStateFlow<Float>,
    onVolumeChange: (Float) -> Unit,
    onClose: () -> Unit,
    onSettingsClick: () -> Unit,
    onInteraction: () -> Unit,
    onRegisterDismissHandler: ((() -> Unit) -> Unit)? = null,
    appVolumeManager: AppVolumeManager,
    settingsRepository: SettingsRepository,
    hapticFeedbackManager: HapticFeedbackManager
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }

    val settings by settingsRepository.settings.collectAsState(initial = settingsRepository.cachedSettings)
    val resolution = settings.mediaResolution.coerceAtLeast(1)
    val hapticLevel = settings.hapticLevel
    val activeApps by appVolumeManager.activeAppSessions.collectAsState()
    val isShizukuReady = remember(appVolumeManager) { appVolumeManager.isShizukuReady() }

    // Collect media volume reactively
    val mediaVolume by volumeFlow.collectAsState()

    var isCaptionActive by remember { mutableStateOf(LiveCaptionHelper.isEnabled(context)) }

    DisposableEffect(context) {
        val observer = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                isCaptionActive = LiveCaptionHelper.isEnabled(context)
            }
        }
        try {
            val uri = Settings.Secure.getUriFor("odi_captions_enabled")
                ?: Settings.Secure.getUriFor("accessibility_captioning_enabled")
            if (uri != null) {
                context.contentResolver.registerContentObserver(uri, false, observer)
            }
        } catch (_: Throwable) {}
        onDispose {
            try { context.contentResolver.unregisterContentObserver(observer) } catch (_: Throwable) {}
        }
    }

    val ringMax = remember {
        try {
            audioManager.getStreamMaxVolume(AudioManager.STREAM_RING).toFloat().coerceAtLeast(1f)
        } catch (_: Throwable) { 1f }
    }
    var ringVol by remember {
        mutableStateOf(
            try {
                audioManager.getStreamVolume(AudioManager.STREAM_RING) / ringMax
            } catch (_: Throwable) { 0.5f }
        )
    }

    val notifMax = remember {
        try {
            audioManager.getStreamMaxVolume(AudioManager.STREAM_NOTIFICATION).toFloat().coerceAtLeast(1f)
        } catch (_: Throwable) { 1f }
    }
    var notifVol by remember {
        mutableStateOf(
            try {
                audioManager.getStreamVolume(AudioManager.STREAM_NOTIFICATION) / notifMax
            } catch (_: Throwable) { 0.5f }
        )
    }

    val callMax = remember {
        try {
            audioManager.getStreamMaxVolume(AudioManager.STREAM_VOICE_CALL).toFloat().coerceAtLeast(1f)
        } catch (_: Throwable) { 1f }
    }
    var callVol by remember {
        mutableStateOf(
            try {
                audioManager.getStreamVolume(AudioManager.STREAM_VOICE_CALL) / callMax
            } catch (_: Throwable) { 0.5f }
        )
    }

    val alarmMax = remember {
        try {
            audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM).toFloat().coerceAtLeast(1f)
        } catch (_: Throwable) { 1f }
    }
    var alarmVol by remember {
        mutableStateOf(
            try {
                audioManager.getStreamVolume(AudioManager.STREAM_ALARM) / alarmMax
            } catch (_: Throwable) { 0.5f }
        )
    }

    val isRingMuted = try {
        audioManager.ringerMode != AudioManager.RINGER_MODE_NORMAL || ringVol == 0f
    } catch (_: Throwable) { false }

    val colors = rememberPixelColors()

    var isDialogVisible by remember { mutableStateOf(false) }
    val isDismissing = remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val openTime = remember { android.os.SystemClock.uptimeMillis() }

    val handleClose: () -> Unit = remember(onClose) {
        {
            if (!isDismissing.value) {
                isDismissing.value = true
                scope.launch {
                    isDialogVisible = false
                    delay(150)
                    onClose()
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        onRegisterDismissHandler?.invoke(handleClose)
        isDialogVisible = true
    }

    // Fullscreen transparent overlay — tapping outside closes the panel
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Transparent)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                if (android.os.SystemClock.uptimeMillis() - openTime > 350L) {
                    handleClose()
                }
            },
        contentAlignment = Alignment.BottomCenter
    ) {
        AnimatedVisibility(
            visible = isDialogVisible,
            enter = fadeIn(animationSpec = tween(150)),
            exit = fadeOut(animationSpec = tween(100))
        ) {
            Surface(
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp, bottomStart = 0.dp, bottomEnd = 0.dp),
                color = colors.panelBg,
                shadowElevation = 16.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {} // absorb clicks
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                        .padding(horizontal = 20.dp)
                        .padding(top = 20.dp, bottom = 20.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "Audio will play on",
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                            )
                            Text(
                                text = "This phone",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        Icon(
                            imageVector = Icons.Rounded.PhoneAndroid,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f),
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(18.dp))

                    // SECTION 1: SYSTEM VOLUME
                    Text(
                        text = "SYSTEM VOLUME",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    // Media
                    PixelHorizontalSlider(
                        value = mediaVolume,
                        onValueChange = onVolumeChange,
                        onInteraction = onInteraction,
                        colors = colors,
                        title = "Media",
                        resolution = resolution,
                        hapticLevel = hapticLevel,
                        hapticFeedbackManager = hapticFeedbackManager,
                        trailingIcon = Icons.Rounded.MusicNote,
                        onTrailingIconClick = {
                            onVolumeChange(if (mediaVolume > 0f) 0f else 0.5f)
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Call
                    PixelHorizontalSlider(
                        value = callVol,
                        onValueChange = { v ->
                            callVol = v
                            val index = (v * callMax).toInt().coerceIn(0, callMax.toInt())
                            try {
                                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, index, 0)
                            } catch (_: Throwable) {}
                        },
                        onInteraction = onInteraction,
                        colors = colors,
                        title = "Call",
                        resolution = resolution,
                        hapticLevel = hapticLevel,
                        hapticFeedbackManager = hapticFeedbackManager,
                        trailingIcon = Icons.Rounded.Call,
                        onTrailingIconClick = {
                            val target = if (callVol > 0f) 0 else (callMax * 0.5f).toInt()
                            try {
                                audioManager.setStreamVolume(AudioManager.STREAM_VOICE_CALL, target, 0)
                            } catch (_: Throwable) {}
                            callVol = target / callMax
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Ring
                    PixelHorizontalSlider(
                        value = ringVol,
                        onValueChange = { v ->
                            ringVol = v
                            val index = (v * ringMax).toInt().coerceIn(0, ringMax.toInt())
                            try {
                                audioManager.setStreamVolume(AudioManager.STREAM_RING, index, 0)
                            } catch (_: Throwable) {}
                        },
                        onInteraction = onInteraction,
                        colors = colors,
                        title = "Ring",
                        resolution = resolution,
                        hapticLevel = hapticLevel,
                        hapticFeedbackManager = hapticFeedbackManager,
                        trailingIcon = if (ringVol == 0f || isRingMuted) Icons.Rounded.NotificationsOff else Icons.Rounded.Notifications,
                        onTrailingIconClick = {
                            val target = if (ringVol > 0f) 0 else (ringMax * 0.5f).toInt()
                            try {
                                audioManager.setStreamVolume(AudioManager.STREAM_RING, target, 0)
                            } catch (_: Throwable) {}
                            ringVol = target / ringMax
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Notification
                    PixelHorizontalSlider(
                        value = if (isRingMuted) 0f else notifVol,
                        onValueChange = { v ->
                            if (!isRingMuted) {
                                notifVol = v
                                val index = (v * notifMax).toInt().coerceIn(0, notifMax.toInt())
                                try {
                                    audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, index, 0)
                                } catch (_: Throwable) {}
                            }
                        },
                        onInteraction = onInteraction,
                        colors = colors,
                        title = "Notification",
                        resolution = resolution,
                        hapticLevel = hapticLevel,
                        hapticFeedbackManager = hapticFeedbackManager,
                        trailingIcon = if (notifVol == 0f || isRingMuted) Icons.Rounded.NotificationsOff else Icons.Rounded.Notifications,
                        onTrailingIconClick = {
                            if (!isRingMuted) {
                                val target = if (notifVol > 0f) 0 else (notifMax * 0.5f).toInt()
                                try {
                                    audioManager.setStreamVolume(AudioManager.STREAM_NOTIFICATION, target, 0)
                                } catch (_: Throwable) {}
                                notifVol = target / notifMax
                            }
                        }
                    )

                    if (isRingMuted) {
                        Spacer(modifier = Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Unavailable because ring is muted",
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                fontSize = 12.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Alarm
                    PixelHorizontalSlider(
                        value = alarmVol,
                        onValueChange = { v ->
                            alarmVol = v
                            val index = (v * alarmMax).toInt().coerceIn(0, alarmMax.toInt())
                            try {
                                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, index, 0)
                            } catch (_: Throwable) {}
                        },
                        onInteraction = onInteraction,
                        colors = colors,
                        title = "Alarm",
                        resolution = resolution,
                        hapticLevel = hapticLevel,
                        hapticFeedbackManager = hapticFeedbackManager,
                        trailingIcon = Icons.Rounded.Alarm,
                        onTrailingIconClick = {
                            val target = if (alarmVol > 0f) 0 else (alarmMax * 0.5f).toInt()
                            try {
                                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, target, 0)
                            } catch (_: Throwable) {}
                            alarmVol = target / alarmMax
                        }
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    // SECTION 2: ACTIVE APPS
                    Text(
                        text = "ACTIVE APPS",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    if (isShizukuReady) {
                        if (activeApps.isNotEmpty()) {
                            activeApps.forEach { app ->
                                PixelHorizontalSlider(
                                    value = app.volume,
                                    onValueChange = { newVol ->
                                        appVolumeManager.setAppVolume(app.packageName, newVol)
                                    },
                                    onInteraction = onInteraction,
                                    colors = colors,
                                    title = app.appName,
                                    leadingAppIcon = app.icon,
                                    resolution = resolution,
                                    hapticLevel = hapticLevel,
                                    hapticFeedbackManager = hapticFeedbackManager,
                                    trailingIcon = if (app.volume > 0f) Icons.AutoMirrored.Rounded.VolumeUp else Icons.AutoMirrored.Rounded.VolumeOff,
                                    onTrailingIconClick = {
                                        val newVol = if (app.volume > 0f) 0f else 1.0f
                                        appVolumeManager.setAppVolume(app.packageName, newVol)
                                    }
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                        } else {
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.MusicNote,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "No apps actively playing audio",
                                        fontSize = 13.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                                    )
                                }
                            }
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Rounded.Security,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Per-App Audio Control",
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 14.sp,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "Controlling individual app volumes independently requires elevated Shizuku access so Android allows audio session attenuation.",
                                    fontSize = 12.sp,
                                    lineHeight = 16.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(10.dp))
                                Button(
                                    onClick = {
                                        try {
                                            val intent = context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                                            if (intent != null) {
                                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                context.startActivity(intent)
                                            } else {
                                                val browserIntent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://shizuku.rikka.app/"))
                                                browserIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                                context.startActivity(browserIntent)
                                            }
                                        } catch (_: Throwable) {}
                                    },
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Text("Open Shizuku", fontSize = 12.sp)
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Live Caption toggle
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(width = 88.dp, height = 44.dp)
                                .clip(RoundedCornerShape(22.dp))
                                .background(if (isCaptionActive) colors.activeColor else colors.inactiveColor)
                                .clickable {
                                    onInteraction()
                                    LiveCaptionHelper.toggle(context) { newState ->
                                        isCaptionActive = newState
                                    }
                                }
                        ) {
                            Icon(
                                imageVector = if (isCaptionActive) Icons.Rounded.ClosedCaption else Icons.Rounded.ClosedCaptionOff,
                                contentDescription = "Live Caption",
                                tint = if (isCaptionActive) colors.onActiveColor else colors.onInactiveColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Live Caption",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }

                    Spacer(modifier = Modifier.height(20.dp))

                    // Settings & Done buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outline), RoundedCornerShape(20.dp))
                                .clickable {
                                    onSettingsClick()
                                }
                                .padding(horizontal = 22.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = "Settings",
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }

                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(colors.activeColor)
                                .clickable { handleClose() }
                                .padding(horizontal = 26.dp, vertical = 10.dp)
                        ) {
                            Text(
                                text = "Done",
                                color = colors.onActiveColor,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

// ─── Horizontal Slider ───────────────────────────────────────────────────────

/**
 * Android 17-style horizontal slider.
 * The divider handle is rendered as an unclipped overlay layer — it always extends
 * 5dp above and below the 56dp track, giving a 66dp total divider height.
 * At 0% and 100% the handle is clamped to the track edges without deformation.
 */
@Composable
fun PixelHorizontalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onInteraction: () -> Unit,
    colors: PixelColors,
    trailingIcon: ImageVector,
    onTrailingIconClick: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    leadingAppIcon: ImageBitmap? = null,
    resolution: Int = 120,
    hapticLevel: Int = 1,
    hapticFeedbackManager: HapticFeedbackManager? = null
) {
    var isDragging by remember { mutableStateOf(false) }
    var trackWidthPx by remember { mutableStateOf(0f) }
    var lastReportedStep by remember { mutableStateOf(-1) }
    var lastHapticTime by remember { mutableStateOf(0L) }
    val density = LocalDensity.current
    val res = resolution.coerceAtLeast(1)

    val animatedValue by animateFloatAsState(
        targetValue = value.coerceIn(0f, 1f),
        animationSpec = if (isDragging) tween(0) else spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "h_slider_anim"
    )

    val percent = (animatedValue * 100f).roundToInt().coerceIn(0, 100)

    val triggerHaptic: () -> Unit = {
        if (hapticLevel > 0) {
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastHapticTime >= 35L) {
                lastHapticTime = now
                hapticFeedbackManager?.vibrate(hapticLevel)
            }
        }
    }

    val trackHeight = 56.dp
    val dividerThickness = 5.5.dp
    val dividerHeight = 66.dp
    val gap = 3.dp

    val dividerThicknessPx = with(density) { dividerThickness.toPx() }
    val gapPx = with(density) { gap.toPx() }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(dividerHeight)
            .onGloballyPositioned { coordinates ->
                trackWidthPx = coordinates.size.width.toFloat()
            }
            .pointerInput(res, hapticLevel) {
                detectHorizontalDragGestures(
                    onDragStart = { isDragging = true; onInteraction() },
                    onDragEnd = { isDragging = false; onInteraction() },
                    onDragCancel = { isDragging = false; onInteraction() }
                ) { change, _ ->
                    change.consume()
                    if (trackWidthPx > 0f) {
                        val raw = (change.position.x / trackWidthPx).coerceIn(0f, 1f)
                        val step = (raw * res.toFloat()).roundToInt().coerceIn(0, res)
                        if (step != lastReportedStep) {
                            lastReportedStep = step
                            onValueChange(step.toFloat() / res)
                            triggerHaptic()
                        }
                        onInteraction()
                    }
                }
            }
            .pointerInput(res, hapticLevel) {
                detectTapGestures { offset ->
                    if (trackWidthPx > 0f) {
                        val raw = (offset.x / trackWidthPx).coerceIn(0f, 1f)
                        val step = (raw * res.toFloat()).roundToInt().coerceIn(0, res)
                        lastReportedStep = step
                        onValueChange(step.toFloat() / res)
                        triggerHaptic()
                        onInteraction()
                    }
                }
            }
    ) {
        if (trackWidthPx > 0f) {
            val halfDiv = dividerThicknessPx / 2f
            val dividerCenterPx = (animatedValue * trackWidthPx).coerceIn(halfDiv, trackWidthPx - halfDiv)
            val dividerLeftPx = dividerCenterPx - halfDiv

            // Active track capsule (left of divider center)
            val activeEndPx = (dividerCenterPx - halfDiv - gapPx).coerceAtLeast(0f)
            if (activeEndPx > 0f) {
                val activeWidthDp = with(density) { activeEndPx.toDp() }
                Box(
                    modifier = Modifier
                        .width(activeWidthDp)
                        .height(trackHeight)
                        .align(Alignment.CenterStart)
                        .clip(
                            RoundedCornerShape(
                                topStart = 14.dp,
                                bottomStart = 14.dp,
                                topEnd = if (animatedValue >= 0.99f) 14.dp else 4.dp,
                                bottomEnd = if (animatedValue >= 0.99f) 14.dp else 4.dp
                            )
                        )
                        .background(colors.activeColor)
                )
            }

            // Inactive track capsule (right of divider center)
            val inactiveStartPx = (dividerCenterPx + halfDiv + gapPx).coerceAtMost(trackWidthPx)
            val inactiveWidthPx = (trackWidthPx - inactiveStartPx).coerceAtLeast(0f)
            if (inactiveWidthPx > 0f) {
                val inactiveStartDp = with(density) { inactiveStartPx.toDp() }
                val inactiveWidthDp = with(density) { inactiveWidthPx.toDp() }
                Box(
                    modifier = Modifier
                        .offset(x = inactiveStartDp)
                        .width(inactiveWidthDp)
                        .height(trackHeight)
                        .align(Alignment.CenterStart)
                        .clip(
                            RoundedCornerShape(
                                topStart = if (animatedValue <= 0.01f) 14.dp else 4.dp,
                                bottomStart = if (animatedValue <= 0.01f) 14.dp else 4.dp,
                                topEnd = 14.dp,
                                bottomEnd = 14.dp
                            )
                        )
                        .background(colors.inactiveColor)
                )
            }

            // Divider handle
            val dividerLeftDp = with(density) { dividerLeftPx.toDp() }
            Box(
                modifier = Modifier
                    .offset(x = dividerLeftDp)
                    .requiredWidth(dividerThickness)
                    .requiredHeight(dividerHeight)
                    .align(Alignment.CenterStart)
                    .clip(RoundedCornerShape(2.75.dp))
                    .background(colors.activeColor)
            )
        }

        // Inside the capsule: Title, App Icon, Live Percentage, and Trailing Icon
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .align(Alignment.CenterStart)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (leadingAppIcon != null) {
                Image(
                    bitmap = leadingAppIcon,
                    contentDescription = null,
                    modifier = Modifier
                        .size(24.dp)
                        .clip(RoundedCornerShape(6.dp))
                )
                Spacer(modifier = Modifier.width(10.dp))
            }

            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (animatedValue > 0.25f) colors.onActiveColor else colors.onInactiveColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "$percent%",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (animatedValue > 0.68f) colors.onActiveColor else colors.onInactiveColor
            )

            Spacer(modifier = Modifier.width(6.dp))

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(36.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        onTrailingIconClick()
                        onInteraction()
                    }
            ) {
                Icon(
                    imageVector = trailingIcon,
                    contentDescription = "$title, volume $percent percent",
                    tint = if (animatedValue > 0.86f) colors.onActiveColor else colors.onInactiveColor,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

// ─── Vertical Slider ─────────────────────────────────────────────────────────

/**
 * Android 17-style vertical slider.
 * The divider handle extends 5dp to the left and right of the 48dp track (58dp total divider width).
 * Rendered as an unclipped overlay on top of the track capsules.
 */
@Composable
fun PixelVerticalSlider(
    value: Float,
    onVolumeChange: (Float) -> Unit,
    onInteraction: () -> Unit,
    colors: PixelColors,
    streamIcon: ImageVector,
    modifier: Modifier = Modifier,
    resolution: Int = 120,
    hapticLevel: Int = 1,
    hapticFeedbackManager: HapticFeedbackManager? = null
) {
    var isDragging by remember { mutableStateOf(false) }
    var trackHeightPx by remember { mutableStateOf(0f) }
    var lastReportedStep by remember { mutableStateOf(-1) }
    var lastHapticTime by remember { mutableStateOf(0L) }
    val density = LocalDensity.current
    val res = resolution.coerceAtLeast(1)

    val animatedVolume by animateFloatAsState(
        targetValue = value.coerceIn(0f, 1f),
        animationSpec = if (isDragging) tween(0) else spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessHigh
        ),
        label = "v_slider_anim"
    )

    val percent = (animatedVolume * 100f).roundToInt().coerceIn(0, 100)

    val dividerThickness = 5.5.dp
    val dividerWidth = 58.dp
    val gap = 3.dp

    val dividerThicknessPx = with(density) { dividerThickness.toPx() }
    val gapPx = with(density) { gap.toPx() }

    Box(
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                trackHeightPx = coordinates.size.height.toFloat()
            }
            .pointerInput(res, hapticLevel) {
                detectVerticalDragGestures(
                    onDragStart = { isDragging = true; onInteraction() },
                    onDragEnd = { isDragging = false; onInteraction() },
                    onDragCancel = { isDragging = false; onInteraction() }
                ) { change, _ ->
                    change.consume()
                    if (trackHeightPx > 0f) {
                        val raw = 1f - (change.position.y / trackHeightPx).coerceIn(0f, 1f)
                        val step = (raw * res.toFloat()).roundToInt().coerceIn(0, res)
                        if (step != lastReportedStep) {
                            lastReportedStep = step
                            onVolumeChange(step.toFloat() / res)
                            if (hapticLevel > 0) {
                                val now = android.os.SystemClock.uptimeMillis()
                                if (now - lastHapticTime >= 35L) {
                                    lastHapticTime = now
                                    hapticFeedbackManager?.vibrate(hapticLevel)
                                }
                            }
                        }
                        onInteraction()
                    }
                }
            }
            .pointerInput(res, hapticLevel) {
                detectTapGestures { offset ->
                    if (trackHeightPx > 0f) {
                        val raw = 1f - (offset.y / trackHeightPx).coerceIn(0f, 1f)
                        val step = (raw * res.toFloat()).roundToInt().coerceIn(0, res)
                        lastReportedStep = step
                        onVolumeChange(step.toFloat() / res)
                        if (hapticLevel > 0) {
                            val now = android.os.SystemClock.uptimeMillis()
                            if (now - lastHapticTime >= 35L) {
                                lastHapticTime = now
                                hapticFeedbackManager?.vibrate(hapticLevel)
                            }
                        }
                        onInteraction()
                    }
                }
            }
    ) {
        if (trackHeightPx > 0f) {
            val halfDiv = dividerThicknessPx / 2f
            val dividerCenterY = ((1f - animatedVolume) * trackHeightPx).coerceIn(halfDiv, trackHeightPx - halfDiv)
            val dividerTopPx = dividerCenterY - halfDiv

            // Inactive track (above divider)
            val inactiveEndPx = (dividerCenterY - halfDiv - gapPx).coerceAtLeast(0f)
            if (inactiveEndPx > 0f) {
                val inactiveHeightDp = with(density) { inactiveEndPx.toDp() }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(inactiveHeightDp)
                        .align(Alignment.TopCenter)
                        .clip(
                            RoundedCornerShape(
                                topStart = 14.dp,
                                topEnd = 14.dp,
                                bottomStart = if (animatedVolume <= 0.01f) 14.dp else 4.dp,
                                bottomEnd = if (animatedVolume <= 0.01f) 14.dp else 4.dp
                            )
                        )
                        .background(colors.inactiveColor)
                )
            }

            // Active track (below divider)
            val activeStartPx = (dividerCenterY + halfDiv + gapPx).coerceAtMost(trackHeightPx)
            val activeHeightPx = (trackHeightPx - activeStartPx).coerceAtLeast(0f)
            if (activeHeightPx > 0f) {
                val activeStartDp = with(density) { activeStartPx.toDp() }
                val activeHeightDp = with(density) { activeHeightPx.toDp() }
                Box(
                    modifier = Modifier
                        .offset(y = activeStartDp)
                        .fillMaxWidth()
                        .height(activeHeightDp)
                        .align(Alignment.TopCenter)
                        .clip(
                            RoundedCornerShape(
                                topStart = if (animatedVolume >= 0.99f) 14.dp else 4.dp,
                                topEnd = if (animatedVolume >= 0.99f) 14.dp else 4.dp,
                                bottomStart = 14.dp,
                                bottomEnd = 14.dp
                            )
                        )
                        .background(colors.activeColor)
                )
            }

            // Divider handle
            val dividerTopDp = with(density) { dividerTopPx.toDp() }
            Box(
                modifier = Modifier
                    .offset(y = dividerTopDp)
                    .requiredWidth(dividerWidth)
                    .requiredHeight(dividerThickness)
                    .align(Alignment.TopCenter)
                    .clip(RoundedCornerShape(2.75.dp))
                    .background(colors.activeColor)
            )
        }

        // Music note icon and live percentage inside the vertical capsule
        val iconTint = if (animatedVolume > 0.88f) colors.onActiveColor else colors.onInactiveColor
        val textTint = if (animatedVolume > 0.78f) colors.onActiveColor else colors.onInactiveColor
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 10.dp)
        ) {
            Icon(
                imageVector = streamIcon,
                contentDescription = "Media Volume, $percent percent",
                tint = iconTint,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "$percent%",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = textTint
            )
        }
    }
}

// ─── Utilities ────────────────────────────────────────────────────────────────

private fun cycleRingerMode(context: Context, audioManager: AudioManager): Int {
    return try {
        val notifManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
        val current = audioManager.ringerMode
        val hasDnd = try {
            notifManager?.isNotificationPolicyAccessGranted == true
        } catch (_: Exception) { false }

        val nextMode = when (current) {
            AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
            AudioManager.RINGER_MODE_VIBRATE -> {
                if (hasDnd) {
                    AudioManager.RINGER_MODE_SILENT
                } else {
                    try {
                        val intent = Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        Toast.makeText(context, "Grant DND permission for Silent mode", Toast.LENGTH_SHORT).show()
                    } catch (_: Exception) {}
                    AudioManager.RINGER_MODE_NORMAL
                }
            }
            AudioManager.RINGER_MODE_SILENT -> AudioManager.RINGER_MODE_NORMAL
            else -> AudioManager.RINGER_MODE_NORMAL
        }

        try {
            audioManager.ringerMode = nextMode
        } catch (e: Exception) {
            e.printStackTrace()
            try { audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE } catch (_: Exception) {}
        }
        audioManager.ringerMode
    } catch (e: Exception) {
        e.printStackTrace()
        AudioManager.RINGER_MODE_NORMAL
    }
}

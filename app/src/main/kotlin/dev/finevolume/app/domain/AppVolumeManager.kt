package dev.finevolume.app.domain

import android.content.Context
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.finevolume.app.backend.ShizukuPlayerBackend
import dev.finevolume.app.data.AppMetadataCache
import dev.finevolume.app.data.SettingsRepository
import dev.finevolume.app.domain.model.AppVolumeSession
import dev.finevolume.app.shizuku.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

@Singleton
class AppVolumeManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shizukuPlayerBackend: ShizukuPlayerBackend,
    private val shizukuManager: ShizukuManager,
    private val appMetadataCache: AppMetadataCache,
    private val settingsRepository: SettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private val _activeAppSessions = MutableStateFlow<List<AppVolumeSession>>(emptyList())
    val activeAppSessions: StateFlow<List<AppVolumeSession>> = _activeAppSessions.asStateFlow()

    private val appVolumeCache = ConcurrentHashMap<String, Float>()
    private var isObserving = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val playbackCallback = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            if (isObserving) {
                refreshActiveSessions()
            }
        }
    }

    fun startObserving() {
        if (isObserving) return
        isObserving = true
        try {
            audioManager.registerAudioPlaybackCallback(playbackCallback, mainHandler)
        } catch (_: Throwable) {}
        refreshActiveSessions()
    }

    fun stopObserving() {
        if (!isObserving) return
        isObserving = false
        try {
            audioManager.unregisterAudioPlaybackCallback(playbackCallback)
        } catch (_: Throwable) {}
    }

    fun refreshActiveSessions() {
        scope.launch(Dispatchers.IO) {
            val service = shizukuPlayerBackend.currentUserService
            val packages = if (service != null) {
                try {
                    service.activeAppPackages ?: emptyList()
                } catch (_: Throwable) {
                    emptyList()
                }
            } else {
                emptyList()
            }

            val sessions = packages.map { pkg ->
                val meta = appMetadataCache.getAppMetadata(pkg)
                val currentVol = appVolumeCache[pkg] ?: run {
                    val sGain = try { service?.getAppGain(pkg) ?: 1.0f } catch (_: Throwable) { 1.0f }
                    appVolumeCache[pkg] = sGain
                    sGain
                }
                AppVolumeSession(
                    packageName = pkg,
                    appName = meta.appName,
                    icon = meta.icon,
                    volume = currentVol
                )
            }

            _activeAppSessions.value = sessions
        }
    }

    fun setAppVolume(packageName: String, position: Float) {
        val resolution = settingsRepository.cachedSettings.mediaResolution.coerceAtLeast(1)
        val step = (position * resolution).roundToInt().coerceIn(0, resolution)
        val quantized = step.toFloat() / resolution

        appVolumeCache[packageName] = quantized

        // Update UI state reactively
        _activeAppSessions.value = _activeAppSessions.value.map { session ->
            if (session.packageName == packageName) {
                session.copy(volume = quantized)
            } else {
                session
            }
        }

        // Apply gain via Shizuku user service in background
        scope.launch(Dispatchers.IO) {
            try {
                shizukuPlayerBackend.currentUserService?.applyAppGain(packageName, quantized)
            } catch (_: Throwable) {}
        }
    }

    fun isShizukuReady(): Boolean {
        return shizukuManager.isConnected.value && shizukuManager.permissionGranted.value
    }
}

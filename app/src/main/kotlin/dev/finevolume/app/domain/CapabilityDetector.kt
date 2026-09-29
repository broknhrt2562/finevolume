package dev.finevolume.app.domain

import android.media.AudioManager
import dev.finevolume.app.backend.VolumeBackend
import dev.finevolume.app.domain.model.VolumeCapabilities
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Detects device capabilities and merges them with backend capabilities.
 */
@Singleton
class CapabilityDetector @Inject constructor(
    private val audioManager: AudioManager
) {
    /** Returns native step count for given stream. */
    fun nativeSteps(streamType: Int): Int =
        audioManager.getStreamMaxVolume(streamType)

    /** Checks if the current Android version supports AudioPlaybackCallback (API 26+). */
    fun supportsPlaybackCallback(): Boolean = android.os.Build.VERSION.SDK_INT >= 26

    /** Returns the active backend's capabilities. */
    fun detect(activeBackend: VolumeBackend): VolumeCapabilities =
        activeBackend.capabilities()
}

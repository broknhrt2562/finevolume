package dev.finevolume.app.backend

import android.media.AudioManager
import dev.finevolume.app.domain.model.AudioStream
import dev.finevolume.app.domain.model.BackendType
import dev.finevolume.app.domain.model.VolumeCapabilities
import dev.finevolume.app.domain.model.VolumeState
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Volume backend using standard AudioManager APIs.
 *
 * Always available. Effective resolution = getStreamMaxVolume(stream).
 * Typically 15–25 on stock Android; up to 150 on Samsung with Sound Assistant.
 *
 * Does NOT apply any software gain. Audio path is not modified.
 */
@Singleton
class NativeVolumeBackend @Inject constructor(
    private val audioManager: AudioManager
) : VolumeBackend {

    override val type = BackendType.NATIVE

    override suspend fun getState(stream: AudioStream): VolumeState {
        val max = audioManager.getStreamMaxVolume(stream.androidStream)
        val current = audioManager.getStreamVolume(stream.androidStream)
        val position = if (max > 0) current.toFloat() / max.toFloat() else 0f
        return VolumeState(
            stream = stream,
            position = position,
            nativeIndex = current,
            nativeMax = max,
            sessionGain = null,
        )
    }

    override suspend fun setPosition(stream: AudioStream, position: Float): Result<Unit> {
        return runCatching {
            val max = audioManager.getStreamMaxVolume(stream.androidStream)
            val index = (position.coerceIn(0f, 1f) * max)
                .toInt()
                .coerceIn(0, max)
            // FLAG_SHOW_UI allows the OEM volume panel to pop up
            audioManager.setStreamVolume(stream.androidStream, index, 0)
        }
    }

    override fun capabilities(): VolumeCapabilities {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return VolumeCapabilities(
            effectiveResolution = max,
            supportsSubStepGain = false,
            isBitPerfect = false,  // Android always mixes; no exclusive output mode
            backendType = BackendType.NATIVE,
            limitedReason = "Native Android volume provides $max steps (0–$max). " +
                "Install Shizuku for sub-step precision.",
        )
    }
}

package dev.finevolume.app.backend

import android.media.AudioManager
import dev.finevolume.app.domain.model.AudioStream
import dev.finevolume.app.domain.model.BackendType
import dev.finevolume.app.domain.model.VolumeCapabilities
import dev.finevolume.app.domain.model.VolumeState
import javax.inject.Inject

/**
 * Read-only fallback backend.
 *
 * Used when all write-capable backends are unavailable.
 * Reports actual native capabilities but always returns failure on setPosition().
 *
 * The app must remain stable in this state — the user should see a clear
 * explanation in Diagnostics but the app must not crash.
 */
class FallbackBackend @Inject constructor(
    private val audioManager: AudioManager
) : VolumeBackend {

    override val type = BackendType.FALLBACK

    override suspend fun getState(stream: AudioStream): VolumeState {
        val max = audioManager.getStreamMaxVolume(stream.androidStream)
        val current = audioManager.getStreamVolume(stream.androidStream)
        return VolumeState(
            stream = stream,
            position = if (max > 0) current.toFloat() / max else 0f,
            nativeIndex = current,
            nativeMax = max,
            sessionGain = null,
        )
    }

    /** FallbackBackend is read-only — volume cannot be set in this state. */
    override suspend fun setPosition(stream: AudioStream, position: Float): Result<Unit> =
        Result.failure(UnsupportedOperationException(
            "FallbackBackend is read-only. Volume control unavailable."
        ))

    override fun capabilities(): VolumeCapabilities {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        return VolumeCapabilities(
            effectiveResolution = max,
            supportsSubStepGain = false,
            isBitPerfect = false,
            backendType = BackendType.FALLBACK,
            limitedReason = "Fallback mode — volume control not available. " +
                "This should not happen in normal operation.",
        )
    }
}

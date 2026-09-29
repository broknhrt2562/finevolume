package dev.finevolume.app.backend

import dev.finevolume.app.domain.model.AudioStream
import dev.finevolume.app.domain.model.BackendType
import dev.finevolume.app.domain.model.VolumeCapabilities
import dev.finevolume.app.domain.model.VolumeState

/**
 * Abstraction over the actual mechanism used to read and set volume.
 *
 * Implementations:
 * - [NativeVolumeBackend]: AudioManager.setStreamVolume() — always available
 * - [ShizukuPlayerBackend]: IPlayer.setVolume() via Shizuku — optional, higher precision
 * - [FallbackBackend]: read-only, used when all write-capable backends fail
 */
interface VolumeBackend {
    /**
     * Returns the current volume state for the given stream.
     * [VolumeState.position] is normalized to [0.0, 1.0].
     */
    suspend fun getState(stream: AudioStream): VolumeState

    /**
     * Sets volume for the given stream.
     * [position] must be in [0.0, 1.0] — values outside this range are clamped.
     * Returns [Result.failure] if the backend cannot set volume (e.g. FallbackBackend).
     */
    suspend fun setPosition(stream: AudioStream, position: Float): Result<Unit>

    /**
     * Returns the actual capabilities this backend provides.
     * [VolumeCapabilities.effectiveResolution] must never be overstated.
     */
    fun capabilities(): VolumeCapabilities

    /** Backend type identifier for diagnostics and routing logic */
    val type: BackendType
}

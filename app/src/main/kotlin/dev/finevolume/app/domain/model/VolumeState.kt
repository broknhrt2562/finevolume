package dev.finevolume.app.domain.model

/**
 * Snapshot of the current volume state for a given audio stream.
 *
 * [position] is always normalized to [0.0, 1.0].
 * [nativeIndex] and [nativeMax] reflect the actual Android AudioManager values.
 * [sessionGain] is non-null only when ShizukuPlayerBackend has applied sub-step gain.
 */
data class VolumeState(
    val stream: AudioStream,
    val position: Float,       // normalized [0.0, 1.0]
    val nativeIndex: Int,      // raw AudioManager index
    val nativeMax: Int,        // AudioManager.getStreamMaxVolume()
    val sessionGain: Float?,   // IPlayer gain if ShizukuPlayerBackend is active
)

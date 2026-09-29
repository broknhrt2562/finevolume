package dev.finevolume.app.domain

import dev.finevolume.app.backend.BackendSelector
import dev.finevolume.app.data.SettingsRepository
import dev.finevolume.app.domain.model.AudioStream
import dev.finevolume.app.domain.model.VolumeState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.min

import dev.finevolume.app.ui.overlay.VolumeOverlayController

/**
 * High-level API for mutating volume state.
 * Translates intent ("step up") into concrete position changes on the active backend.
 */
@Singleton
class VolumeController @Inject constructor(
    private val backendSelector: BackendSelector,
    private val settingsRepository: SettingsRepository,
    private val profileManager: ProfileManager,
    private val volumeOverlayController: VolumeOverlayController
) {
    private val backend get() = backendSelector.activeBackend.value

    /**
     * Returns the true effective resolution — never more than the backend can provide.
     * This is what the UI should display as the actual capability.
     */
    fun effectiveResolution(@Suppress("UNUSED_PARAMETER") stream: AudioStream): Int {
        return backend.capabilities().effectiveResolution
    }

    /**
     * Effective resolution given a requested resolution.
     * Returns min(requested, backend capability).
     */
    fun effectiveResolution(@Suppress("UNUSED_PARAMETER") stream: AudioStream, requested: Int): Int =
        min(requested, backend.capabilities().effectiveResolution)

    suspend fun getState(stream: AudioStream): VolumeState =
        backend.getState(stream)

    private val mutex = kotlinx.coroutines.sync.Mutex()
    
    // Maintain internal precise position to prevent rounding loss on small increments
    private val precisePositions = mutableMapOf<AudioStream, Float>()
    private val lastUpdateTime = mutableMapOf<AudioStream, Long>()

    suspend fun stepUp(stream: AudioStream, steps: Int) {
        val requested = settingsRepository.cachedSettings.mediaResolution.coerceAtLeast(1)
        val delta = steps.toFloat() / requested
        val now = android.os.SystemClock.uptimeMillis()

        val newPosition = mutex.withLock {
            val lastTime = lastUpdateTime[stream] ?: 0L
            val currentPos = if (now - lastTime < 1500L && precisePositions.containsKey(stream)) {
                precisePositions[stream]!!
            } else {
                val state = backend.getState(stream)
                state.position
            }
            val pos = (currentPos + delta).coerceIn(0f, 1f)
            precisePositions[stream] = pos
            lastUpdateTime[stream] = now
            pos
        }
        
        backend.setPosition(stream, newPosition)
        volumeOverlayController.show(newPosition)
    }

    suspend fun stepDown(stream: AudioStream, steps: Int) {
        val requested = settingsRepository.cachedSettings.mediaResolution.coerceAtLeast(1)
        val delta = steps.toFloat() / requested
        val now = android.os.SystemClock.uptimeMillis()

        val newPosition = mutex.withLock {
            val lastTime = lastUpdateTime[stream] ?: 0L
            val currentPos = if (now - lastTime < 1500L && precisePositions.containsKey(stream)) {
                precisePositions[stream]!!
            } else {
                val state = backend.getState(stream)
                state.position
            }
            val pos = (currentPos - delta).coerceIn(0f, 1f)
            precisePositions[stream] = pos
            lastUpdateTime[stream] = now
            pos
        }
        
        backend.setPosition(stream, newPosition)
        volumeOverlayController.show(newPosition)
    }

    suspend fun setAbsolute(stream: AudioStream, position: Float) {
        val pos = position.coerceIn(0f, 1f)
        val now = android.os.SystemClock.uptimeMillis()
        mutex.withLock {
            precisePositions[stream] = pos
            lastUpdateTime[stream] = now
        }
        backend.setPosition(stream, pos)
    }
}

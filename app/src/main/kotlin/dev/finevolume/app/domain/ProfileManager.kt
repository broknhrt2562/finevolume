package dev.finevolume.app.domain

import android.media.AudioDeviceInfo
import android.media.AudioManager
import dev.finevolume.app.data.ProfileRepository
import dev.finevolume.app.data.VolumeProfileProto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages per-output profiles.
 * Automatically switches the active profile when the output type changes.
 */
@Singleton
class ProfileManager @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val audioManager: AudioManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _currentOutputType = MutableStateFlow(detectOutputType())
    val currentOutputType: StateFlow<Int> = _currentOutputType

    private val _currentProfile = MutableStateFlow<VolumeProfileProto?>(null)
    val currentProfile: StateFlow<VolumeProfileProto?> = _currentProfile

    init {
        profileRepository.profiles.onEach { profiles ->
            val outputType = _currentOutputType.value
            _currentProfile.value = profiles.firstOrNull { it.outputType == outputType }
        }.launchIn(scope)
    }

    fun onOutputChanged(newOutputType: Int) {
        _currentOutputType.value = newOutputType
    }

    private fun detectOutputType(): Int {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.firstOrNull()?.type ?: AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
    }
}

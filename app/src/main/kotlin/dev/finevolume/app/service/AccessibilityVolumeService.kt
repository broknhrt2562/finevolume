package dev.finevolume.app.service

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import dagger.hilt.android.AndroidEntryPoint
import dev.finevolume.app.data.SettingsRepository
import dev.finevolume.app.domain.*
import kotlinx.coroutines.*
import javax.inject.Inject

import dev.finevolume.app.ui.overlay.VolumeOverlayController

/**
 * Intercepts hardware volume keys.
 *
 * MUST NOT use an overlay window or modify SystemUI.
 * Requires `android:canRequestFilterKeyEvents="true"` in its XML config.
 */
@AndroidEntryPoint
class AccessibilityVolumeService : AccessibilityService() {

    @Inject lateinit var buttonBehaviorEngine: ButtonBehaviorEngine
    @Inject lateinit var volumeController: VolumeController
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var hapticFeedbackManager: HapticFeedbackManager
    @Inject lateinit var volumeOverlayController: VolumeOverlayController

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dragChannel = kotlinx.coroutines.channels.Channel<Float>(kotlinx.coroutines.channels.Channel.CONFLATED)
    private var repeatJob: Job? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            volumeOverlayController.attachContext(this)

            // Conflated drag processor: prioritizes latest position, drops stale intermediate states
            scope.launch {
                for (newVolume in dragChannel) {
                    try {
                        volumeController.setAbsolute(
                            dev.finevolume.app.domain.model.AudioStream.MUSIC,
                            newVolume
                        )
                    } catch (_: Exception) {}
                }
            }

            volumeOverlayController.onVolumeChange = { newVolume ->
                dragChannel.trySend(newVolume)
            }

            volumeOverlayController.onSettingsClick = {
                try {
                    val intent = android.content.Intent(this@AccessibilityVolumeService, dev.finevolume.app.MainActivity::class.java).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    try {
                        val fallback = android.content.Intent(android.provider.Settings.ACTION_SOUND_SETTINGS).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(fallback)
                    } catch (_: Exception) {}
                }
            }

            volumeOverlayController.onMuteClick = {
                try {
                    val audioManager = getSystemService(android.content.Context.AUDIO_SERVICE) as? android.media.AudioManager
                    audioManager?.adjustStreamVolume(
                        android.media.AudioManager.STREAM_MUSIC,
                        android.media.AudioManager.ADJUST_TOGGLE_MUTE,
                        0
                    )
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        event ?: return false
        val keyCode = event.keyCode

        // If the expanded volume overlay is open, intercept the BACK key to dismiss it
        if (keyCode == KeyEvent.KEYCODE_BACK && volumeOverlayController.isExpanded) {
            if (event.action == KeyEvent.ACTION_UP) {
                volumeOverlayController.dismissExpanded()
            }
            return true
        }

        if (keyCode != KeyEvent.KEYCODE_VOLUME_UP && keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) {
            return false
        }

        val settings = settingsRepository.cachedSettings
        if (!settings.isEnabled) {
            return false
        }

        try {
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    // Suppress OS hardware auto-repeat to avoid duplicate event storms
                    if (event.repeatCount > 0) return true

                    // Immediate single press
                    val action = buttonBehaviorEngine.handleKeyEvent(event, settings)
                    executeAction(action)

                    // Controlled long-press repeat loop (respects user-configured speed)
                    repeatJob?.cancel()
                    val direction = if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) Direction.UP else Direction.DOWN
                    val steps = settings.mediaIncrement.coerceAtLeast(1)
                    val repeatInterval = settings.longPressRepeatIntervalMs.coerceIn(20, 500).toLong()

                    repeatJob = scope.launch {
                        delay(350L) // Standard Android initial hold threshold
                        while (isActive) {
                            try {
                                when (direction) {
                                    Direction.UP -> volumeController.stepUp(
                                        dev.finevolume.app.domain.model.AudioStream.MUSIC, steps
                                    )
                                    Direction.DOWN -> volumeController.stepDown(
                                        dev.finevolume.app.domain.model.AudioStream.MUSIC, steps
                                    )
                                }
                                hapticFeedbackManager.vibrate(settings.hapticLevel)
                            } catch (_: Exception) {}
                            delay(repeatInterval)
                        }
                    }
                    return true
                }
                KeyEvent.ACTION_UP -> {
                    repeatJob?.cancel()
                    repeatJob = null
                    buttonBehaviorEngine.handleKeyEvent(event, settings)
                    return true
                }
                else -> return false
            }
        } catch (_: Exception) {
            return false
        }
    }

    private fun executeAction(action: KeyAction) {
        val settings = settingsRepository.cachedSettings
        when (action) {
            is KeyAction.StepVolume -> {
                scope.launch {
                    try {
                        when (action.direction) {
                            Direction.UP -> volumeController.stepUp(
                                dev.finevolume.app.domain.model.AudioStream.MUSIC, action.steps
                            )
                            Direction.DOWN -> volumeController.stepDown(
                                dev.finevolume.app.domain.model.AudioStream.MUSIC, action.steps
                            )
                        }
                        hapticFeedbackManager.vibrate(settings.hapticLevel)
                    } catch (_: Exception) {}
                }
            }
            is KeyAction.Consume, is KeyAction.PassThrough -> {
                // No-op
            }
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used — only intercepting keys
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        repeatJob?.cancel()
        repeatJob = null
        scope.coroutineContext[Job]?.cancelChildren()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        repeatJob?.cancel()
        repeatJob = null
        dragChannel.close()
        scope.cancel()
        volumeOverlayController.detachContext()
        super.onDestroy()
    }
}

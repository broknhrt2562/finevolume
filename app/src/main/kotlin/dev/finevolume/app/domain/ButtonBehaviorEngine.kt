package dev.finevolume.app.domain

import android.view.KeyEvent
import dev.finevolume.app.data.AccelerationMode
import dev.finevolume.app.data.AppSettings
import javax.inject.Inject
import javax.inject.Singleton

sealed class KeyAction {
    object Consume : KeyAction()
    data class StepVolume(val direction: Direction, val steps: Int) : KeyAction()
    object PassThrough : KeyAction()
}

enum class Direction { UP, DOWN }

/**
 * Translates hardware volume key events into logical actions based on user settings.
 * Fine Mode has been removed. Simultaneous Vol-Up + Vol-Down hold is no longer a special gesture.
 */
@Singleton
class ButtonBehaviorEngine @Inject constructor() {

    private var manualRepeatCount: Int = 0

    fun handleKeyEvent(event: KeyEvent, settings: AppSettings): KeyAction {
        return when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> handleVolumeKey(event, Direction.UP, settings)
            KeyEvent.KEYCODE_VOLUME_DOWN -> handleVolumeKey(event, Direction.DOWN, settings)
            else -> KeyAction.PassThrough
        }
    }

    private fun handleVolumeKey(event: KeyEvent, direction: Direction, settings: AppSettings): KeyAction {
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) {
                    manualRepeatCount = 0
                } else {
                    manualRepeatCount++
                }
                val steps = computeSteps(settings)
                KeyAction.StepVolume(direction, steps)
            }
            KeyEvent.ACTION_UP -> {
                manualRepeatCount = 0
                KeyAction.Consume
            }
            else -> KeyAction.PassThrough
        }
    }

    private fun computeSteps(settings: AppSettings): Int {
        val baseIncrement = settings.mediaIncrement.takeIf { it > 0 } ?: 1
        return when (settings.accelerationMode) {
            AccelerationMode.LINEAR -> baseIncrement
            AccelerationMode.EXPONENTIAL -> {
                val multiplier = 1 shl (manualRepeatCount / EXPONENTIAL_THRESHOLD).coerceAtMost(4)
                (baseIncrement * multiplier).coerceAtMost(settings.mediaResolution)
            }
            AccelerationMode.ADAPTIVE -> baseIncrement
        }
    }

    companion object {
        const val EXPONENTIAL_THRESHOLD = 3
    }
}

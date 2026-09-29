package dev.finevolume.app.service

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HapticFeedbackManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val vibrator: Vibrator? by lazy {
        try {
            if (android.os.Build.VERSION.SDK_INT >= 31) {
                val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                manager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (_: Exception) {
            null
        }
    }

    private val effectLight = if (android.os.Build.VERSION.SDK_INT >= 26) VibrationEffect.createOneShot(20L, 40) else null
    private val effectMedium = if (android.os.Build.VERSION.SDK_INT >= 26) VibrationEffect.createOneShot(20L, 100) else null
    private val effectStrong = if (android.os.Build.VERSION.SDK_INT >= 26) VibrationEffect.createOneShot(20L, 200) else null

    /** level: 0=off, 1=light, 2=medium, 3=strong */
    fun vibrate(level: Int) {
        if (level == 0) return
        val v = vibrator ?: return
        try {
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                val effect = when (level) {
                    1 -> effectLight
                    2 -> effectMedium
                    3 -> effectStrong
                    else -> return
                }
                effect?.let { v.vibrate(it) }
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(20L)
            }
        } catch (_: Exception) {}
    }
}

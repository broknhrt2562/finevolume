package dev.finevolume.app.domain.model

import android.media.AudioManager

/**
 * The set of audio streams FineVolume can control.
 *
 * Streams are queried dynamically at runtime — the app does not assume
 * all streams are available on all devices/OEM configurations.
 */
enum class AudioStream(val androidStream: Int, val displayName: String) {
    MUSIC(AudioManager.STREAM_MUSIC, "Media"),
    RING(AudioManager.STREAM_RING, "Ring"),
    NOTIFICATION(AudioManager.STREAM_NOTIFICATION, "Notification"),
    ALARM(AudioManager.STREAM_ALARM, "Alarm"),
    SYSTEM(AudioManager.STREAM_SYSTEM, "System"),
}

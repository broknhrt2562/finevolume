package dev.finevolume.app.domain.model

import androidx.compose.ui.graphics.ImageBitmap

data class AppMetadata(
    val packageName: String,
    val appName: String,
    val icon: ImageBitmap? = null
)

data class AppVolumeSession(
    val packageName: String,
    val appName: String,
    val icon: ImageBitmap? = null,
    val volume: Float = 1.0f
)

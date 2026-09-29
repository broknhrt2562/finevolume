package dev.finevolume.app.data

enum class AccelerationMode {
    LINEAR, EXPONENTIAL, ADAPTIVE
}

data class AppSettings(
    val mediaResolution: Int = 120,
    val mediaIncrement: Int = 1,
    val longPressRepeatIntervalMs: Int = 60,
    val bitPerfectProtection: Boolean = true,
    val hapticLevel: Int = 1,
    val accelerationMode: AccelerationMode = AccelerationMode.EXPONENTIAL,
    val shizukuEnabled: Boolean = true,
    val isEnabled: Boolean = true
)

package dev.finevolume.app.data

data class VolumeProfileProto(
    val id: String,
    val name: String,
    val outputType: Int,
    val resolution: Int,
    val increment: Int,
    val bitPerfectProtection: Boolean
)

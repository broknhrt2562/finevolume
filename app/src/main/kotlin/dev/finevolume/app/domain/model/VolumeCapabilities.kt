package dev.finevolume.app.domain.model

/**
 * Identifies which volume control mechanism is active.
 */
enum class BackendType {
    /** Standard AudioManager.setStreamVolume() — always available */
    NATIVE,
    /** Shizuku-based IPlayer session gain + NativeVolumeBackend — Shizuku required */
    SHIZUKU_PLAYER,
    /** Read-only fallback — used when all write-capable backends fail */
    FALLBACK,
}

/**
 * Describes the actual capabilities of the active backend.
 *
 * IMPORTANT: [effectiveResolution] must NEVER be overstated.
 * If the backend can only provide 15 distinguishable levels, this must be 15,
 * even if the user has requested 100.
 */
data class VolumeCapabilities(
    /**
     * The true number of distinguishable volume levels this backend provides.
     * = getStreamMaxVolume() for NativeVolumeBackend
     * = up to 1000 for ShizukuPlayerBackend (float gain precision)
     */
    val effectiveResolution: Int,
    /** Whether this backend applies per-session floating-point gain (sub-step precision) */
    val supportsSubStepGain: Boolean,
    /**
     * Whether audio output is bit-perfect.
     * False whenever software gain is applied (which the Android mixer already does).
     * Note: Android has NO exclusive output mode — isBitPerfect is always false in practice.
     */
    val isBitPerfect: Boolean,
    val backendType: BackendType,
    /** Human-readable explanation of any limitation, shown in Diagnostics */
    val limitedReason: String?,
)

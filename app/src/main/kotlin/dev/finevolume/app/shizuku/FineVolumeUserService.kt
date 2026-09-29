package dev.finevolume.app.shizuku

import android.content.Context
import android.media.AudioPlaybackConfiguration
import android.media.AudioManager
import android.util.Log
import kotlin.system.exitProcess

import androidx.annotation.Keep

/**
 * Runs inside the Shizuku (uid 2000 / shell or uid 0 / root) process.
 * Has full access to System APIs, including hidden IPlayer controls.
 */
@Keep
class FineVolumeUserService : IFineVolumeService.Stub() {

    private var audioManager: AudioManager? = null
    private var packageManager: android.content.pm.PackageManager? = null
    private var getPlayerProxyMethod: java.lang.reflect.Method? = null
    private var setVolumeMethod: java.lang.reflect.Method? = null
    private var isActiveMethod: java.lang.reflect.Method? = null
    private var getPlayerStateMethod: java.lang.reflect.Method? = null
    private var getClientUidMethod: java.lang.reflect.Method? = null
    private var clientUidField: java.lang.reflect.Field? = null

    private val appGainMap = java.util.concurrent.ConcurrentHashMap<String, Float>()
    private val uidPackageCache = java.util.concurrent.ConcurrentHashMap<Int, String>()

    init {
        try {
            if (android.os.Looper.myLooper() == null) {
                android.os.Looper.prepare()
            }
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val systemMainMethod = activityThreadClass.getDeclaredMethod("systemMain")
            val activityThread = systemMainMethod.invoke(null)
            val getSystemContextMethod = activityThreadClass.getDeclaredMethod("getSystemContext")
            val systemContext = getSystemContextMethod.invoke(activityThread) as Context
            audioManager = systemContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            packageManager = systemContext.packageManager

            // Pre-cache reflection methods once
            getPlayerProxyMethod = runCatching {
                AudioPlaybackConfiguration::class.java.getDeclaredMethod("getPlayerProxy").apply {
                    isAccessible = true
                }
            }.getOrNull()

            isActiveMethod = runCatching {
                AudioPlaybackConfiguration::class.java.getDeclaredMethod("isActive").apply {
                    isAccessible = true
                }
            }.getOrNull()

            getPlayerStateMethod = runCatching {
                AudioPlaybackConfiguration::class.java.getMethod("getPlayerState").apply {
                    isAccessible = true
                }
            }.getOrNull() ?: runCatching {
                AudioPlaybackConfiguration::class.java.getDeclaredMethod("getPlayerState").apply {
                    isAccessible = true
                }
            }.getOrNull()

            getClientUidMethod = runCatching {
                AudioPlaybackConfiguration::class.java.getMethod("getClientUid").apply { isAccessible = true }
            }.getOrNull() ?: runCatching {
                AudioPlaybackConfiguration::class.java.getDeclaredMethod("getClientUid").apply { isAccessible = true }
            }.getOrNull()

            clientUidField = runCatching {
                AudioPlaybackConfiguration::class.java.getDeclaredField("mClientUid").apply { isAccessible = true }
            }.getOrNull()
        } catch (_: Exception) {}
    }

    private fun getUidForConfig(config: AudioPlaybackConfiguration): Int {
        try {
            val uid = getClientUidMethod?.invoke(config) as? Int
            if (uid != null && uid > 0) return uid
        } catch (_: Throwable) {}

        try {
            val uid = clientUidField?.getInt(config)
            if (uid != null && uid > 0) return uid
        } catch (_: Throwable) {}

        return -1
    }

    private fun getPackageNameForUid(uid: Int): String? {
        if (uid <= 1000) return null // Kernel & System UIDs
        uidPackageCache[uid]?.let { return it }
        val pm = packageManager ?: return null
        return try {
            val pkgs = pm.getPackagesForUid(uid)
            val pkg = pkgs?.firstOrNull { it !in SYSTEM_PACKAGES && !it.startsWith("com.android.keyguard") }
            if (pkg != null) {
                uidPackageCache[uid] = pkg
            }
            pkg
        } catch (_: Throwable) {
            null
        }
    }

    override fun applySessionGain(streamType: Int, gain: Float) {
        val am = audioManager ?: return
        try {
            val configs = am.activePlaybackConfigurations
            if (configs.isEmpty()) return
            val proxyMethod = getPlayerProxyMethod ?: return

            for (config in configs) {
                val playerProxy = proxyMethod.invoke(config) ?: continue
                val attrs = config.audioAttributes
                if (attrs != null && (streamType == AudioManager.STREAM_MUSIC)) {
                    val uid = getUidForConfig(config)
                    val pkg = if (uid > 0) getPackageNameForUid(uid) else null
                    // If this app has a specific per-app gain, combine or respect app gain
                    val finalGain = if (pkg != null && appGainMap.containsKey(pkg)) {
                        (appGainMap[pkg] ?: 1.0f) * gain
                    } else {
                        gain
                    }

                    var setVol = setVolumeMethod
                    if (setVol == null) {
                        setVol = playerProxy.javaClass.getDeclaredMethod("setVolume", Float::class.java).apply {
                            isAccessible = true
                        }
                        setVolumeMethod = setVol
                    }
                    try {
                        setVol?.invoke(playerProxy, finalGain)
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    override fun applyAppGain(packageName: String, gain: Float) {
        val clampedGain = gain.coerceIn(0f, 1f)
        appGainMap[packageName] = clampedGain

        val am = audioManager ?: return
        try {
            val configs = am.activePlaybackConfigurations
            if (configs.isEmpty()) return
            val proxyMethod = getPlayerProxyMethod ?: return

            for (config in configs) {
                val uid = getUidForConfig(config)
                if (uid <= 0) continue
                val pkg = getPackageNameForUid(uid) ?: continue
                if (pkg == packageName) {
                    val playerProxy = proxyMethod.invoke(config) ?: continue
                    var setVol = setVolumeMethod
                    if (setVol == null) {
                        setVol = playerProxy.javaClass.getDeclaredMethod("setVolume", Float::class.java).apply {
                            isAccessible = true
                        }
                        setVolumeMethod = setVol
                    }
                    try {
                        setVol?.invoke(playerProxy, clampedGain)
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    override fun getActiveAppPackages(): List<String> {
        val am = audioManager ?: return emptyList()
        val result = mutableSetOf<String>()
        try {
            val configs = am.activePlaybackConfigurations
            if (configs.isEmpty()) return emptyList()
            for (config in configs) {
                // A player is actively playing if playerState is PLAYER_STATE_STARTED (2).
                // When volume is reduced to 0 in FineVolume, playerState is STILL 2 (started),
                // so the app remains visible while actively playing at volume 0.
                // When the app is closed/stopped, playerState is no longer 2, or the config is removed.
                val playerState = runCatching {
                    getPlayerStateMethod?.invoke(config) as? Int
                }.getOrNull()

                val isPlaying = if (playerState != null) {
                    playerState == 2 // 2 = PLAYER_STATE_STARTED
                } else {
                    val active = isActiveMethod?.invoke(config) as? Boolean ?: true
                    active || (getUidForConfig(config).let { uid ->
                        val pkg = if (uid > 0) getPackageNameForUid(uid) else null
                        pkg != null && appGainMap.containsKey(pkg)
                    })
                }

                if (isPlaying) {
                    val uid = getUidForConfig(config)
                    if (uid > 0) {
                        val pkg = getPackageNameForUid(uid)
                        if (pkg != null && pkg !in SYSTEM_PACKAGES) {
                            result.add(pkg)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
        return result.toList()
    }

    override fun getAppGain(packageName: String): Float {
        return appGainMap[packageName] ?: 1.0f
    }

    override fun getActiveSessionCount(streamType: Int): Int {
        val am = audioManager ?: return 0
        var count = 0
        try {
            val configs = am.activePlaybackConfigurations
            if (configs.isEmpty()) return 0
            val activeMethod = isActiveMethod
            for (config in configs) {
                val isActive = activeMethod?.invoke(config) as? Boolean ?: false
                if (isActive) {
                    count++
                }
            }
        } catch (_: Exception) {}
        return count
    }

    override fun destroy() {
        Log.i(TAG, "FineVolumeUserService destroy")
        exitProcess(0)
    }

    companion object {
        private const val TAG = "FineVolumeUserService"
        private val SYSTEM_PACKAGES = setOf(
            "android",
            "com.android.systemui",
            "dev.finevolume.app"
        )
    }
}

package dev.finevolume.app.shizuku

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import rikka.shizuku.Shizuku
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Manages the connection lifecycle and permissions for Shizuku.
 *
 * Exposes a reactive [isConnected] state flow so that the BackendSelector
 * can automatically switch between NativeVolumeBackend and ShizukuPlayerBackend.
 */
@Singleton
class ShizukuManager @Inject constructor() {

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected

    private val _permissionGranted = MutableStateFlow(false)
    val permissionGranted: StateFlow<Boolean> = _permissionGranted

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        _isConnected.value = true
        checkPermission()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        onBinderDead()
    }

    init {
        // We use catching blocks because in test environments or without Shizuku,
        // these calls might throw or fail gracefully.
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
        }
    }

    fun onBinderDead() {
        _isConnected.value = false
        _permissionGranted.value = false
    }

    fun requestPermission(requestCode: Int = SHIZUKU_PERMISSION_CODE) {
        if (!_isConnected.value) return
        
        runCatching {
            if (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
                _permissionGranted.value = true
                return
            }
            Shizuku.requestPermission(requestCode)
        }
    }

    fun onPermissionResult(requestCode: Int, grantResult: Int) {
        if (requestCode == SHIZUKU_PERMISSION_CODE) {
            _permissionGranted.value =
                (grantResult == android.content.pm.PackageManager.PERMISSION_GRANTED)
        }
    }

    private fun checkPermission() {
        runCatching {
            _permissionGranted.value =
                (Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED)
        }
    }

    fun cleanup() {
        runCatching {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
        }
    }

    companion object {
        const val SHIZUKU_PERMISSION_CODE = 1001
    }
}

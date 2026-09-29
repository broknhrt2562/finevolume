package dev.finevolume.app.backend

import dev.finevolume.app.domain.model.BackendType
import dev.finevolume.app.shizuku.ShizukuManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku
import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import dev.finevolume.app.shizuku.IFineVolumeService
import dev.finevolume.app.shizuku.FineVolumeUserService
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Automatically selects the best available VolumeBackend.
 *
 * If Shizuku is connected and the ShizukuPlayerBackend is available, it uses that.
 * Otherwise, it falls back to the NativeVolumeBackend.
 * If all else fails, it could theoretically use FallbackBackend, but NativeVolumeBackend
 * is always available on Android.
 */
@Singleton
class BackendSelector @Inject constructor(
    private val nativeBackend: NativeVolumeBackend,
    private val shizukuPlayerBackend: ShizukuPlayerBackend,
    private val fallbackBackend: FallbackBackend,
    private val shizukuManager: ShizukuManager,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _activeBackend = MutableStateFlow<VolumeBackend>(nativeBackend)
    val activeBackend: StateFlow<VolumeBackend> = _activeBackend

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            shizukuPlayerBackend.bindUserService(IFineVolumeService.Stub.asInterface(service))
            _activeBackend.value = shizukuPlayerBackend
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shizukuPlayerBackend.unbindUserService()
            _activeBackend.value = nativeBackend
        }
    }

    init {
        scope.launch {
            combine(shizukuManager.isConnected, shizukuManager.permissionGranted) { connected, granted ->
                connected && granted
            }.collect { canBind ->
                if (canBind) {
                    try {
                        val args = Shizuku.UserServiceArgs(ComponentName("dev.finevolume.app", FineVolumeUserService::class.java.name))
                            .daemon(false)
                            .processNameSuffix("service")
                            .debuggable(false)
                            .version(1)
                        Shizuku.bindUserService(args, serviceConnection)
                    } catch (e: Exception) {
                        e.printStackTrace()
                        _activeBackend.value = nativeBackend
                    }
                } else {
                    try {
                        Shizuku.unbindUserService(Shizuku.UserServiceArgs(ComponentName("dev.finevolume.app", FineVolumeUserService::class.java.name)), serviceConnection, true)
                    } catch (e: Exception) {}
                    _activeBackend.value = nativeBackend
                }
            }
        }
    }
}

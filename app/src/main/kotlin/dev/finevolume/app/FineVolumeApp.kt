package dev.finevolume.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import org.lsposed.hiddenapibypass.HiddenApiBypass

@HiltAndroidApp
class FineVolumeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Allow reflection on hidden Android APIs.
        // Required for Shizuku-based IPlayer access to apply sub-step session gain.
        HiddenApiBypass.addHiddenApiExemptions("")
    }
}

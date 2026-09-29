# FineVolume ProGuard / R8 Optimization Rules

# Keep Shizuku AIDL interfaces and user service implementations
-keep class dev.finevolume.app.shizuku.** { *; }
-keep interface dev.finevolume.app.shizuku.IFineVolumeService { *; }
-keep class dev.finevolume.app.shizuku.IFineVolumeService$Stub { *; }

# Keep reflection targets used in Shizuku process
-keepclassmembers class android.media.AudioPlaybackConfiguration {
    public *** getPlayerProxy();
    public *** isActive();
}

# Strip debug and verbose logging in release builds to eliminate string formatting and Log I/O
-assumenosideeffects class android.util.Log {
    public static boolean isLoggable(java.lang.String, int);
    public static int v(...);
    public static int d(...);
    public static int i(...);
}

# Keep Hilt and Dagger generated code
-keep class * extends dagger.hilt.internal.GeneratedComponent { *; }

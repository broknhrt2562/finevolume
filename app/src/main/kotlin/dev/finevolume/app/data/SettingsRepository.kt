package dev.finevolume.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    private val MEDIA_RES = intPreferencesKey("media_resolution")
    private val MEDIA_INC = intPreferencesKey("media_increment")
    private val LONG_PRESS_INTERVAL = intPreferencesKey("long_press_interval_ms")
    private val HAPTIC_LVL = intPreferencesKey("haptic_level")
    private val ACCEL_MODE = stringPreferencesKey("accel_mode")
    private val IS_ENABLED = booleanPreferencesKey("is_finevolume_enabled")

    @Volatile
    var cachedSettings: AppSettings = AppSettings()
        private set

    private val repoScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO
    )

    val settings: Flow<AppSettings> = dataStore.data.map { prefs ->
        val s = AppSettings(
            mediaResolution = prefs[MEDIA_RES] ?: 120,
            mediaIncrement = prefs[MEDIA_INC] ?: 1,
            longPressRepeatIntervalMs = prefs[LONG_PRESS_INTERVAL] ?: 60,
            hapticLevel = prefs[HAPTIC_LVL] ?: 1,
            accelerationMode = try {
                AccelerationMode.valueOf(prefs[ACCEL_MODE] ?: "EXPONENTIAL")
            } catch (_: Exception) {
                AccelerationMode.EXPONENTIAL
            },
            isEnabled = prefs[IS_ENABLED] ?: true
        )
        cachedSettings = s
        s
    }

    init {
        repoScope.launch {
            try {
                settings.collect { cachedSettings = it }
            } catch (_: Exception) {}
        }
    }

    suspend fun updateIsEnabled(enabled: Boolean) {
        dataStore.edit { it[IS_ENABLED] = enabled }
    }
    suspend fun updateMediaResolution(res: Int) {
        dataStore.edit { it[MEDIA_RES] = res }
    }
    suspend fun updateMediaIncrement(inc: Int) {
        dataStore.edit { it[MEDIA_INC] = inc }
    }
    suspend fun updateLongPressRepeatInterval(ms: Int) {
        dataStore.edit { it[LONG_PRESS_INTERVAL] = ms.coerceIn(20, 500) }
    }
    suspend fun updateHapticLevel(lvl: Int) {
        dataStore.edit { it[HAPTIC_LVL] = lvl }
    }
    suspend fun updateAccelerationMode(mode: AccelerationMode) {
        dataStore.edit { it[ACCEL_MODE] = mode.name }
    }
}

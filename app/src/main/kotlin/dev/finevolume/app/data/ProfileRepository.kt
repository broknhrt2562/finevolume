package dev.finevolume.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    // For simplicity without protobuf, we'll serialize to a single JSON-like string
    // or just not fully implement the persistent list for this skeleton
    
    val profiles: Flow<List<VolumeProfileProto>> = dataStore.data.map { prefs ->
        emptyList()
    }
}

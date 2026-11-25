package com.sergey.animevault.ui.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.stateIn

private val Context.graphicsDataStore by preferencesDataStore(name = "animevault_graphics")
private val graphicsPresetKey = stringPreferencesKey("graphics_preset")

internal class GraphicsPresetStore(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) {
    val preset: StateFlow<GraphicsPreset?> = dataStore.data
        .map { GraphicsPreset.fromStorage(it[graphicsPresetKey]) }
        .retryWhen { error, _ ->
            if (error !is IOException) return@retryWhen false
            emit(GraphicsPreset.BALANCED)
            delay(2_000)
            true
        }
        .stateIn(scope, SharingStarted.Eagerly, null)

    suspend fun setPreset(preset: GraphicsPreset) {
        dataStore.edit { it[graphicsPresetKey] = preset.storageId }
    }
}

internal fun createGraphicsPresetStore(context: Context, scope: CoroutineScope): GraphicsPresetStore =
    GraphicsPresetStore(context.applicationContext.graphicsDataStore, scope)

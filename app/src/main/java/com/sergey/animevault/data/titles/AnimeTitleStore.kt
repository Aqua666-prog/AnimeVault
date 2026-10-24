package com.sergey.animevault.data.titles

import android.content.Context
import androidx.core.content.edit
import com.google.gson.Gson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Rebuildable metadata cache, separate from Room identities and the user's library. */
@OptIn(FlowPreview::class)
class AnimeTitleStore(context: Context, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("anime_title_metadata_v1", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val _index = MutableStateFlow(runCatching {
        gson.fromJson(prefs.getString("index", null), AnimeTitleIndex::class.java)
            ?.also { restored ->
                // Gson can bypass Kotlin non-null defaults when reading a damaged cache.
                restored.names.values.forEach { it.overlay(AnimeNames()) }
                restored.references.values.forEach { refs -> refs.forEach { it.length } }
            }
    }.getOrNull() ?: AnimeTitleIndex())
    val index = _index.asStateFlow()
    init {
        scope.launch {
            index.debounce(300).collect { snapshot ->
                withContext(Dispatchers.IO) { prefs.edit { putString("index", gson.toJson(snapshot)) } }
            }
        }
    }

    @Synchronized
    fun removeAbsentLocals(ids: Set<Long>) {
        val keys = ids.map(::localTitleKey).toSet()
        val old = _index.value
        _index.value = old.copy(names = old.names.filterKeys { !it.startsWith("local:") || it in keys },
            references = old.references.filterKeys { !it.startsWith("local:") || it in keys })
    }

    @Synchronized
    fun put(key: String, names: AnimeNames, references: List<String>? = null) {
        if (key.isBlank()) return
        val old = _index.value
        val merged = (old.names[key] ?: AnimeNames()).overlay(names)
        val refs = references?.filter { it != key }?.distinct() ?: old.references[key]
        if (old.names[key] == merged && old.references[key] == refs) return
        val entries = (old.names + (key to merged)).entries.toList().takeLast(4000).associate { it.toPair() }
        val links = (old.references + if (refs != null) mapOf(key to refs) else emptyMap()).filterKeys { it in entries }
        _index.value = AnimeTitleIndex(entries, links)
    }
}

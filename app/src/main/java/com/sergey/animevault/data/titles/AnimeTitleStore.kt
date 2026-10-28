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
    private val _index = MutableStateFlow(AnimeTitleCachePolicy.restore(prefs.getString("index", null), gson))
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
        _index.value = AnimeTitleCachePolicy.merge(_index.value, emptyMap(), liveLocals = ids)
    }

    /** One Room/collection snapshot produces one bounded Flow update, not N map copies. */
    @Synchronized
    fun putBatch(values: Map<String, AnimeNames>, links: Map<String, List<String>> = emptyMap(), liveLocals: Set<Long>? = null) {
        _index.value = AnimeTitleCachePolicy.merge(_index.value, values, links, liveLocals)
    }

    @Synchronized
    fun put(key: String, names: AnimeNames, references: List<String>? = null) {
        if (key.isBlank()) return
        _index.value = AnimeTitleCachePolicy.merge(_index.value, mapOf(key to names),
            references?.let { mapOf(key to it) }.orEmpty())
    }
}

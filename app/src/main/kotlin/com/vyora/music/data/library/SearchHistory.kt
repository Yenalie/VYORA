package com.vyora.music.data.library

import android.content.Context
import com.vyora.music.data.db.SearchEntity
import com.vyora.music.data.db.VyoraDatabase
import java.util.concurrent.Executors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object SearchHistory {
    private const val MAX = 12

    private val scope = CoroutineScope(SupervisorJob() + Executors.newSingleThreadExecutor { r ->
        Thread(r, "vyora-search").apply { isDaemon = true }
    }.asCoroutineDispatcher())

    private val _recent = MutableStateFlow<List<String>>(emptyList())
    val recent: StateFlow<List<String>> = _recent.asStateFlow()
    private var db: VyoraDatabase? = null

    @Synchronized
    fun init(context: Context) {
        if (db != null) return
        val database = VyoraDatabase.get(context)
        db = database
        scope.launch { database.library().searchesFlow().collect { _recent.value = it.take(MAX) } }
    }

    fun add(query: String) {
        val q = query.trim()
        if (q.isBlank()) return
        _recent.value = (listOf(q) + _recent.value.filterNot { it.equals(q, ignoreCase = true) }).take(MAX)
        write { dao ->
            dao.upsertSearch(SearchEntity(q.lowercase(), q, System.currentTimeMillis()))
            dao.trimSearches(MAX)
        }
    }

    fun remove(query: String) {
        _recent.value = _recent.value - query
        write { it.deleteSearch(query.lowercase()) }
    }

    fun clear() {
        _recent.value = emptyList()
        write { it.clearSearches() }
    }

    private fun write(block: suspend (com.vyora.music.data.db.LibraryDao) -> Unit) {
        val database = db ?: return
        scope.launch { runCatching { block(database.library()) } }
    }
}

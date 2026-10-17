package com.vyora.music.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vyora.music.data.youtube.SearchFilter
import com.vyora.music.data.youtube.SimpleItem
import com.vyora.music.data.youtube.YouTubeClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.Page

enum class SearchTab(val filter: SearchFilter?) {
    ALL(null),
    SONGS(SearchFilter.SONGS),
    ARTISTS(SearchFilter.ARTISTS),
    ALBUMS(SearchFilter.ALBUMS),
    PLAYLISTS(SearchFilter.PLAYLISTS),
    VIDEOS(SearchFilter.VIDEOS)
}

enum class LoadStatus { IDLE, LOADING, LOADED, LOADING_MORE, ERROR }

data class TabState(
    val items: List<SimpleItem> = emptyList(),
    val nextPage: Page? = null,
    val status: LoadStatus = LoadStatus.IDLE,
    val error: String? = null
)

data class SearchUiState(
    val query: String = "",
    val submittedQuery: String = "",
    val tab: SearchTab = SearchTab.ALL,
    val tabs: Map<SearchTab, TabState> = emptyMap(),
    val suggestion: String? = null,
    val corrected: Boolean = false,
    val artistFocus: String? = null,
    val typedQuery: String? = null,
    val exact: Boolean = false,
    /** Search-as-you-type suggestions for [query] while it has not been submitted yet. */
    val suggestions: List<String> = emptyList(),
) {
    fun tabState(tab: SearchTab): TabState = tabs[tab] ?: TabState()
}

class SearchViewModel : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var generation = 0
    private val jobs = mutableMapOf<SearchTab, Job>()

    private var suggestJob: Job? = null

    fun onQueryChange(query: String) {
        suggestJob?.cancel()
        if (query.isBlank()) {
            reset()
            return
        }
        _state.update { it.copy(query = query) }
        suggestJob = viewModelScope.launch {
            kotlinx.coroutines.delay(SUGGEST_DEBOUNCE_MS)
            val list = YouTubeClient.suggestions(query)
            _state.update { if (it.query == query) it.copy(suggestions = list) else it }
        }
    }

    fun submit(
        override: String? = null,
        tab: SearchTab = SearchTab.ALL,
        artistFocus: String? = null,
        exact: Boolean = false,
        typedQuery: String? = null,
    ) {
        val q = (override ?: _state.value.query).trim().replace(Regex("\\s+"), " ")
        if (q.isBlank()) return
        suggestJob?.cancel()
        generation++
        cancelJobs()
        _state.value = SearchUiState(
            query = q, submittedQuery = q, tab = tab, artistFocus = artistFocus,
            exact = exact, typedQuery = typedQuery,
        )
        ensureLoaded(tab)
        if (tab != SearchTab.ALL && tab != SearchTab.SONGS) ensureLoaded(SearchTab.SONGS)
    }

    private fun focus(items: List<SimpleItem>): List<SimpleItem> {
        val name = _state.value.artistFocus?.trim()?.lowercase() ?: return items
        return items.sortedByDescending { item ->
            item.artist.lowercase().split(",", "&", "•", " feat.", " ft.", " x ").any { it.trim() == name }
        }
    }

    fun selectTab(tab: SearchTab) {
        _state.update { it.copy(tab = tab) }
        ensureLoaded(tab)
    }

    fun retry(tab: SearchTab) {
        if (tab == SearchTab.ALL) {
            SEARCH_TABS.filter { _state.value.tabState(it).status == LoadStatus.ERROR }.forEach { loadFirst(it) }
        } else {
            loadFirst(tab)
        }
    }

    fun loadMore(tab: SearchTab) {
        val filter = tab.filter ?: return
        val current = _state.value.tabState(tab)
        val page = current.nextPage
        if (current.status != LoadStatus.LOADED || page == null) return

        val gen = generation
        val q = _state.value.submittedQuery
        setTab(tab) { it.copy(status = LoadStatus.LOADING_MORE) }
        jobs[tab]?.cancel()
        jobs[tab] = viewModelScope.launch {
            val result = YouTubeClient.search(q, filter, page)
            if (gen != generation) return@launch
            result.fold(
                onSuccess = { more ->
                    setTab(tab) { cur ->
                        val merged = (cur.items + focus(more.items)).distinctBy { it.id }
                        val next = if (merged.size == cur.items.size) null else more.nextPage
                        cur.copy(items = merged, nextPage = next, status = LoadStatus.LOADED)
                    }
                },
                onFailure = { setTab(tab) { it.copy(status = LoadStatus.LOADED) } }
            )
        }
    }

    private fun ensureLoaded(tab: SearchTab) {
        if (tab == SearchTab.ALL) {
            SEARCH_TABS.forEach { ensureLoaded(it) }
        } else if (_state.value.tabState(tab).status == LoadStatus.IDLE) {
            loadFirst(tab)
        }
    }

    private fun loadFirst(tab: SearchTab) {
        val filter = tab.filter ?: return
        val gen = generation
        val q = _state.value.submittedQuery
        if (q.isBlank()) return
        jobs[tab]?.cancel()
        setTab(tab) { TabState(status = LoadStatus.LOADING) }
        jobs[tab] = viewModelScope.launch {
            val result = YouTubeClient.search(q, filter)
            if (gen != generation) return@launch
            result.fold(
                onSuccess = { page ->
                    if (tab == SearchTab.SONGS && page.suggestion != null) {
                        val st = _state.value
                        if (!page.corrected && !st.exact && st.typedQuery == null && st.artistFocus == null) {
                            submit(page.suggestion, st.tab, typedQuery = st.submittedQuery)
                            return@launch
                        }
                        _state.update { it.copy(suggestion = page.suggestion, corrected = page.corrected) }
                    }
                    setTab(tab) { TabState(items = focus(page.items), nextPage = page.nextPage, status = LoadStatus.LOADED) }
                },
                onFailure = { e ->
                    setTab(tab) { TabState(status = LoadStatus.ERROR, error = e.message) }
                }
            )
        }
    }

    private fun setTab(tab: SearchTab, transform: (TabState) -> TabState) {
        _state.update { s -> s.copy(tabs = s.tabs + (tab to transform(s.tabState(tab)))) }
    }

    private fun reset() {
        generation++
        cancelJobs()
        _state.value = SearchUiState()
    }

    private fun cancelJobs() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
    }

    companion object {
        const val SUGGEST_DEBOUNCE_MS = 220L

        /** How many of each kind the Top Results list mixes in. */
        val TOP_LIMITS = mapOf(
            SearchTab.SONGS to 6,
            SearchTab.ARTISTS to 3,
            SearchTab.ALBUMS to 4,
            SearchTab.PLAYLISTS to 3,
            SearchTab.VIDEOS to 3,
        )

        /** The order kinds take turns in Top Results — songs lead, as in Apple Music. */
        private val TOP_PATTERN = listOf(
            SearchTab.SONGS, SearchTab.SONGS, SearchTab.ALBUMS, SearchTab.ARTISTS, SearchTab.SONGS,
            SearchTab.PLAYLISTS, SearchTab.VIDEOS, SearchTab.SONGS, SearchTab.ALBUMS, SearchTab.ARTISTS,
        )

        /**
         * One ranked list across every kind ("Top Results"): an artist whose name is exactly what was
         * searched comes first, then the kinds take turns by [TOP_PATTERN] until each hits its limit.
         */
        fun topResults(tabs: Map<SearchTab, List<SimpleItem>>, wanted: String): List<SimpleItem> {
            val queues = SEARCH_TABS.associateWith { tab ->
                ArrayDeque(tabs[tab].orEmpty().take(TOP_LIMITS.getValue(tab)))
            }
            val out = mutableListOf<SimpleItem>()
            val seen = HashSet<String>()
            fun take(tab: SearchTab) {
                val q = queues.getValue(tab)
                while (q.isNotEmpty()) {
                    val item = q.removeFirst()
                    if (seen.add(item.id)) {
                        out += item
                        return
                    }
                }
            }
            val exactArtist = tabs[SearchTab.ARTISTS].orEmpty().firstOrNull {
                wanted.isNotEmpty() && normalize(it.title) == wanted
            }
            if (exactArtist != null) {
                seen += exactArtist.id
                out += exactArtist
                queues.getValue(SearchTab.ARTISTS).removeAll { it.id == exactArtist.id }
            }
            while (queues.values.any { it.isNotEmpty() }) {
                TOP_PATTERN.forEach(::take)
            }
            return out
        }

        val SEARCH_TABS = listOf(SearchTab.SONGS, SearchTab.ARTISTS, SearchTab.ALBUMS, SearchTab.PLAYLISTS, SearchTab.VIDEOS)

        fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    }
}

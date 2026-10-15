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

/** [ALL] is a summary view; every other tab maps 1:1 to a YouTube Music filter. */
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
    /** What is currently typed in the text field. */
    val query: String = "",
    /** The query the results below belong to; blank = nothing searched yet. */
    val submittedQuery: String = "",
    val tab: SearchTab = SearchTab.ALL,
    val tabs: Map<SearchTab, TabState> = emptyMap(),
    /** YouTube's spelling fix for [submittedQuery]; see [corrected]. */
    val suggestion: String? = null,
    /** True: results are already for [suggestion]. False: it is only "did you mean". */
    val corrected: Boolean = false,
    /** Set when an artist row was tapped: that artist's songs are pulled to the top. */
    val artistFocus: String? = null,
    /**
     * What the user actually typed when we switched to YouTube's spelling suggestion
     * ourselves ("nadin amizha" -> "nadin amizah"); null when no switch happened.
     */
    val typedQuery: String? = null,
    /** True when the user asked for the literal query, never auto-correct it. */
    val exact: Boolean = false,
) {
    fun tabState(tab: SearchTab): TabState = tabs[tab] ?: TabState()
}

/**
 * Holds search results so they survive navigating to an album/playlist and back.
 * Each category is fetched and paged independently; the "All" tab just shows the
 * first few rows of each, so switching to "See all" reuses what is already loaded.
 */
class SearchViewModel : ViewModel() {

    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    /** Bumped on every new search so late responses from an old query are dropped. */
    private var generation = 0
    private val jobs = mutableMapOf<SearchTab, Job>()

    fun onQueryChange(query: String) {
        if (query.isBlank()) {
            reset()
            return
        }
        _state.update { it.copy(query = query) }
    }

    /** Searches [override] if given (and shows it in the text field), else the typed query. */
    fun submit(
        override: String? = null,
        tab: SearchTab = SearchTab.ALL,
        artistFocus: String? = null,
        exact: Boolean = false,
        typedQuery: String? = null,
    ) {
        // Collapse runs of spaces: "tulus  hati" and "tulus hati" are the same search.
        val q = (override ?: _state.value.query).trim().replace(Regex("\\s+"), " ")
        if (q.isBlank()) return
        generation++
        cancelJobs()
        _state.value = SearchUiState(
            query = q, submittedQuery = q, tab = tab, artistFocus = artistFocus,
            exact = exact, typedQuery = typedQuery,
        )
        ensureLoaded(tab)
        // The spelling hint comes with the songs page, so always fetch that one too.
        if (tab != SearchTab.ALL && tab != SearchTab.SONGS) ensureLoaded(SearchTab.SONGS)
    }

    /** Songs by the focused artist first, keeping YouTube's order inside each group. */
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
                        // No new rows means the cursor is stuck: stop paging instead of looping.
                        val next = if (merged.size == cur.items.size) null else more.nextPage
                        cur.copy(items = merged, nextPage = next, status = LoadStatus.LOADED)
                    }
                },
                // Keep what we have and the same cursor so scrolling again can retry.
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
                            // YouTube only *suggested* a fix, so the other categories were run
                            // with the typo and come back thin or wrong (no artists, random
                            // albums). Do what Apple Music does: search the fixed spelling and
                            // offer the literal one as a link.
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
        /** The four real categories, in display order. */
        val SEARCH_TABS = listOf(SearchTab.SONGS, SearchTab.ARTISTS, SearchTab.ALBUMS, SearchTab.PLAYLISTS, SearchTab.VIDEOS)

        /** Loose comparison for "is this artist exactly what was typed". */
        fun normalize(s: String) = s.lowercase().filter { it.isLetterOrDigit() }
    }
}

package com.vyora.music.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Celebration
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material.icons.filled.Spa
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vyora.music.R
import com.vyora.music.data.library.SearchHistory
import com.vyora.music.data.youtube.ItemType
import com.vyora.music.data.youtube.SimpleItem
import com.vyora.music.ui.components.ItemRow
import com.vyora.music.ui.components.LocalBottomBarInset
import com.vyora.music.ui.design.GlassChip
import com.vyora.music.ui.design.GlassEmptyState
import com.vyora.music.ui.design.GlassShapes
import com.vyora.music.ui.design.Hairline
import com.vyora.music.ui.design.LargeTitleHeader
import com.vyora.music.ui.design.OnTabReselect
import com.vyora.music.ui.design.SectionTitle
import com.vyora.music.ui.design.Spacing
import com.vyora.music.ui.design.glass
import com.vyora.music.ui.design.glassClickable
import com.vyora.music.ui.design.isAtTop
import com.vyora.music.ui.design.liquidFill
import com.vyora.music.ui.design.scrollToTopSmooth
import com.vyora.music.ui.theme.GlassFill
import com.vyora.music.ui.theme.GlassFillStrong
import com.vyora.music.ui.theme.MoodColors
import com.vyora.music.ui.theme.VyoraAccent
import com.vyora.music.ui.theme.VyoraSurface
import com.vyora.music.ui.theme.VyoraText
import com.vyora.music.ui.theme.VyoraTextFaint
import com.vyora.music.ui.theme.VyoraTextMuted

@Composable
fun SearchScreen(
    onPlay: (List<SimpleItem>, Int) -> Unit,
    onOpenPlaylist: (SimpleItem) -> Unit,
    modifier: Modifier = Modifier,
    onOpenArtist: (SimpleItem) -> Unit = {},
    viewModel: SearchViewModel = viewModel()
) {
    val state by viewModel.state.collectAsState()
    val focusManager = LocalFocusManager.current
    val searching = state.submittedQuery.isNotBlank()
    val recentSearches by SearchHistory.recent.collectAsState()
    var fieldFocused by remember { mutableStateOf(false) }
    val fieldFocus = remember { FocusRequester() }

    fun submit(query: String? = null, tab: SearchTab = SearchTab.ALL, artistFocus: String? = null) {
        SearchHistory.add(query ?: state.query)
        viewModel.submit(query, tab, artistFocus)
        focusManager.clearFocus()
    }

    fun openItem(item: SimpleItem, queue: List<SimpleItem>, index: Int) {
        when (item.type) {
            ItemType.SONG -> onPlay(queue, index)
            ItemType.ALBUM, ItemType.PLAYLIST -> onOpenPlaylist(item)
            ItemType.ARTIST -> onOpenArtist(item)
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(start = Spacing.ScreenH, end = Spacing.ScreenH, top = 18.dp)
        ) {
            AnimatedVisibility(visible = !searching, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                LargeTitleHeader(
                    title = stringResource(R.string.search),
                    modifier = Modifier.padding(bottom = 12.dp),
                )
            }
            GlassSearchField(
                value = state.query,
                onValueChange = viewModel::onQueryChange,
                onSearch = { submit() },
                onClear = { viewModel.onQueryChange("") },
                focused = fieldFocused,
                focusRequester = fieldFocus,
                onFocusChange = { fieldFocused = it },
                onCancel = {
                    viewModel.onQueryChange("")
                    focusManager.clearFocus()
                },
            )
            if (searching) {
                Spacer(Modifier.height(12.dp))
                TabChips(selected = state.tab, onSelect = viewModel::selectTab)
                SpellingHint(state, onPick = { q, exact ->
                    SearchHistory.add(q)
                    viewModel.submit(q, state.tab, exact = exact)
                    focusManager.clearFocus()
                })
            }
            Spacer(Modifier.height(12.dp))
        }

        Box(Modifier.fillMaxSize().padding(horizontal = Spacing.ScreenH)) {
            val typing = fieldFocused && state.query.isNotBlank() && state.query.trim() != state.submittedQuery
            if (fieldFocused && state.query.isBlank() && recentSearches.isNotEmpty()) {
                RecentSearches(recentSearches, onPick = { viewModel.onQueryChange(it); submit(it) })
            } else if (typing) {
                Suggestions(
                    query = state.query,
                    suggestions = state.suggestions,
                    onPick = { viewModel.onQueryChange(it); submit(it) },
                )
            } else if (!searching) {
                Discovery(onMood = { submit(it) }, onFocusSearch = { runCatching { fieldFocus.requestFocus() } })
            } else {
                key(state.tab) {
                    if (state.tab == SearchTab.ALL) {
                        AllTab(
                            state = state,
                            onItem = ::openItem,
                            onSeeAll = viewModel::selectTab,
                            onRetry = viewModel::retry
                        )
                    } else {
                        SingleTab(
                            tab = state.tab,
                            tabState = state.tabState(state.tab),
                            onItem = ::openItem,
                            onLoadMore = { viewModel.loadMore(state.tab) },
                            onRetry = { viewModel.retry(state.tab) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GlassSearchField(
    focusRequester: FocusRequester,
    value: String,
    onValueChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit,
    focused: Boolean,
    onFocusChange: (Boolean) -> Unit,
    onCancel: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier
                .weight(1f)
                .height(40.dp)
                .liquidFill(shape = SearchFieldShape, fill = if (focused) GlassFillStrong else GlassFill)
                .padding(start = 10.dp, end = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Default.Search, null, tint = VyoraTextMuted, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(6.dp))
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (value.isEmpty()) {
                    Text(stringResource(R.string.search_field), color = VyoraTextMuted, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = VyoraText),
                    cursorBrush = SolidColor(VyoraAccent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester).onFocusChanged { onFocusChange(it.isFocused) },
                )
            }
            AnimatedVisibility(visible = value.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                Box(
                    Modifier
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(VyoraTextMuted)
                        .glassClickable(pressedScale = 0.85f, onClick = onClear),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Close, stringResource(R.string.clear), tint = VyoraSurface, modifier = Modifier.size(13.dp))
                }
            }
        }
        AnimatedVisibility(visible = focused || value.isNotEmpty(), enter = fadeIn() + expandHorizontally(), exit = fadeOut() + shrinkHorizontally()) {
            Text(
                stringResource(R.string.cancel),
                color = VyoraAccent,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                modifier = Modifier
                    .padding(start = 12.dp)
                    .glassClickable(onClick = onCancel),
            )
        }
    }
}

@Composable
private fun RecentSearches(queries: List<String>, onPick: (String) -> Unit) {
    val bottomInset = LocalBottomBarInset.current
    LazyColumn(contentPadding = PaddingValues(top = 6.dp, bottom = 24.dp + bottomInset)) {
        item(key = "recent_title") {
            SectionTitle(
                title = stringResource(R.string.recent_searches),
                action = stringResource(R.string.clear),
                onAction = { SearchHistory.clear() },
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        item(key = "recent_group") {
          Column(Modifier.fillMaxWidth()) {
          queries.forEachIndexed { i, q ->
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .glassClickable(pressedScale = 0.985f) { onPick(q) }
                        .padding(vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.History, null, tint = VyoraTextMuted, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(q, color = VyoraText, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Icon(
                        Icons.Default.Close, stringResource(R.string.remove), tint = VyoraTextFaint,
                        modifier = Modifier.size(44.dp).glassClickable(pressedScale = 0.85f) { SearchHistory.remove(q) }.padding(12.dp),
                    )
                }
                Hairline(startIndent = 32.dp)
            }
          }
          }
        }
    }
}

@Composable
private fun AllTab(
    state: SearchUiState,
    onItem: (SimpleItem, List<SimpleItem>, Int) -> Unit,
    onSeeAll: (SearchTab) -> Unit,
    onRetry: (SearchTab) -> Unit
) {
    val bottomInset = LocalBottomBarInset.current
    val sections = SearchViewModel.SEARCH_TABS.map { it to state.tabState(it) }
    val wanted = SearchViewModel.normalize(state.corrected.takeIf { it }?.let { state.suggestion } ?: state.submittedQuery)
    val allSettled = sections.all { (_, s) -> s.status == LoadStatus.LOADED || s.status == LoadStatus.ERROR }
    val nothingFound = allSettled && sections.all { (_, s) -> s.items.isEmpty() }
    val everythingFailed = allSettled && sections.all { (_, s) -> s.status == LoadStatus.ERROR }
    val songsReady = state.tabState(SearchTab.SONGS).status.let { it == LoadStatus.LOADED || it == LoadStatus.ERROR }
    val results = remember(state.tabs, wanted) {
        SearchViewModel.topResults(sections.associate { (tab, s) -> tab to s.items }, wanted)
    }
    val playable = remember(results) { results.filter { it.isSong } }
    val loadingKinds = sections.any { (_, s) -> s.status == LoadStatus.IDLE || s.status == LoadStatus.LOADING }

    val allListState = rememberLazyListState()
    OnTabReselect("search") { allListState.scrollToTopSmooth() }

    when {
        everythingFailed -> ErrorBlock(onRetry = { onRetry(SearchTab.ALL) })
        nothingFound -> EmptyBlock()
        results.isEmpty() || !songsReady -> Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) { InlineLoading() }
        else -> LazyColumn(
            state = allListState,
            contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp + bottomInset)
        ) {
            itemsIndexed(results, key = { _, item -> "top_${item.type}_${item.id}" }) { index, item ->
                ItemRow(
                    item = item,
                    showKind = true,
                    showSeparator = index < results.lastIndex,
                    onClick = {
                        if (item.isSong) onItem(item, playable, playable.indexOf(item).coerceAtLeast(0))
                        else onItem(item, listOf(item), 0)
                    },
                )
            }
            if (loadingKinds) item(key = "top_loading") { InlineLoading() }
            sections.filter { (tab, s) -> s.status == LoadStatus.ERROR }.forEach { (tab, _) ->
                item(key = "error_${tab.name}") {
                    TextButton(onClick = { onRetry(tab) }) {
                        Text(stringResource(R.string.search_retry_kind, tabTitle(tab)), color = VyoraAccent)
                    }
                }
            }
            item(key = "see_more") {
                Column(Modifier.padding(top = 18.dp)) {
                    SectionTitle(title = stringResource(R.string.search_more_results))
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(SearchViewModel.SEARCH_TABS, key = { "more_" + it.name }) { tab ->
                            GlassChip(label = tabTitle(tab), selected = false, onClick = { onSeeAll(tab) }, icon = tabIcon(tab))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Suggestions(query: String, suggestions: List<String>, onPick: (String) -> Unit) {
    val bottomInset = LocalBottomBarInset.current
    val rows = remember(query, suggestions) {
        (listOf(query.trim()) + suggestions).distinctBy { it.lowercase() }.filter { it.isNotBlank() }
    }
    LazyColumn(contentPadding = PaddingValues(top = 2.dp, bottom = 24.dp + bottomInset)) {
        itemsIndexed(rows, key = { _, q -> "suggest_$q" }) { i, q ->
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .glassClickable(pressedScale = 0.985f) { onPick(q) }
                        .padding(vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Search, null, tint = VyoraTextMuted, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(
                        highlightTyped(q, query),
                        color = VyoraText,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (i < rows.lastIndex) Hairline(startIndent = 32.dp)
            }
        }
    }
}

/** Apple Music style: what was typed in regular weight, the completion in bold. */
private fun highlightTyped(suggestion: String, typed: String): androidx.compose.ui.text.AnnotatedString {
    val t = typed.trim()
    return androidx.compose.ui.text.buildAnnotatedString {
        if (t.isNotEmpty() && suggestion.startsWith(t, ignoreCase = true)) {
            append(suggestion.substring(0, t.length))
            pushStyle(androidx.compose.ui.text.SpanStyle(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
            append(suggestion.substring(t.length))
            pop()
        } else {
            append(suggestion)
        }
    }
}

private val SearchFieldShape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)

@Composable
private fun SingleTab(
    tab: SearchTab,
    tabState: TabState,
    onItem: (SimpleItem, List<SimpleItem>, Int) -> Unit,
    onLoadMore: () -> Unit,
    onRetry: () -> Unit
) {
    val bottomInset = LocalBottomBarInset.current
    when {
        tabState.status == LoadStatus.IDLE || (tabState.status == LoadStatus.LOADING && tabState.items.isEmpty()) ->
            Box(Modifier.fillMaxWidth().padding(top = 24.dp), contentAlignment = Alignment.Center) { InlineLoading() }
        tabState.status == LoadStatus.ERROR -> ErrorBlock(onRetry = onRetry)
        tabState.items.isEmpty() -> EmptyBlock()
        else -> {
            val listState = rememberLazyListState()
            val nearEnd by remember {
                derivedStateOf {
                    val info = listState.layoutInfo
                    val last = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                    info.totalItemsCount > 0 && last >= info.totalItemsCount - 4
                }
            }
            LaunchedEffect(nearEnd, tabState.items.size, tabState.status) {
                if (nearEnd) onLoadMore()
            }
            OnTabReselect("search") { listState.scrollToTopSmooth() }
            LazyColumn(
                state = listState,
                                contentPadding = PaddingValues(bottom = 24.dp + bottomInset)
            ) {
                itemRows(tab, tabState.items, fullQueue = tabState.items, onItem)
                if (tabState.status == LoadStatus.LOADING_MORE) {
                    item(key = "more_loading") {
                        Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) { InlineLoading() }
                    }
                }
            }
        }
    }
}

private fun LazyListScope.itemRows(
    tab: SearchTab,
    shown: List<SimpleItem>,
    fullQueue: List<SimpleItem>,
    onItem: (SimpleItem, List<SimpleItem>, Int) -> Unit
) {
    itemsIndexed(shown, key = { _, item -> "${tab.name}_${item.id}" }) { index, item ->
        ItemRow(item, onClick = { onItem(item, fullQueue, index) })
    }
}

@Composable
private fun TabChips(selected: SearchTab, onSelect: (SearchTab) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(SearchTab.entries.toList(), key = { it.name }) { tab ->
            GlassChip(label = tabTitle(tab), selected = tab == selected, onClick = { onSelect(tab) })
        }
    }
}

@Composable
private fun SpellingHint(state: SearchUiState, onPick: (String, Boolean) -> Unit) {
    val typed = state.typedQuery
    val shown = when {
        typed != null -> state.submittedQuery
        state.corrected -> state.suggestion
        else -> null
    }
    if (shown != null) {
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.search_showing_for, shown),
            style = MaterialTheme.typography.bodyMedium,
            color = VyoraTextMuted,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
        if (typed != null) {
            Text(
                stringResource(R.string.search_instead_for, typed),
                style = MaterialTheme.typography.bodyMedium,
                color = VyoraAccent,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp).clickable { onPick(typed, true) },
            )
        }
        return
    }
    val suggestion = state.suggestion ?: return
    Spacer(Modifier.height(10.dp))
    Text(
        stringResource(R.string.search_did_you_mean, suggestion),
        style = MaterialTheme.typography.bodyMedium,
        color = VyoraAccent,
        maxLines = 1,
        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        modifier = Modifier.clickable { onPick(suggestion, false) },
    )
}

@Composable
private fun InlineLoading() {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        CircularProgressIndicator(modifier = Modifier.size(18.dp), color = VyoraAccent, strokeWidth = 2.dp)
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.search_loading), color = VyoraTextMuted, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ErrorBlock(onRetry: () -> Unit) {
    GlassEmptyState(
        icon = Icons.Default.WifiOff,
        title = stringResource(R.string.search_error_title),
        body = stringResource(R.string.home_reco_error_body),
        actionLabel = stringResource(R.string.try_again),
        onAction = onRetry,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun EmptyBlock() {
    GlassEmptyState(
        icon = Icons.Default.SearchOff,
        title = stringResource(R.string.search_empty_title),
        body = stringResource(R.string.search_empty_body),
        modifier = Modifier.padding(top = 12.dp),
    )
}

private data class Mood(val label: Int, val query: Int, val icon: ImageVector, val colors: List<Color>)

private val Moods = listOf(
    Mood(R.string.mood_chill, R.string.mood_q_chill, Icons.Default.Spa, MoodColors.Chill),
    Mood(R.string.mood_pop, R.string.mood_q_pop, Icons.Default.Star, MoodColors.Pop),
    Mood(R.string.mood_indonesian, R.string.mood_q_indonesian, Icons.Default.Favorite, MoodColors.Indonesian),
    Mood(R.string.mood_workout, R.string.mood_q_workout, Icons.Default.FitnessCenter, MoodColors.Workout),
    Mood(R.string.mood_focus, R.string.mood_q_focus, Icons.Default.Headphones, MoodColors.Focus),
    Mood(R.string.mood_acoustic, R.string.mood_q_acoustic, Icons.Default.MusicNote, MoodColors.Acoustic),
    Mood(R.string.mood_party, R.string.mood_q_party, Icons.Default.Celebration, MoodColors.Party),
    Mood(R.string.mood_sleep, R.string.mood_q_sleep, Icons.Default.Bedtime, MoodColors.Sleep),
    Mood(R.string.mood_hiphop, R.string.mood_q_hiphop, Icons.Default.Mic, MoodColors.HipHop),
    Mood(R.string.mood_rnb, R.string.mood_q_rnb, Icons.Default.Favorite, MoodColors.RnB),
    Mood(R.string.mood_rock, R.string.mood_q_rock, Icons.Default.Album, MoodColors.Rock),
    Mood(R.string.mood_kpop, R.string.mood_q_kpop, Icons.Default.Star, MoodColors.KPop),
)

@Composable
private fun Discovery(onMood: (String) -> Unit, onFocusSearch: () -> Unit) {
    val bottomInset = LocalBottomBarInset.current
    val listState = rememberLazyListState()
    OnTabReselect("search") {
        if (listState.isAtTop) onFocusSearch() else listState.scrollToTopSmooth()
    }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(top = 10.dp, bottom = 24.dp + bottomInset),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "mood_title") {
            SectionTitle(title = stringResource(R.string.browse_categories), modifier = Modifier.padding(bottom = 2.dp))
        }
        items(Moods.chunked(2), key = { it.first().label }) { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { mood ->
                    val label = stringResource(mood.label)
                    val query = stringResource(mood.query)
                    MoodTile(label, mood.icon, mood.colors, Modifier.weight(1f)) { onMood(query) }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun MoodTile(label: String, icon: ImageVector, colors: List<Color>, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .aspectRatio(1.6f)
            .glassClickable(onClick = onClick)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp))
            .background(Brush.linearGradient(colors))
            .padding(12.dp),
    ) {
        Icon(
            icon, null, tint = Color.White.copy(alpha = 0.55f),
            modifier = Modifier.align(Alignment.TopEnd).size(46.dp).graphicsLayer { rotationZ = 18f; translationX = 18f; translationY = -6f },
        )
        Text(
            label, color = Color.White, style = MaterialTheme.typography.titleMedium,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(end = 28.dp),
        )
    }
}

@Composable
private fun tabTitle(tab: SearchTab): String = stringResource(
    when (tab) {
        SearchTab.ALL -> R.string.search_top_results
        SearchTab.SONGS -> R.string.tab_songs
        SearchTab.ARTISTS -> R.string.tab_artists
        SearchTab.ALBUMS -> R.string.tab_albums
        SearchTab.PLAYLISTS -> R.string.tab_playlists
        SearchTab.VIDEOS -> R.string.tab_videos
    }
)

private fun tabIcon(tab: SearchTab): ImageVector = when (tab) {
    SearchTab.SONGS -> Icons.Default.MusicNote
    SearchTab.ARTISTS -> Icons.Default.Mic
    SearchTab.ALBUMS -> Icons.Default.Album
    SearchTab.VIDEOS -> Icons.Default.Videocam
    else -> Icons.Default.LibraryMusic
}

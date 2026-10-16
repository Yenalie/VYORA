package com.vyora.music.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.rounded.ArrowBackIosNew
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vyora.music.R
import com.vyora.music.data.db.DayRow
import com.vyora.music.data.db.TopArtistRow
import com.vyora.music.data.db.TopSongRow
import com.vyora.music.data.db.TotalsRow
import com.vyora.music.data.library.LibraryManager
import com.vyora.music.data.youtube.SimpleItem
import com.vyora.music.ui.components.GlassMenu
import com.vyora.music.ui.components.LocalBottomBarInset
import com.vyora.music.ui.components.MenuAction
import com.vyora.music.ui.design.Artwork
import com.vyora.music.ui.design.GlassChip
import com.vyora.music.ui.design.GlassEmptyState
import com.vyora.music.ui.design.GlassIconButton
import com.vyora.music.ui.design.GlassShapes
import com.vyora.music.ui.design.Hairline
import com.vyora.music.ui.design.LargeTitleHeader
import com.vyora.music.ui.design.SectionTitle
import com.vyora.music.ui.design.Spacing
import com.vyora.music.ui.design.glassClickable
import com.vyora.music.ui.screens.settings.ConfirmDialog
import com.vyora.music.ui.theme.GlassFillStrong
import com.vyora.music.ui.theme.VyoraAccent
import com.vyora.music.ui.theme.VyoraAccentSecondary
import com.vyora.music.ui.theme.VyoraText
import com.vyora.music.ui.theme.VyoraTextFaint
import com.vyora.music.ui.theme.VyoraTextMuted
import java.text.NumberFormat
import java.util.Calendar
import java.util.TimeZone
import kotlinx.coroutines.flow.flowOf

enum class RecapPeriod(val days: Int) { WEEK(7), MONTH(30), ALL(0) }

/** Start of the period, aligned to local midnight so "7 days" means today plus the six before it. */
internal fun recapSince(period: RecapPeriod, now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): Long {
    if (period == RecapPeriod.ALL) return 0L
    return Calendar.getInstance(zone).apply {
        timeInMillis = now
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        add(Calendar.DAY_OF_YEAR, -(period.days - 1))
    }.timeInMillis
}

/** One value per day for the last [days] days (oldest first), zero-filled. */
internal fun dailySeries(rows: List<DayRow>, days: Int, now: Long = System.currentTimeMillis(), zone: TimeZone = TimeZone.getDefault()): List<Long> {
    val offset = zone.getOffset(now).toLong()
    val today = (now + offset) / 86_400_000L
    val byDay = rows.associate { it.day to it.listenedMs }
    return (days - 1 downTo 0).map { back -> byDay[today - back] ?: 0L }
}

@Composable
fun RecapScreen(
    onBack: () -> Unit,
    onPlay: (List<SimpleItem>, Int) -> Unit,
    onOpenArtist: (String, String?) -> Unit,
) {
    var period by rememberSaveable { mutableStateOf(RecapPeriod.MONTH) }
    var menu by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    val dao = remember { LibraryManager.stats() }
    val since = remember(period) { recapSince(period) }
    val chartDays = if (period == RecapPeriod.WEEK) 7 else 30
    val chartSince = remember(chartDays) { recapSince(if (chartDays == 7) RecapPeriod.WEEK else RecapPeriod.MONTH) }
    val offset = remember { TimeZone.getDefault().getOffset(System.currentTimeMillis()).toLong() }

    val totals by remember(dao, since) { dao?.totals(since) ?: flowOf(TotalsRow(0, 0, 0, 0)) }.collectAsState(TotalsRow(0, 0, 0, 0))
    val songs by remember(dao, since) { dao?.topSongs(since, 10) ?: flowOf(emptyList()) }.collectAsState(emptyList())
    val artists by remember(dao, since) { dao?.topArtists(since, 10) ?: flowOf(emptyList()) }.collectAsState(emptyList())
    val daily by remember(dao, chartSince) { dao?.daily(chartSince, offset) ?: flowOf(emptyList()) }.collectAsState(emptyList())
    val bottomInset = LocalBottomBarInset.current

    val songItems = remember(songs) { songs.map { it.toItem() } }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = Spacing.ScreenH, end = Spacing.ScreenH, bottom = 28.dp + bottomInset),
    ) {
        item(key = "top") {
            Column(Modifier.statusBarsPadding().padding(top = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlassIconButton(Icons.Rounded.ArrowBackIosNew, stringResource(R.string.back), onBack, size = 44.dp, iconSize = 20.dp)
                    Spacer(Modifier.weight(1f))
                    Box {
                        GlassIconButton(Icons.Default.MoreHoriz, stringResource(R.string.more), { menu = true }, size = 44.dp, iconSize = 22.dp)
                        GlassMenu(
                            expanded = menu,
                            onDismiss = { menu = false },
                            actions = listOf(
                                MenuAction(stringResource(R.string.recap_clear), Icons.Default.DeleteOutline) { confirmClear = true },
                            ),
                        )
                    }
                }
                Spacer(Modifier.height(14.dp))
                LargeTitleHeader(title = stringResource(R.string.recap_title), subtitle = stringResource(R.string.recap_subtitle))
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassChip(stringResource(R.string.recap_week), period == RecapPeriod.WEEK, { period = RecapPeriod.WEEK })
                    GlassChip(stringResource(R.string.recap_month), period == RecapPeriod.MONTH, { period = RecapPeriod.MONTH })
                    GlassChip(stringResource(R.string.recap_all), period == RecapPeriod.ALL, { period = RecapPeriod.ALL })
                }
                Spacer(Modifier.height(16.dp))
            }
        }

        if (totals.plays == 0) {
            item(key = "empty") {
                GlassEmptyState(
                    icon = Icons.Default.Insights,
                    title = stringResource(R.string.recap_empty_title),
                    body = stringResource(R.string.recap_empty_body),
                )
            }
        } else {
            item(key = "hero") { RecapHero(totals) }
            item(key = "chart") {
                Spacer(Modifier.height(Spacing.Section))
                SectionTitle(stringResource(if (chartDays == 7) R.string.recap_chart_week else R.string.recap_chart_month))
                Spacer(Modifier.height(12.dp))
                ListeningChart(dailySeries(daily, chartDays), Modifier.fillMaxWidth().height(120.dp))
            }
            if (artists.isNotEmpty()) {
                item(key = "artists_title") {
                    Spacer(Modifier.height(Spacing.Section))
                    SectionTitle(stringResource(R.string.recap_top_artists))
                    Spacer(Modifier.height(12.dp))
                }
                item(key = "artists") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        itemsIndexed(artists, key = { _, a -> a.artist }) { i, a -> ArtistBubble(i + 1, a) { onOpenArtist(a.artist, a.thumbnail) } }
                    }
                }
            }
            item(key = "songs_title") {
                Spacer(Modifier.height(Spacing.Section))
                SectionTitle(stringResource(R.string.recap_top_songs))
                Spacer(Modifier.height(6.dp))
            }
            itemsIndexed(songs, key = { _, s -> "song_" + s.itemId }) { i, s ->
                RankedSongRow(i + 1, s, last = i == songs.lastIndex) { onPlay(songItems, i) }
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            message = stringResource(R.string.recap_clear_confirm),
            onDismiss = { confirmClear = false },
            onConfirm = { confirmClear = false; LibraryManager.clearStats() },
        )
    }
}

private fun TopSongRow.toItem() = SimpleItem(
    id = itemId, title = title, artist = artist, thumbnail = thumbnail, url = SimpleItem.playbackUrl(itemId),
)

@Composable
private fun RecapHero(totals: TotalsRow) {
    val minutes = (totals.listenedMs / 60_000L).toInt()
    // Count up once when the card appears or the period changes, like a Replay reveal.
    val shown by animateIntAsState(minutes, tween(900, easing = FastOutSlowInEasing), label = "recapMinutes")
    val format = remember { NumberFormat.getIntegerInstance() }
    val accent = VyoraAccent
    Column(
        Modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(listOf(accent.copy(alpha = 0.95f), VyoraAccentSecondary.copy(alpha = 0.75f), accent.copy(alpha = 0.55f))),
                RoundedCornerShape(24.dp),
            )
            .padding(20.dp),
    ) {
        Text(stringResource(R.string.recap_minutes_label), color = Color.White.copy(alpha = 0.85f), style = MaterialTheme.typography.labelLarge)
        Text(
            format.format(shown),
            color = Color.White,
            style = MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold, fontSize = 52.sp),
            maxLines = 1,
        )
        Spacer(Modifier.height(14.dp))
        Row(Modifier.fillMaxWidth()) {
            HeroStat(format.format(totals.plays), stringResource(R.string.recap_plays_label), Modifier.weight(1f))
            HeroStat(format.format(totals.songs), stringResource(R.string.recap_songs_label), Modifier.weight(1f))
            HeroStat(format.format(totals.artists), stringResource(R.string.recap_artists_label), Modifier.weight(1f))
        }
    }
}

@Composable
private fun HeroStat(value: String, label: String, modifier: Modifier) {
    Column(modifier) {
        Text(value, color = Color.White, style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold))
        Text(label, color = Color.White.copy(alpha = 0.8f), style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}

@Composable
private fun ListeningChart(values: List<Long>, modifier: Modifier) {
    val accent = VyoraAccent
    val track = GlassFillStrong.copy(alpha = GlassFillStrong.alpha * 0.6f)
    val grow = remember { Animatable(0f) }
    LaunchedEffect(values) {
        grow.snapTo(0f)
        grow.animateTo(1f, tween(700, easing = FastOutSlowInEasing))
    }
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    Canvas(modifier) {
        val n = values.size.coerceAtLeast(1)
        val gap = if (n <= 7) 10.dp.toPx() else 3.dp.toPx()
        val barW = (size.width - gap * (n - 1)) / n
        val radius = CornerRadius(minOf(barW / 2f, 6.dp.toPx()))
        values.forEachIndexed { i, v ->
            val x = i * (barW + gap)
            drawRoundRect(track, Offset(x, 0f), Size(barW, size.height), radius)
            val h = size.height * (v.toFloat() / max) * grow.value
            if (h > 0f) {
                val isToday = i == values.lastIndex
                drawRoundRect(
                    if (isToday) accent else accent.copy(alpha = 0.65f),
                    Offset(x, size.height - h), Size(barW, h), radius,
                )
            }
        }
    }
}

@Composable
private fun ArtistBubble(rank: Int, artist: TopArtistRow, onClick: () -> Unit) {
    Column(
        Modifier.width(96.dp).glassClickable(pressedScale = 0.95f, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box {
            Artwork(artist.thumbnail, Modifier.size(88.dp), shape = CircleShape, placeholder = Icons.Default.Person, contentDescription = artist.artist)
            Box(
                Modifier.align(Alignment.BottomEnd).size(26.dp).background(VyoraAccent, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(rank.toString(), color = Color.White, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(artist.artist, color = VyoraText, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(pluralStringResource(R.plurals.recap_play_count, artist.plays, artist.plays), color = VyoraTextMuted, style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}

@Composable
private fun RankedSongRow(rank: Int, song: TopSongRow, last: Boolean, onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().glassClickable(pressedScale = 0.985f, onClick = onClick).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                rank.toString(),
                color = if (rank <= 3) VyoraAccent else VyoraTextFaint,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.width(30.dp),
            )
            Artwork(song.thumbnail, Modifier.size(52.dp), shape = GlassShapes.Small, contentDescription = song.title)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, color = VyoraText, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(song.artist, pluralStringResource(R.plurals.recap_play_count, song.plays, song.plays)).filter { it.isNotBlank() }.joinToString(" · "),
                    color = VyoraTextMuted, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (!last) Hairline(Modifier.align(Alignment.BottomStart), startIndent = 96.dp)
    }
}

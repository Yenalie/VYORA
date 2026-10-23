package com.vyora.music.ui.design

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Keys for a LazyColumn/LazyRow that are guaranteed unique. Lazy lists crash ("Key ... was already used") the
 * moment two items share a key, and ids from YouTube, Spotify or a music server do repeat: a playlist can hold
 * the same video twice, two artists can share a collaboration, a search can return one id in two sections.
 * The first occurrence keeps its plain key (so item state and animations stay stable); repeats get `#2`, `#3`.
 */
fun <T> uniqueKeys(items: List<T>, key: (T) -> Any): List<String> {
    val seen = HashMap<String, Int>(items.size * 2)
    return items.map { item ->
        val base = key(item).toString()
        val n = (seen[base] ?: 0) + 1
        seen[base] = n
        if (n == 1) base else "$base#$n"
    }
}

@Composable
fun <T> rememberUniqueKeys(items: List<T>, key: (T) -> Any): List<String> = remember(items) { uniqueKeys(items, key) }

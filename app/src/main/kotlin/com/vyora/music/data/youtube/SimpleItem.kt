package com.vyora.music.data.youtube

import org.json.JSONArray
import org.json.JSONObject

object ItemType {
    const val SONG = "song"
    const val ARTIST = "artist"
    const val ALBUM = "album"
    const val PLAYLIST = "playlist"
}

data class SimpleItem(
    val id: String,
    val title: String,
    val artist: String,
    val thumbnail: String?,
    val url: String,
    val duration: Long = 0L,
    val type: String = ItemType.SONG,
    val count: Long = -1L,
    val artistId: String? = null,
    val isVideo: Boolean = false,
) {
    val isSong: Boolean get() = type == ItemType.SONG
    val isCollection: Boolean get() = type == ItemType.ALBUM || type == ItemType.PLAYLIST

    companion object {
        fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"
    }
}

object SimpleItemJson {
    private const val ID = "id"
    private const val TITLE = "title"
    private const val ARTIST = "artist"
    private const val THUMBNAIL = "thumbnail"
    private const val URL = "url"
    private const val DURATION = "duration"
    private const val TYPE = "type"
    private const val COUNT = "count"
    private const val ARTIST_ID = "aid"
    private const val VIDEO = "video"

    fun toJson(item: SimpleItem): JSONObject = JSONObject()
        .put(ID, item.id)
        .put(TITLE, item.title)
        .put(ARTIST, item.artist)
        .put(THUMBNAIL, item.thumbnail ?: JSONObject.NULL)
        .put(URL, item.url)
        .put(DURATION, item.duration)
        .put(TYPE, item.type)
        .put(COUNT, item.count)
        .put(ARTIST_ID, item.artistId ?: JSONObject.NULL)
        .put(VIDEO, item.isVideo)

    fun fromJson(o: JSONObject): SimpleItem? {
        val id = o.optString(ID)
        if (id.isBlank()) return null
        return SimpleItem(
            id = id,
            title = o.optString(TITLE),
            artist = o.optString(ARTIST),
            thumbnail = o.optStringOrNull(THUMBNAIL),
            url = o.optString(URL).ifBlank { SimpleItem.watchUrl(id) },
            duration = o.optLong(DURATION, 0L),
            type = o.optString(TYPE).ifBlank { ItemType.SONG },
            count = o.optLong(COUNT, -1L),
            artistId = o.optStringOrNull(ARTIST_ID),
            isVideo = o.optBoolean(VIDEO, false),
        )
    }

    fun encode(list: List<SimpleItem>): String =
        JSONArray().apply { list.forEach { put(toJson(it)) } }.toString()

    fun decode(raw: String?): List<SimpleItem> {
        if (raw.isNullOrBlank()) return emptyList()
        val array = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return decode(array)
    }

    fun decode(array: JSONArray): List<SimpleItem> = buildList {
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let(::fromJson)?.let(::add)
        }
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key).takeIf { it.isNotBlank() }
}

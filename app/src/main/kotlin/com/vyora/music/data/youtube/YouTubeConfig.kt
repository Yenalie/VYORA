package com.vyora.music.data.youtube

object YouTubeConfig {
    const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
    const val WEB_ORIGIN = "https://www.youtube.com"

    const val MUSIC_ORIGIN = "https://music.youtube.com"

    const val CONNECT_TIMEOUT_SEC = 20L
    const val READ_TIMEOUT_SEC = 30L

    const val HTTP_TOO_MANY_REQUESTS = 429

    const val ALBUM_ID_PREFIX = "OLAK5uy_"

    const val RADIO_LIST_PREFIX = "RDAMVM"

    const val TOPIC_SUFFIX = " - Topic"

    const val VIDEO_THUMB_HOST = "ytimg.com"

    const val INNERTUBE_BASE = "$MUSIC_ORIGIN/youtubei/v1/"

    const val MUSIC_CLIENT_NAME = "WEB_REMIX"
    const val MUSIC_CLIENT_ID = "67"
    const val MUSIC_CLIENT_VERSION = "1.20260707.12.00"

    const val SIGN_IN_URL =
        "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&passive=true&continue=$MUSIC_ORIGIN/"

    const val SAPISID_COOKIE = "SAPISID"
    const val SECURE_SAPISID_COOKIE = "__Secure-3PAPISID"

    const val BROWSE_LIKED_SONGS = "FEmusic_liked_videos"
    const val BROWSE_LIBRARY_PLAYLISTS = "FEmusic_liked_playlists"
    const val BROWSE_LIBRARY_ALBUMS = "FEmusic_liked_albums"

    val SYSTEM_PLAYLISTS = setOf("LM", "SE", "VLLM", "VLSE")

    const val BROWSE_PLAYLIST_PREFIX = "VL"
}

package com.vyora.music.data.youtube

/** Fixed values of the YouTube integration, kept in one place instead of inline. */
object YouTubeConfig {
    /** Desktop Chrome: what NewPipe's web requests and web-client stream URLs expect. */
    const val BROWSER_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
    const val WEB_ORIGIN = "https://www.youtube.com"

    /** Links shared with other apps open in YouTube Music. */
    const val MUSIC_ORIGIN = "https://music.youtube.com"

    const val CONNECT_TIMEOUT_SEC = 20L
    const val READ_TIMEOUT_SEC = 30L

    /** HTTP status YouTube answers with when it wants a captcha solved. */
    const val HTTP_TOO_MANY_REQUESTS = 429

    /** Prefix of YouTube Music album playlist ids. */
    const val ALBUM_ID_PREFIX = "OLAK5uy_"

    /** Prefix of the "radio" mix for a track (what "Start radio" plays). */
    const val RADIO_LIST_PREFIX = "RDAMVM"

    /** Auto-generated YouTube Music channels ("Tulus - Topic") upload the audio-only songs. */
    const val TOPIC_SUFFIX = " - Topic"

    /** Video thumbnails come from this CDN; YouTube Music covers come from Google's image CDN. */
    const val VIDEO_THUMB_HOST = "ytimg.com"

    // ------------------------------------------------------------ signed-in YouTube Music

    /** The YouTube Music web app's own API, the one its library pages call. */
    const val INNERTUBE_BASE = "$MUSIC_ORIGIN/youtubei/v1/"

    /** WEB_REMIX = the music.youtube.com web client (InnerTube client id 67). */
    const val MUSIC_CLIENT_NAME = "WEB_REMIX"
    const val MUSIC_CLIENT_ID = "67"
    const val MUSIC_CLIENT_VERSION = "1.20260707.12.00"

    /** Google sign-in that lands on YouTube Music, where the session cookies get set. */
    const val SIGN_IN_URL =
        "https://accounts.google.com/ServiceLogin?ltmpl=music&service=youtube&passive=true&continue=$MUSIC_ORIGIN/"

    /** Cookie carrying the session id the request signature is built from. */
    const val SAPISID_COOKIE = "SAPISID"
    const val SECURE_SAPISID_COOKIE = "__Secure-3PAPISID"

    /** Browse ids of the library shelves. */
    const val BROWSE_LIKED_SONGS = "FEmusic_liked_videos"
    const val BROWSE_LIBRARY_PLAYLISTS = "FEmusic_liked_playlists"
    const val BROWSE_LIBRARY_ALBUMS = "FEmusic_liked_albums"

    /** Auto playlists that are not real user playlists ("Liked music", "Episodes for later"). */
    val SYSTEM_PLAYLISTS = setOf("LM", "SE", "VLLM", "VLSE")

    /** "VLPL123" (browse id) -> "PL123" (playlist id). */
    const val BROWSE_PLAYLIST_PREFIX = "VL"
}

package com.vyora.music.data.youtube

import com.grack.nanojson.JsonObject

/**
 * Reads YouTube's per-video loudness figure (`playerConfig.audioConfig.loudnessDb`, or a format's own
 * `loudnessDb`) from the player response NewPipe already fetched, so Sound Check costs no extra request.
 *
 * NewPipe keeps those responses private, hence the reflection; anything unexpected just yields `null`
 * and the track plays at its own level. The figure is relative to YouTube's reference loudness:
 * +6 means six decibels louder than the target, so the gain to apply is its negation.
 */
internal object Loudness {
    private val responseFields = listOf("playerResponse")
    private val streamingFields = listOf("androidStreamingData", "iosStreamingData", "visionOsStreamingData")

    fun read(extractor: Any): Double? = runCatching {
        val player = responseFields.firstNotNullOfOrNull { field(extractor, it) as? JsonObject }
        val streaming = streamingFields.mapNotNull { field(extractor, it) as? JsonObject }
        fromResponses(player, streaming)
    }.getOrNull()

    fun fromResponses(player: JsonObject?, streamingData: List<JsonObject>): Double? {
        player?.getObject("playerConfig")?.getObject("audioConfig")?.let { audio ->
            number(audio, "loudnessDb")?.let { return it }
        }
        val sources = listOfNotNull(player?.getObject("streamingData")) + streamingData
        for (data in sources) {
            val formats = data.getArray("adaptiveFormats") ?: continue
            for (i in 0 until formats.size) {
                val format = formats.getObject(i) ?: continue
                if (!format.getString("mimeType", "").startsWith("audio/")) continue
                number(format, "loudnessDb")?.let { return it }
            }
        }
        return null
    }

    private fun number(o: JsonObject, key: String): Double? =
        (o[key] as? Number)?.toDouble()?.takeIf { it.isFinite() && it in -60.0..60.0 }

    private fun field(target: Any, name: String): Any? {
        var type: Class<*>? = target.javaClass
        while (type != null) {
            runCatching { type.getDeclaredField(name) }.getOrNull()?.let { f ->
                f.isAccessible = true
                return f.get(target)
            }
            type = type.superclass
        }
        return null
    }

    /** Linear gain for a figure, bounded like BitChord: at most 15 dB quieter, at most 3 dB louder. */
    fun gainFor(loudnessDb: Double): Float {
        val db = (-loudnessDb).coerceIn(-15.0, 3.0)
        return Math.pow(10.0, db / 20.0).toFloat()
    }
}

/**
 * Remembers loudness figures across launches, so a song replayed from the cache or a download (which
 * never asks YouTube for its stream again) is still levelled.
 */
object LoudnessStore {
    private const val PREFS = "vyora_loudness"
    private const val MAX = 4000
    private val memory = java.util.concurrent.ConcurrentHashMap<String, Double>()
    @Volatile private var prefs: android.content.SharedPreferences? = null

    fun init(context: android.content.Context) {
        if (prefs != null) return
        val p = context.applicationContext.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
        prefs = p
        Thread {
            runCatching { p.all.forEach { (k, v) -> (v as? Float)?.let { memory.putIfAbsent(k, it.toDouble()) } } }
        }.apply { isDaemon = true }.start()
    }

    fun get(videoId: String): Double? = memory[videoId]

    fun put(videoId: String, loudnessDb: Double) {
        if (memory.put(videoId, loudnessDb) == loudnessDb) return
        val p = prefs ?: return
        p.edit().apply {
            if (memory.size > MAX) {
                clear()
                memory.clear()
                memory[videoId] = loudnessDb
            }
            putFloat(videoId, loudnessDb.toFloat())
        }.apply()
    }
}

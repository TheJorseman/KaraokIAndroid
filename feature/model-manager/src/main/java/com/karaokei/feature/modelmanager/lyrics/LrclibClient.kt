package com.karaokei.feature.modelmanager.lyrics

import com.karaokei.core.common.result.AppError
import com.karaokei.core.common.result.AppResult
import com.karaokei.core.common.result.runCatchingResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class LrclibTrack(
    val id: Long? = null,
    val trackName: String? = null,
    val artistName: String? = null,
    val albumName: String? = null,
    val duration: Double? = null,
    val instrumental: Boolean = false,
    val plainLyrics: String? = null,
    val syncedLyrics: String? = null,
)

/**
 * LRCLIB (lrclib.net) lyrics search. Free, no API key; only the track
 * title/artist/duration are sent — never audio (privacy: the online
 * lyrics sync is opt-in via `UserPreferences.lyricsOnlineSync`).
 *
 * This is the only network-facing lyrics component and lives in
 * `feature:model-manager`, the module allowed to open connections.
 */
@Singleton
class LrclibClient @Inject constructor() {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Find the best matching track for the local song. [localWords] is
     * the locally generated karaoke text used to score candidates;
     * returns null when nothing matches convincingly.
     */
    fun findBest(
        title: String,
        artist: String?,
        durationSec: Double,
        localWords: Set<String>,
    ): AppResult<LrclibTrack?> = runCatchingResult {
        val primary = buildString {
            append("track_name=").append(encode(cleanTitle(title)))
            if (!artist.isNullOrBlank()) {
                append("&artist_name=").append(encode(artist))
            }
        }
        val firstPass = search(primary)
        val candidates = if (firstPass.isEmpty() && !artist.isNullOrBlank()) {
            // Retry without the artist: file metadata is often wrong.
            search("track_name=${encode(cleanTitle(title))}")
        } else {
            firstPass
        }
        candidates
            .filterNot { it.instrumental }
            .filter { it.syncedLyrics != null || it.plainLyrics != null }
            .map { it to score(it, durationSec, localWords) }
            .filter { it.second >= MIN_SCORE }
            .maxByOrNull { it.second + if (it.first.syncedLyrics != null) SYNCED_BONUS else 0.0 }
            ?.first
    }.let { result ->
        when (result) {
            is AppResult.Success -> result
            is AppResult.Failure -> AppResult.Failure(
                AppError.Io("lyrics lookup failed: ${result.error.message}", result.error.cause),
            )
        }
    }

    private fun search(query: String): List<LrclibTrack> {
        val conn = (URL("$API_BASE/api/search?$query").openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "KaraokIAndroid/1.0 (lyrics sync)")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) return emptyList()
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return json.decodeFromString(body)
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Score a candidate by duration proximity and word overlap with
     * the locally generated lyrics. Duration within 3 s scores full,
     * up to a hard 30 s cut-off; word overlap is the Jaccard index of
     * normalized words.
     */
    private fun score(track: LrclibTrack, durationSec: Double, localWords: Set<String>): Double {
        val duration = track.duration ?: return 0.0
        val delta = kotlin.math.abs(duration - durationSec)
        if (delta > 30.0) return 0.0
        val durationScore = (1.0 - (delta / 30.0)).coerceIn(0.0, 1.0)
        val text = (track.syncedLyrics ?: track.plainLyrics).orEmpty()
        val words = text.split(WHITESPACE)
            .map { it.lowercase().replace(NON_WORD, "") }
            .filter { it.isNotEmpty() }
            .toSet()
        if (words.isEmpty()) return durationScore * 0.2
        val overlap = words.count { it in localWords }.toDouble() / (words.size + localWords.size) * 2.0
        return durationScore * 0.35 + overlap.coerceIn(0.0, 1.0) * 0.65
    }

    /** File-derived titles look like `te_juro_que_te_amo`. */
    private fun cleanTitle(title: String): String =
        title.replace(Regex("[_\\-]+"), " ").replace(WHITESPACE, " ").trim()

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private val WHITESPACE = Regex("\\s+")
    private val NON_WORD = Regex("[^\\p{L}\\p{N}']+")

    private companion object {
        const val API_BASE = "https://lrclib.net"
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 20_000
        const val MIN_SCORE = 0.30
        const val SYNCED_BONUS = 0.15
    }
}

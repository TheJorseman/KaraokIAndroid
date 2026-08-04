package com.karaokei.feature.karaoke.engine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * JSON schema for the final karaoke document consumed by the
 * player (T6.x). Stored at
 * `<filesDir>/cache/<song_id>/karaoke.json`.
 *
 * The schema is the source of truth for the renderer; the alignment
 * stage (T5) is responsible for producing it from the raw
 * `transcript.json` (which is in segment-of-words form) and the
 * line-breaking rules documented in [KaraokeLineBuilder].
 */
@Serializable
data class KaraokeDocument(
    @SerialName("version") val version: Int = 1,
    @SerialName("song_id") val songId: String,
    @SerialName("language") val language: String,
    @SerialName("duration") val duration: Double,
    @SerialName("lines") val lines: List<KaraokeLine>,
)

@Serializable
data class KaraokeLine(
    @SerialName("start") val start: Double,
    @SerialName("end") val end: Double,
    @SerialName("words") val words: List<KaraokeWord>,
)

@Serializable
data class KaraokeWord(
    @SerialName("text") val text: String,
    @SerialName("start") val start: Double,
    @SerialName("end") val end: Double,
    @SerialName("confidence") val confidence: Float = 1f,
)

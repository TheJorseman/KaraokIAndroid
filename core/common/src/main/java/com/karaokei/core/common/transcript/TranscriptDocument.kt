package com.karaokei.core.common.transcript

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * JSON schema for the `transcript.json` artifact written by the
 * transcription stage (T4.4). Stored under
 * `<filesDir>/cache/<song_id>/transcript.json`.
 */
@Serializable
data class TranscriptDocument(
    @SerialName("version") val version: Int = 1,
    @SerialName("song_id") val songId: String,
    @SerialName("language") val language: String,
    @SerialName("duration") val duration: Double,
    @SerialName("model_id") val modelId: String,
    @SerialName("segments") val segments: List<TranscriptSegment>,
)

@Serializable
data class TranscriptSegment(
    @SerialName("start") val start: Double,
    @SerialName("end") val end: Double,
    @SerialName("text") val text: String,
    @SerialName("confidence") val confidence: Float = 1f,
    @SerialName("no_speech_prob") val noSpeechProb: Float = 0f,
    @SerialName("words") val words: List<TranscriptWord> = emptyList(),
)

@Serializable
data class TranscriptWord(
    @SerialName("text") val text: String,
    @SerialName("start") val start: Double,
    @SerialName("end") val end: Double,
    @SerialName("confidence") val confidence: Float = 1f,
)

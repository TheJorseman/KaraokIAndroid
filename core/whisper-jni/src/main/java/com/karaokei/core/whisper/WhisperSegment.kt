package com.karaokei.core.whisper

/**
 * One transcribed segment produced by whisper.cpp.
 *
 * `words` is populated when word-level timestamps are available
 * (whisper.cpp exposes them via the per-token metadata in
 * `whisper_full_with_state`); otherwise the segment has zero words
 * and only the full text is meaningful.
 */
data class WhisperSegment(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val language: String,
    val words: List<WhisperWord> = emptyList(),
    val noSpeechProbability: Float = 0f,
)

data class WhisperWord(
    val text: String,
    val startMs: Long,
    val endMs: Long,
    val confidence: Float,
)

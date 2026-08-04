package com.karaokei.core.whisper

/**
 * Callback from the native side invoked for every transcribed
 * segment. Marshalled to the main thread by the implementation.
 *
 * The order is:
 *  1. `onLanguageDetected(language)` — fired exactly once, with the
 *     auto-detected language if `language` was `null`/empty in the
 *     call to [WhisperBridge.transcribeFile].
 *  2. `onSegment(segment)` — fired for each segment as it becomes
 *     available.
 *  3. `onCompleted()` — fired once at the end (always called if the
 *     session did not fail catastrophically).
 *  4. `onError(message)` — fired once if the session aborts.
 */
interface WhisperCallback {
    fun onLanguageDetected(language: String) {}
    fun onNativeSegment(
        text: String,
        startMs: Long,
        endMs: Long,
        language: String,
        noSpeechProbability: Float,
        words: String,
    ) {}
    fun onSegment(segment: WhisperSegment) {}
    fun onCompleted() {}
    fun onError(message: String) {}
}

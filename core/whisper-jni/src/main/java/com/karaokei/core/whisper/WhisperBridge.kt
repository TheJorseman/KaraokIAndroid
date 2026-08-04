package com.karaokei.core.whisper

import androidx.annotation.Keep

/**
 * Kotlin side of the whisper.cpp JNI bridge.
 *
 * Method names mirror the `Java_com_karaokei_core_whisper_*` exports
 * in `src/main/cpp/whisper_jni.cpp`. The `@Keep` annotation prevents
 * R8 from renaming them in release builds (the symbols are looked up
 * by name from C).
 *
 * The implementation is provided by the native `libkaraokei_whisper.so`
 * shipped in the AAB. The current build only contains the stub (see
 * `whisper_stub.cpp`); a real whisper.cpp integration is wired in by
 * vendoring the upstream sources and updating the CMakeLists.
 */
@Keep
object WhisperBridge {

    init {
        System.loadLibrary("karaokei_whisper")
    }

    /**
     * Load a model from disk and return an opaque handle.
     *
     * @return non-zero handle on success, 0 on failure.
     */
    @JvmStatic external fun nativeInit(modelPath: String, numThreads: Int): Long

    /** Release resources held by [handle]. Safe to call with 0. */
    @JvmStatic external fun nativeFree(handle: Long)

    /**
     * Transcribe [wavPath] (16 kHz / 16-bit / mono PCM).
     *
     * @param language ISO-639-1 two-letter code, or null/empty for
     *                 auto-detection.
     * @return 0 on success, non-zero on failure.
     */
    @JvmStatic external fun nativeTranscribeFile(
        handle: Long,
        wavPath: String,
        language: String?,
        translate: Boolean,
        callback: WhisperCallback,
    ): Int

    /** Last error message for [handle], or empty string. */
    @JvmStatic external fun nativeLastError(handle: Long): String

    // -----------------------------------------------------------------
    // Kotlin-side façade. Callers should NOT touch the native methods
    // directly; the helpers below handle resource lifetime and surface
    // typed errors.
    // -----------------------------------------------------------------

    fun open(modelPath: String, numThreads: Int = 0): Result<Long> {
        val handle = nativeInit(modelPath, numThreads)
        if (handle == 0L) {
            return Result.failure(IllegalStateException("whisper init failed: ${nativeLastError(0)}"))
        }
        return Result.success(handle)
    }

    fun close(handle: Long) {
        nativeFree(handle)
    }

    fun transcribeFile(
        handle: Long,
        wavPath: String,
        language: String?,
        translate: Boolean,
        callback: WhisperCallback,
    ): Result<Unit> {
        val code = nativeTranscribeFile(handle, wavPath, language, translate, callback)
        if (code != 0) {
            val msg = nativeLastError(handle)
            return Result.failure(IllegalStateException("whisper failed (code=$code): $msg"))
        }
        return Result.success(Unit)
    }
}

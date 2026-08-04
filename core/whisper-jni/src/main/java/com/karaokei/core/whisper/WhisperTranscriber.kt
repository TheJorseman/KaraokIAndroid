package com.karaokei.core.whisper

import android.content.Context
import com.karaokei.core.common.result.AppError
import com.karaokei.core.common.result.AppResult
import com.karaokei.core.common.result.runCatchingResult
import com.karaokei.core.data.db.entity.ModelEntity
import com.karaokei.core.data.db.entity.ModelType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import com.karaokei.core.common.coroutines.Dispatcher
import com.karaokei.core.common.coroutines.KaraokeDispatcher
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Higher-level wrapper around [WhisperBridge].
 *
 * - Resolves the model bytes to a path (Asset Pack → /data/data/.../files/).
 * - Streams segments back as a cold [Flow] of [WhisperSegment]s so the
 *   UI can render partial transcripts as they're produced.
 * - Releases the native context when the flow is cancelled or ends.
 *
 * Note: el idioma preferido se pasa como parámetro [language] para no
 * acoplar este módulo a `:core:data` (UserPreferences). El caller
 * (`TranscribeSongUseCase`) lee la preferencia y la pasa.
 */
@Singleton
class WhisperTranscriber @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(KaraokeDispatcher.IO) private val io: CoroutineDispatcher,
) {

    fun transcribe(
        wav: File,
        model: ModelEntity,
        language: String? = null,
    ): Flow<WhisperEvent> = callbackFlow {
        require(model.type == ModelType.TRANSCRIPTION) { "not a transcription model" }
        val modelPath = resolveModelPath(model)
            ?: throw IllegalStateException("cannot resolve model path for ${model.id}")
        val languageArg = language?.takeIf { it.isNotBlank() && it != "auto" }

        val opened = WhisperBridge.open(modelPath, numThreads = 0)
        val handle = opened.getOrElse { t ->
            trySend(WhisperEvent.Error(t.message ?: "whisper init failed"))
            close(t)
            return@callbackFlow
        }

        val nativeCallback = object : WhisperCallback {
            override fun onLanguageDetected(language: String) {
                trySend(WhisperEvent.LanguageDetected(language))
            }

            override fun onNativeSegment(
                text: String,
                startMs: Long,
                endMs: Long,
                language: String,
                noSpeechProbability: Float,
                words: String,
            ) {
                val parsedWords = words.lineSequence().mapNotNull { line ->
                    val fields = line.split('|')
                    if (fields.size != 4) return@mapNotNull null
                    WhisperWord(
                        text = fields[0],
                        startMs = fields[1].toLongOrNull() ?: return@mapNotNull null,
                        endMs = fields[2].toLongOrNull() ?: return@mapNotNull null,
                        confidence = fields[3].toFloatOrNull() ?: 0f,
                    )
                }.toList()
                trySend(WhisperEvent.Segment(WhisperSegment(
                    text = text,
                    startMs = startMs,
                    endMs = endMs,
                    language = language,
                    words = parsedWords,
                    noSpeechProbability = noSpeechProbability,
                )))
            }

            override fun onSegment(segment: WhisperSegment) {
                trySend(WhisperEvent.Segment(segment))
            }

            override fun onCompleted() {
                trySend(WhisperEvent.Completed)
                close()
            }

            override fun onError(message: String) {
                trySend(WhisperEvent.Error(message))
                close()
            }
        }

        val result = WhisperBridge.transcribeFile(
            handle = handle,
            wavPath = wav.absolutePath,
            language = languageArg,
            translate = false,
            callback = nativeCallback,
        )
        result.onFailure { t ->
            trySend(WhisperEvent.Error(t.message ?: "whisper failed"))
            close(t)
        }

        awaitClose { WhisperBridge.close(handle) }
    }.flowOn(io)

    /**
     * The Asset Pack ships models in `assets/...`; the native code
     * needs a real file path. The simplest portable approach is to
     * copy the bytes into the per-app filesDir on first use and reuse
     * the cached copy thereafter. The cache is invalidated only when
     * the model entity's `checksum_sha256` changes.
     */
    private fun resolveModelPath(model: ModelEntity): String? {
        if (!model.isEmbedded) {
            return model.localPath
        }
        val target = File(context.filesDir, "models/${model.id}.bin")
        if (target.exists() && target.length() == model.sizeBytes) {
            return target.absolutePath
        }
        target.parentFile?.mkdirs()
        val assetPath = inferAssetPath(model)
        context.assets.open(assetPath).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target.absolutePath
    }

    private fun inferAssetPath(model: ModelEntity): String = "transcription/${model.id}.bin"
}

/**
 * Stream of events produced by the transcriber. The consumer is
 * expected to handle [LanguageDetected], [Segment] (zero or more),
 * and exactly one terminal event ([Completed] or [Error]).
 */
sealed interface WhisperEvent {
    data class LanguageDetected(val language: String) : WhisperEvent
    data class Segment(val segment: WhisperSegment) : WhisperEvent
    data object Completed : WhisperEvent
    data class Error(val message: String) : WhisperEvent
}

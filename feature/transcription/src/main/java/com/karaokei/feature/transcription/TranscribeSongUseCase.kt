package com.karaokei.feature.transcription

import com.karaokei.core.common.coroutines.Dispatcher
import com.karaokei.core.common.coroutines.KaraokeDispatcher
import com.karaokei.core.common.result.AppError
import com.karaokei.core.common.result.AppResult
import com.karaokei.core.common.result.runCatchingResult
import com.karaokei.core.data.cache.SongCacheLayout
import com.karaokei.core.data.db.dao.ModelDao
import com.karaokei.core.data.db.dao.ProcessingCacheDao
import com.karaokei.core.data.db.dao.SongDao
import com.karaokei.core.data.db.entity.ModelType
import com.karaokei.core.data.db.entity.ProcessingCacheEntity
import com.karaokei.core.data.db.entity.ProcessingStage
import com.karaokei.core.data.db.entity.SongStatus
import com.karaokei.core.data.preferences.UserPreferences
import com.karaokei.core.common.transcript.TranscriptDocument
import com.karaokei.core.common.transcript.TranscriptSegment
import com.karaokei.core.whisper.WhisperEvent
import com.karaokei.core.whisper.WhisperSegment
import com.karaokei.core.whisper.WhisperTranscriber
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Top-level use case for T4. Runs Whisper over the vocals WAV,
 * accumulates the segments, and writes `transcript.json`. Skipped
 * when the vocals are mostly silent (T4.5) — the document is written
 * empty in that case and the pipeline proceeds to alignment.
 */
@Singleton
class TranscribeSongUseCase @Inject constructor(
    @ApplicationContext private val context: android.content.Context,
    private val songDao: SongDao,
    private val modelDao: ModelDao,
    private val cacheDao: ProcessingCacheDao,
    private val cacheLayout: SongCacheLayout,
    private val transcriber: WhisperTranscriber,
    private val preferences: UserPreferences,
    @Dispatcher(KaraokeDispatcher.IO) private val io: CoroutineDispatcher,
) {

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
    }

    suspend operator fun invoke(songId: String): AppResult<TranscriptDocument> = runCatchingResult {
        val song = songDao.findById(songId)
            ?: throw IllegalStateException("song $songId not found")
        val tier = preferences.selectedTier.first()
        val model = modelDao.findByTierAndType(tier, ModelType.TRANSCRIPTION)
            ?: throw IllegalStateException("no transcription model for tier $tier")

        songDao.updateStatus(songId, SongStatus.TRANSCRIBING)
        val vocals = cacheLayout.vocalsFile(songId)
        if (!vocals.exists()) throw IllegalStateException("vocals.wav missing for $songId")

        // T4.5: skip if vocals are mostly silent.
        if (SilenceDetector.isMostlySilent(vocals)) {
            val empty = TranscriptDocument(
                songId = songId,
                language = "unknown",
                duration = song.durationMs / 1000.0,
                modelId = model.id,
                segments = emptyList(),
            )
            writeTranscript(empty, cacheLayout.transcriptFile(songId))
            cacheDao.upsert(ProcessingCacheEntity(
                songId = songId,
                stage = ProcessingStage.TRANSCRIPTION,
                completedAt = System.currentTimeMillis(),
                outputPath = cacheLayout.transcriptFile(songId).absolutePath,
            ))
            songDao.updateStatus(songId, SongStatus.ALIGNING)
            return@runCatchingResult empty
        }

        val segments = mutableListOf<TranscriptSegment>()
        var detectedLanguage: String = "unknown"
        val preferredLanguage = preferences.preferredLanguage.first()
        transcriber.transcribe(vocals, model, preferredLanguage).collect { event ->
            when (event) {
                is WhisperEvent.LanguageDetected -> detectedLanguage = event.language
                is WhisperEvent.Segment -> segments += event.segment.toTranscriptSegment()
                is WhisperEvent.Completed -> Unit
                is WhisperEvent.Error -> throw IllegalStateException(event.message)
            }
        }

        val doc = TranscriptDocument(
            songId = songId,
            language = detectedLanguage,
            duration = song.durationMs / 1000.0,
            modelId = model.id,
            segments = segments,
        )
        writeTranscript(doc, cacheLayout.transcriptFile(songId))
        cacheDao.upsert(ProcessingCacheEntity(
            songId = songId,
            stage = ProcessingStage.TRANSCRIPTION,
            completedAt = System.currentTimeMillis(),
            outputPath = cacheLayout.transcriptFile(songId).absolutePath,
        ))
        songDao.updateStatus(songId, SongStatus.ALIGNING)
        doc
    }.let { result ->
        when (result) {
            is AppResult.Success -> result
            is AppResult.Failure -> {
                songDao.updateStatus(songId, SongStatus.ERROR)
                AppResult.Failure(
                    AppError.Inference(result.error.message, result.error.cause)
                )
            }
        }
    }

    private fun writeTranscript(doc: TranscriptDocument, file: java.io.File) {
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(doc))
    }
}

private fun WhisperSegment.toTranscriptSegment(): TranscriptSegment =
    TranscriptSegment(
        start = startMs / 1000.0,
        end = endMs / 1000.0,
        text = text,
        confidence = 1f,
        noSpeechProb = noSpeechProbability,
        words = words.map {
            com.karaokei.core.common.transcript.TranscriptWord(
                text = it.text,
                start = it.startMs / 1000.0,
                end = it.endMs / 1000.0,
                confidence = it.confidence,
            )
        },
    )

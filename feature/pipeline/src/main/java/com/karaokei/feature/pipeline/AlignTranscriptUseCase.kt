package com.karaokei.feature.pipeline

import com.karaokei.core.common.result.AppError
import com.karaokei.core.common.result.AppResult
import com.karaokei.core.common.result.runCatchingResult
import com.karaokei.core.data.cache.SongCacheLayout
import com.karaokei.core.data.db.dao.ProcessingCacheDao
import com.karaokei.core.data.db.dao.SongDao
import com.karaokei.core.data.db.entity.ProcessingCacheEntity
import com.karaokei.core.data.db.entity.ProcessingStage
import com.karaokei.core.data.db.entity.SongStatus
import com.karaokei.core.common.transcript.TranscriptDocument
import com.karaokei.feature.karaoke.engine.KaraokeDocument
import com.karaokei.feature.karaoke.engine.KaraokeLineBuilder
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Top-level use case for T5.1. Reads `transcript.json` from the
 * cache, runs [KaraokeLineBuilder], writes `karaoke.json` and
 * updates the `processing_cache` table.
 */
@Singleton
class AlignTranscriptUseCase @Inject constructor(
    private val songDao: SongDao,
    private val cacheDao: ProcessingCacheDao,
    private val cacheLayout: SongCacheLayout,
) {

    private val json = Json {
        prettyPrint = false
        ignoreUnknownKeys = true
    }

    suspend operator fun invoke(songId: String): AppResult<KaraokeDocument> = runCatchingResult {
        val song = songDao.findById(songId)
            ?: throw IllegalStateException("song $songId not found")
        songDao.updateStatus(songId, SongStatus.ALIGNING)

        val transcriptFile = cacheLayout.transcriptFile(songId)
        require(transcriptFile.exists()) { "transcript.json missing for $songId" }
        val transcript: TranscriptDocument = json.decodeFromString(
            TranscriptDocument.serializer(),
            transcriptFile.readText(),
        )
        val karaoke = KaraokeLineBuilder.build(transcript)
        val outputFile = cacheLayout.karaokeFile(songId)
        outputFile.parentFile?.mkdirs()
        outputFile.writeText(json.encodeToString(karaoke))

        cacheDao.upsert(ProcessingCacheEntity(
            songId = songId,
            stage = ProcessingStage.ALIGNMENT,
            completedAt = System.currentTimeMillis(),
            outputPath = outputFile.absolutePath,
        ))
        songDao.updateStatus(songId, SongStatus.READY)
        karaoke
    }.let { result ->
        when (result) {
            is AppResult.Success -> result
            is AppResult.Failure -> {
                songDao.updateStatus(songId, SongStatus.ERROR)
                AppResult.Failure(
                    AppError.Unknown(result.error.message, result.error.cause)
                )
            }
        }
    }
}

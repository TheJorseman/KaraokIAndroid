package com.karaokei.feature.separation

import android.content.Context
import android.net.Uri
import com.karaokei.core.common.coroutines.Dispatcher
import com.karaokei.core.common.coroutines.KaraokeDispatcher
import com.karaokei.core.common.result.AppError
import com.karaokei.core.common.result.AppResult
import com.karaokei.core.common.result.getOrThrow
import com.karaokei.core.common.result.runCatchingResult
import com.karaokei.core.data.cache.SongCacheLayout
import com.karaokei.core.data.db.dao.ModelDao
import com.karaokei.core.data.db.dao.ProcessingCacheDao
import com.karaokei.core.data.db.dao.SongDao
import com.karaokei.core.data.db.entity.ModelTier
import com.karaokei.core.data.db.entity.ModelType
import com.karaokei.core.data.db.entity.ProcessingCacheEntity
import com.karaokei.core.data.db.entity.ProcessingStage
import com.karaokei.core.data.db.entity.SongStatus
import com.karaokei.core.data.preferences.UserPreferences
import com.karaokei.core.media.extraction.AudioExtractor
import com.karaokei.core.media.io.WavWriter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Top-level use case for T3: extract the input audio to PCM, run the
 * selected separation model, write `vocals.wav` and `instrumental.wav`,
 * and mark the cache row so the orchestrator doesn't re-run.
 *
 * Strictly sequential in the sense that this method does not return
 * until the ONNX session has been released (T3.7).
 */
@Singleton
class SeparateSongUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val songDao: SongDao,
    private val modelDao: ModelDao,
    private val cacheDao: ProcessingCacheDao,
    private val cacheLayout: SongCacheLayout,
    private val extractor: AudioExtractor,
    private val preferences: UserPreferences,
    @Dispatcher(KaraokeDispatcher.IO) private val io: CoroutineDispatcher,
) {

    suspend operator fun invoke(songId: String): AppResult<SeparationResult> = runCatchingResult {
        val song = songDao.findById(songId)
            ?: throw IllegalStateException("song $songId not found")
        val tier = preferences.selectedTier.first()
        // The catalog is currently single-source: every tier shares the
        // same RoFormer weights. Fall back to *any* available
        // separation model rather than the empty tier-specific row.
        val model = modelDao.findByTierAndType(tier, ModelType.SEPARATION)
            ?: modelDao.findByType(ModelType.SEPARATION).firstOrNull()
            ?: run {
                songDao.updateStatus(songId, SongStatus.ERROR)
                throw IllegalStateException(
                    "no separation model for tier $tier. " +
                        "Abre la pantalla Modelos y descarga uno antes de procesar."
                )
            }

        songDao.updateStatus(songId, SongStatus.SEPARATING)

        val pcmWav = cacheLayout.dirFor(songId).resolve("source_16k_mono.wav")
        val sourceUri = Uri.parse(song.fileUri)
        extractor.extractToWav(input = sourceUri, output = pcmWav).getOrThrow()

        val samples = com.karaokei.core.media.io.WavReader.readPcm16Mono(pcmWav)
        val result = MdxNetSeparator(modelLoader = modelDao.let { dl -> com.karaokei.core.ai.model.ModelLoader(context) }, io = io)
            .separate(samples, model)
            .getOrThrow()

        val vocalsFile = cacheLayout.vocalsFile(songId)
        val instrFile = cacheLayout.instrumentalFile(songId)
        WavWriter.writePcm16Mono(vocalsFile, result.vocals)
        WavWriter.writePcm16Mono(instrFile, result.instrumental)

        cacheDao.upsert(
            ProcessingCacheEntity(
                songId = songId,
                stage = ProcessingStage.SEPARATION,
                completedAt = System.currentTimeMillis(),
                outputPath = vocalsFile.absolutePath,
            ),
        )
        songDao.updateStatus(songId, SongStatus.TRANSCRIBING)
        result
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
}

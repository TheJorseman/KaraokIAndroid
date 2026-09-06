package com.karaokei.feature.separation

import android.content.Context
import android.net.Uri
import android.util.Log
import com.karaokei.core.common.audio.PcmFormat
import com.karaokei.core.common.coroutines.Dispatcher
import com.karaokei.core.common.coroutines.KaraokeDispatcher
import com.karaokei.core.common.result.AppError
import com.karaokei.core.common.result.AppResult
import com.karaokei.core.common.result.getOrNull
import com.karaokei.core.common.result.getOrThrow
import com.karaokei.core.common.result.runCatchingResult
import com.karaokei.core.data.cache.SongCacheLayout
import com.karaokei.core.data.db.dao.ModelDao
import com.karaokei.core.data.db.dao.ProcessingCacheDao
import com.karaokei.core.data.db.dao.SongDao
import com.karaokei.core.data.db.entity.ModelEntity
import com.karaokei.core.data.db.entity.ModelType
import com.karaokei.core.data.db.entity.ProcessingCacheEntity
import com.karaokei.core.data.db.entity.ProcessingStage
import com.karaokei.core.data.db.entity.SongStatus
import com.karaokei.core.data.preferences.UserPreferences
import com.karaokei.core.media.extraction.AudioExtractor
import com.karaokei.core.media.io.WavWriter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Top-level use case for T3: extract the input audio to PCM, run the
 * selected separation model, write `vocals.wav` and `instrumental.wav`,
 * and mark the cache row so the orchestrator doesn't re-run.
 *
 * Dispatch by `tierClass`:
 * - `mdx_net_*`     → [MdxNetSeparator]
 * - `htdemucs_*`    → [HtDemucsSeparator]
 * - `mel_band_roformer_*` → [RoformerSeparator]
 *
 * The orchestrator polls [progress] to surface per-window progress in
 * the foreground-service notification (T7.2).
 */
@Singleton
class SeparateSongUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val songDao: SongDao,
    private val modelDao: ModelDao,
    private val cacheDao: ProcessingCacheDao,
    private val cacheLayout: SongCacheLayout,
    private val extractor: AudioExtractor,
    private val mdxNet: MdxNetSeparator,
    private val htDemucs: HtDemucsSeparator,
    private val roformer: RoformerSeparator,
    private val preferences: UserPreferences,
    @Dispatcher(KaraokeDispatcher.IO) private val io: CoroutineDispatcher,
) {

    private val _progress = MutableStateFlow(0f)
    val progress: StateFlow<Float> = _progress.asStateFlow()

    suspend operator fun invoke(songId: String): AppResult<SeparationResult> = runCatchingResult {
        val song = songDao.findById(songId)
            ?: throw IllegalStateException("song $songId not found")
        val testFixture = song.fileUri.endsWith("karaokei-test-audio.wav")

        songDao.updateStatus(songId, SongStatus.SEPARATING)
        _progress.value = 0f

        val pcmWav = cacheLayout.dirFor(songId).resolve("source_16k_mono.wav")
        val sourceUri = Uri.parse(song.fileUri)
        extractor.extractToWav(input = sourceUri, output = pcmWav).getOrThrow()

        val samples = com.karaokei.core.media.io.WavReader.readPcm16Mono(pcmWav)

        // The test fixture song (`karaokei-test-audio.wav`) is a
        // synthetic two-tone file seeded by `DefaultTestAudioSeeder`.
        // It is intentionally bypassed from any real model load so the
        // pipeline can be exercised end-to-end without downloading the
        // ~800 MB of weights; the vocals are simply a copy of the
        // source, the instrumental is silence.
        if (testFixture) {
            val fixtureResult = testFixtureSeparation(samples)
            writeAndCache(songId, fixtureResult)
            songDao.updateStatus(songId, SongStatus.TRANSCRIBING)
            _progress.value = 1f
            return@runCatchingResult fixtureResult
        }

        val tier = preferences.selectedTier.first()
        val model = modelDao.findByTierAndType(tier, ModelType.SEPARATION) ?: run {
            val downloaded = modelDao.findDownloadedByType(ModelType.SEPARATION).firstOrNull()
            if (downloaded != null) {
                Log.w(TAG, "Tier $tier has no local separation model; using downloaded ${downloaded.id}")
                downloaded
            } else {
                songDao.updateStatus(songId, SongStatus.ERROR)
                throw IllegalStateException(
                    "no separation model available. Abre Modelos y descarga el tier ${tier.name} antes de procesar."
                )
            }
        }

        val result = when (separatorFor(model)) {
            Separator.MDX_NET -> mdxNetSeparation(samples, model)
            Separator.HTDEMUCS -> htDemucsSeparation(samples, model)
            Separator.ROFORMER -> roformerSeparation(samples, model)
            Separator.UNKNOWN -> {
                Log.w(TAG, "Unknown separator for ${model.id} (tierClass=${model.tierClass}); falling back to MDX-Net")
                mdxNetSeparation(samples, model)
            }
        }

        writeAndCache(songId, result)
        songDao.updateStatus(songId, SongStatus.TRANSCRIBING)
        _progress.value = 1f
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

    private enum class Separator { MDX_NET, HTDEMUCS, ROFORMER, UNKNOWN }

    private fun separatorFor(model: ModelEntity): Separator {
        val tierClass = model.tierClass.orEmpty().lowercase()
        val id = model.id.lowercase()
        return when {
            tierClass.startsWith("htdemucs") || id.startsWith("htdemucs") -> Separator.HTDEMUCS
            tierClass.startsWith("mel_band_roformer") ||
                tierClass.startsWith("roformer") ||
                id.contains("roformer") -> Separator.ROFORMER
            tierClass.startsWith("mdx_net") || id.startsWith("mdx") -> Separator.MDX_NET
            else -> Separator.UNKNOWN
        }
    }

    private suspend fun htDemucsSeparation(
        samples: FloatArray,
        model: ModelEntity,
    ): SeparationResult {
        val mono44_1k = HtDemucsAudio.mono16kToMono44_1k(samples)
        val stereo = HtDemucsAudio.monoToStereo(mono44_1k)
        Log.i(TAG, "Running HTDemucs ${model.id} on ${mono44_1k.size} samples @44.1k")
        val htResult = htDemucs.separate(stereo, model) { fraction ->
            _progress.value = fraction * 0.95f
        }.getOrThrow()
        _progress.value = 0.95f
        val vocalsMono44_1k = HtDemucsAudio.stereo44_1kToMono(htResult.vocals)
        val vocalsMono16k = HtDemucsAudio.mono44_1kToMono16k(vocalsMono44_1k)
        val vocalsOut = FloatArray(samples.size)
        System.arraycopy(vocalsMono16k, 0, vocalsOut, 0, minOf(vocalsMono16k.size, samples.size))
        val instrMono16k = FloatArray(samples.size) { i -> samples[i] - vocalsOut[i] }
        return SeparationResult(
            vocals = vocalsOut,
            instrumental = instrMono16k,
            sampleRateHz = PcmFormat.SAMPLE_RATE_HZ,
            durationMs = samples.size.toLong() * 1000L / PcmFormat.SAMPLE_RATE_HZ,
        )
    }

    private suspend fun roformerSeparation(
        samples: FloatArray,
        model: ModelEntity,
    ): SeparationResult {
        // RoFormer expects 44.1 kHz stereo PCM. Resample mono 16 kHz
        // source to 44.1 kHz mono, then broadcast to stereo.
        val mono44_1k = HtDemucsAudio.padOrTruncate(
            HtDemucsAudio.mono16kToMono44_1k(samples),
            RoformerSeparator.N_FFT + RoformerSeparator.HOP * (RoformerSeparator.EXPECTED_FRAMES - 1),
        )
        val stereo = HtDemucsAudio.monoToStereo(mono44_1k)
        Log.i(TAG, "Running RoFormer ${model.id} on ${mono44_1k.size} samples @44.1k")
        val chunkSamples = RoformerSeparator.HOP * (RoformerSeparator.EXPECTED_FRAMES - 1)
        val totalChunks = maxOf(1, (mono44_1k.size - chunkSamples + RoformerSeparator.HOP * RoformerSeparator.STEP_FRAMES - 1) /
            (RoformerSeparator.HOP * RoformerSeparator.STEP_FRAMES))
        val rfResult = roformer.separate(stereo, model) { chunkProgress ->
            _progress.value = (totalChunks - 1 + chunkProgress) / totalChunks * 0.95f
        }.getOrThrow()
        // `rfResult.vocals` is mono 44.1 kHz; down-sample to mono 16 kHz.
        val vocalsMono16k = HtDemucsAudio.mono44_1kToMono16k(rfResult.vocals)
        val vocalsOut = FloatArray(samples.size)
        System.arraycopy(vocalsMono16k, 0, vocalsOut, 0, minOf(vocalsMono16k.size, samples.size))
        val instrMono16k = FloatArray(samples.size) { i -> samples[i] - vocalsOut[i] }
        return SeparationResult(
            vocals = vocalsOut,
            instrumental = instrMono16k,
            sampleRateHz = PcmFormat.SAMPLE_RATE_HZ,
            durationMs = samples.size.toLong() * 1000L / PcmFormat.SAMPLE_RATE_HZ,
        )
    }

    private suspend fun mdxNetSeparation(
        samples: FloatArray,
        model: ModelEntity,
    ): SeparationResult = mdxNet.separate(samples, model, testFixture = false).getOrThrow()

    private fun testFixtureSeparation(samples: FloatArray): SeparationResult = SeparationResult(
        vocals = samples.copyOf(),
        instrumental = FloatArray(samples.size),
        sampleRateHz = PcmFormat.SAMPLE_RATE_HZ,
        durationMs = samples.size.toLong() * 1000L / PcmFormat.SAMPLE_RATE_HZ,
    )

    private suspend fun writeAndCache(songId: String, result: SeparationResult) {
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
    }
}

private const val TAG = "SeparateSongUseCase"

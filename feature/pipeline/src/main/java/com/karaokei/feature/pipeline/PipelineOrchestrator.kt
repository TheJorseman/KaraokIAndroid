package com.karaokei.feature.pipeline

import android.util.Log
import com.karaokei.core.common.result.AppResult
import com.karaokei.core.common.result.getOrThrow
import com.karaokei.core.data.cache.SongCacheLayout
import com.karaokei.core.data.db.dao.ProcessingCacheDao
import com.karaokei.core.data.db.entity.ProcessingCacheEntity
import com.karaokei.core.data.db.entity.ProcessingStage
import com.karaokei.feature.separation.SeparateSongUseCase
import com.karaokei.feature.transcription.TranscribeSongUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Top-level orchestrator for the AI pipeline. Skips stages whose
 * output is already cached.
 *
 * The pipeline is intentionally sequential (separation → transcription
 * → alignment) and **never** loads two models at the same time (T3.7,
 * section 4 of the plan). A single [Mutex] enforces this at the
 * orchestrator level even when the same song is processed by two
 * different callers.
 */
@Singleton
class PipelineOrchestrator @Inject constructor(
    private val separateUseCase: SeparateSongUseCase,
    private val transcribeUseCase: TranscribeSongUseCase,
    private val alignUseCase: AlignTranscriptUseCase,
    private val cacheLayout: SongCacheLayout,
    private val cacheDao: ProcessingCacheDao,
) {

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: kotlinx.coroutines.Job? = null
    private val _state = MutableStateFlow(PipelineState())
    val state: StateFlow<PipelineState> = _state.asStateFlow()

    /**
     * Runs the pipeline for [songId]. If `karaoke.json` is already
     * on disk, returns immediately. Otherwise, runs whichever stages
     * are missing in order.
     */
    suspend fun run(songId: String): AppResult<Unit> = mutex.withLock {
        _state.update { it.copy(songId = songId, stage = PipelineStageName.SEPARATION, progress = 0, error = null) }
        try {
            if (cacheLayout.hasKaraoke(songId)) {
                _state.update { it.copy(stage = PipelineStageName.DONE, progress = 100) }
                return@withLock AppResult.Success(Unit)
            }
            if (!cacheLayout.hasSeparation(songId)) {
                _state.update { it.copy(stage = PipelineStageName.SEPARATION, progress = 10) }
                separateUseCase(songId).getOrThrow()
            }
            if (!cacheLayout.hasTranscript(songId)) {
                _state.update { it.copy(stage = PipelineStageName.TRANSCRIBING, progress = 55) }
                transcribeUseCase(songId).getOrThrow()
            }
            _state.update { it.copy(stage = PipelineStageName.ALIGNING, progress = 85) }
            alignUseCase(songId).getOrThrow()
            _state.update { it.copy(stage = PipelineStageName.DONE, progress = 100) }
            AppResult.Success(Unit)
        } catch (t: CancellationException) {
            _state.update { it.copy(stage = PipelineStageName.CANCELLED, progress = 100, error = null) }
            throw t
        } catch (t: Throwable) {
            Log.e(TAG, "Pipeline failed for song=$songId stage=${_state.value.stage}", t)
            _state.update { it.copy(stage = PipelineStageName.ERROR, progress = 100, error = t.message) }
            AppResult.Failure(
                com.karaokei.core.common.result.AppError.Unknown(
                    t.message ?: t::class.java.simpleName,
                    t,
                )
            )
        }
    }

    fun runAsync(songId: String) {
        activeJob?.cancel()
        activeJob = scope.launch {
            try {
                run(songId)
            } catch (_: CancellationException) {
                // Cancellation is signalled through [state], not as a failure.
            }
        }
    }

    fun cancel(songId: String) {
        if (_state.value.songId == songId) {
            activeJob?.cancel()
            activeJob = null
        }
    }

    /** Invalidate the cache for [songId] (T7.4). */
    suspend fun invalidate(songId: String) {
        cacheLayout.deleteAll(songId)
        cacheDao.deleteForSong(songId)
        _state.update { PipelineState() }
    }
}

data class PipelineState(
    val songId: String? = null,
    val stage: PipelineStageName = PipelineStageName.IDLE,
    val progress: Int = 0,
    val error: String? = null,
)

enum class PipelineStageName { IDLE, SEPARATION, TRANSCRIBING, ALIGNING, DONE, ERROR, CANCELLED }

private const val TAG = "KaraokePipeline"

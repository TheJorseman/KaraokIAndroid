package com.karaokei.feature.pipeline

import android.util.Log
import com.karaokei.core.ai.ort.OrtSessionFactory
import com.karaokei.core.common.result.AppResult
import com.karaokei.core.common.result.getOrThrow
import com.karaokei.core.data.cache.SongCacheLayout
import com.karaokei.core.data.db.dao.ProcessingCacheDao
import com.karaokei.core.data.db.dao.SongDao
import com.karaokei.core.data.db.entity.ProcessingCacheEntity
import com.karaokei.core.data.db.entity.ProcessingStage
import com.karaokei.feature.separation.SeparateSongUseCase
import com.karaokei.feature.transcription.TranscribeSongUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
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
    private val songDao: com.karaokei.core.data.db.dao.SongDao,
) {

    private val mutex = Mutex()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null
    private val _state = MutableStateFlow(PipelineState())
    val state: StateFlow<PipelineState> = _state.asStateFlow()

    // ---- batch queue -------------------------------------------------
    // Songs enqueued with [enqueue] are processed one after another by a
    // single consumer, so "Procesar todo" works without cancelling the
    // run in flight (runAsync keeps its cancel-and-replace semantics).
    private val queue = kotlinx.coroutines.channels.Channel<String>(
        kotlinx.coroutines.channels.Channel.UNLIMITED,
    )
    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    init {
        scope.launch {
            for (songId in queue) {
                _pendingCount.update { (it - 1).coerceAtLeast(0) }
                run(songId, force = false)
            }
        }
    }

    /** Queue a song for processing after the current run finishes. */
    fun enqueue(songId: String) {
        _pendingCount.update { it + 1 }
        queue.trySend(songId)
    }

    /** Drop everything still waiting in the batch queue. */
    fun clearQueue() {
        while (queue.tryReceive().isSuccess) {
            // drain
        }
        _pendingCount.value = 0
    }

    /**
     * Runs the pipeline for [songId]. If `karaoke.json` is already
     * on disk, returns immediately. Otherwise, runs whichever stages
     * are missing in order. With [force] the cache is invalidated
     * first so "Procesar / reprocesar" redoes every stage even when a
     * (possibly stale) `karaoke.json` exists — otherwise the tap
     * silently does nothing.
     */
    suspend fun run(songId: String, force: Boolean = false): AppResult<Unit> = mutex.withLock {
        val testFixture = songDao.findById(songId)?.fileUri?.endsWith("karaokei-test-audio.wav") == true
        _state.update {
            it.copy(
                songId = songId,
                stage = PipelineStageName.SEPARATION,
                progress = 0,
                error = null,
                testFixture = testFixture,
            )
        }
        try {
            if (force) {
                cacheLayout.deleteAll(songId)
                cacheDao.deleteForSong(songId)
            }
            if (cacheLayout.hasKaraoke(songId)) {
                _state.update { it.copy(stage = PipelineStageName.DONE, progress = 100) }
                return@withLock AppResult.Success(Unit)
            }
            if (!cacheLayout.hasSeparation(songId)) {
                _state.update { it.copy(stage = PipelineStageName.SEPARATION, progress = 5) }
                // Mirror per-window separation progress into the pipeline
                // state so the foreground service notification has a
                // real per-window progress bar instead of a single
                // "Separando…" sentinel.
                var progressJob: Job? = null
                val separationStart = System.nanoTime()
                try {
                    progressJob = scope.launch {
                        separateUseCase.progress.collectLatest { fraction ->
                            _state.update {
                                val scaled = 5 + (fraction * 45).toInt().coerceIn(0, 45)
                                it.copy(progress = scaled)
                            }
                        }
                    }
                    separateUseCase(songId).getOrThrow()
                } finally {
                    progressJob?.cancel()
                }
                val separationMs = (System.nanoTime() - separationStart) / 1_000_000
                Log.i(
                    TAG,
                    "Separation finished in ${separationMs} ms (backend=${OrtSessionFactory.activeBackend})",
                )
                _state.update { it.copy(stage = PipelineStageName.SEPARATION, progress = 50) }
            }
            if (!cacheLayout.hasTranscript(songId)) {
                _state.update { it.copy(stage = PipelineStageName.TRANSCRIBING, progress = 55) }
                val transcriptionStart = System.nanoTime()
                // Mirror the whisper segment progress into the state so
                // the bar keeps moving during the (potentially long)
                // transcription instead of freezing at 55%.
                var progressJob: Job? = null
                try {
                    progressJob = scope.launch {
                        transcribeUseCase.progress.collectLatest { fraction ->
                            _state.update {
                                val scaled = 55 + (fraction * 30).toInt().coerceIn(0, 30)
                                it.copy(progress = scaled)
                            }
                        }
                    }
                    transcribeUseCase(songId).getOrThrow()
                } finally {
                    progressJob?.cancel()
                }
                val transcriptionMs = (System.nanoTime() - transcriptionStart) / 1_000_000
                Log.i(
                    TAG,
                    "Transcription finished in ${transcriptionMs} ms (backend=${OrtSessionFactory.activeBackend})",
                )
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

    fun runAsync(songId: String, force: Boolean = false) {
        activeJob?.cancel()
        activeJob = scope.launch {
            try {
                run(songId, force)
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

    /**
     * Partial invalidation: drop the outputs of [from] and later
     * stages so the next run redoes only those while reusing the
     * earlier ones (e.g. `TRANSCRIPTION` re-runs lyrics + alignment
     * while keeping the separated vocals).
     */
    suspend fun invalidateFrom(
        songId: String,
        from: com.karaokei.core.data.db.entity.ProcessingStage,
    ) {
        cacheLayout.deleteFrom(songId, from)
        cacheDao.deleteForSong(songId)
        songDao.updateStatus(songId, com.karaokei.core.data.db.entity.SongStatus.IMPORTED)
    }
}

data class PipelineState(
    val songId: String? = null,
    val stage: PipelineStageName = PipelineStageName.IDLE,
    val progress: Int = 0,
    val error: String? = null,
    val testFixture: Boolean = false,
)

enum class PipelineStageName { IDLE, SEPARATION, TRANSCRIBING, ALIGNING, DONE, ERROR, CANCELLED }

private const val TAG = "KaraokePipeline"

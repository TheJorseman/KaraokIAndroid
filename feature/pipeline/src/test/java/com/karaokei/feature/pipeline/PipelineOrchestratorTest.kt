package com.karaokei.feature.pipeline

import com.google.common.truth.Truth.assertThat
import com.karaokei.core.common.result.AppResult
import com.karaokei.core.data.cache.SongCacheLayout
import com.karaokei.core.data.db.dao.ProcessingCacheDao
import com.karaokei.core.data.db.dao.SongDao
import com.karaokei.core.data.db.entity.SongEntity
import com.karaokei.core.data.db.entity.SongStatus
import com.karaokei.feature.separation.SeparateSongUseCase
import com.karaokei.feature.transcription.TranscribeSongUseCase
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * The pipeline must never execute stages of two runs at the same
 * time: only one separation/transcription model may be resident in
 * memory. The orchestrator's mutex is the guarantee — these tests
 * pin it down against regressions (and against runAsync's
 * cancel-and-replace racing a still-cleaning-up previous run).
 */
class PipelineOrchestratorTest {

    private class Harness {
        val active = AtomicInteger(0)
        val maxActive = AtomicInteger(0)
        val finished = ConcurrentHashMap.newKeySet<String>()
        val order = ConcurrentHashMap<String, Boolean>()

        val separate = mockk<SeparateSongUseCase>(relaxed = true)
        val transcribe = mockk<TranscribeSongUseCase>(relaxed = true)
        val align = mockk<AlignTranscriptUseCase>(relaxed = true)
        val cacheLayout = mockk<SongCacheLayout>(relaxed = true)
        val cacheDao = mockk<ProcessingCacheDao>(relaxed = true)
        val songDao = mockk<SongDao>(relaxed = true)

        val orchestrator = PipelineOrchestrator(
            separate, transcribe, align, cacheLayout, cacheDao, songDao,
        )

        init {
            every { separate.progress } returns MutableStateFlow(0f)
            every { transcribe.progress } returns MutableStateFlow(0f)
            coEvery { songDao.findById(any()) } answers {
                SongEntity(
                    id = firstArg(),
                    title = firstArg(),
                    artist = null,
                    durationMs = 1_000L,
                    fileUri = "/tmp/song.mp3",
                    coverUri = null,
                    status = SongStatus.IMPORTED,
                    createdAt = 0L,
                )
            }
            every { cacheLayout.hasKaraoke(any()) } returns false
            every { cacheLayout.hasSeparation(any()) } returns false
            every { cacheLayout.hasTranscript(any()) } returns false

            fun enterStage() {
                val now = active.incrementAndGet()
                maxActive.updateAndGet { maxOf(it, now) }
                Thread.sleep(60) // hold "the model" long enough to overlap if serialization breaks
                active.decrementAndGet()
            }
            coEvery { separate(any()) } coAnswers {
                enterStage()
                AppResult.Success(mockk<com.karaokei.feature.separation.SeparationResult>())
            }
            coEvery { transcribe(any()) } coAnswers {
                enterStage()
                AppResult.Success(
                    com.karaokei.core.common.transcript.TranscriptDocument(
                        songId = firstArg(),
                        language = "es",
                        duration = 1.0,
                        modelId = "test",
                        segments = emptyList(),
                    ),
                )
            }
            coEvery { align(any()) } coAnswers {
                enterStage()
                finished.add(firstArg())
                AppResult.Success(
                    com.karaokei.feature.karaoke.engine.KaraokeDocument(
                        songId = firstArg(),
                        language = "es",
                        duration = 1.0,
                        lines = emptyList(),
                    ),
                )
            }
        }
    }

    @Test
    fun `two concurrent runs never execute stages at the same time`() = runBlocking {
        val harness = Harness()
        val a = launch(Dispatchers.Default) { harness.orchestrator.run("song-a") }
        val b = launch(Dispatchers.Default) { harness.orchestrator.run("song-b") }
        withTimeout(15_000) {
            a.join()
            b.join()
        }
        assertThat(harness.finished).containsAtLeast("song-a", "song-b")
        assertThat(harness.maxActive.get()).isEqualTo(1)
    }

    @Test
    fun `runAsync replaces the active run without overlapping stages`() = runBlocking {
        val harness = Harness()
        harness.orchestrator.runAsync("song-a")
        harness.orchestrator.runAsync("song-b")
        withTimeout(15_000) {
            while (!harness.finished.contains("song-b")) {
                kotlinx.coroutines.delay(10)
            }
        }
        // The replaced run must never have overlapped the new one.
        assertThat(harness.maxActive.get()).isEqualTo(1)
    }

    @Test
    fun `batch queue processes songs sequentially`() = runBlocking {
        val harness = Harness()
        harness.orchestrator.enqueue("song-a")
        harness.orchestrator.enqueue("song-b")
        harness.orchestrator.enqueue("song-c")
        withTimeout(30_000) {
            while (harness.finished.size < 3) {
                kotlinx.coroutines.delay(10)
            }
        }
        assertThat(harness.finished).containsAtLeast("song-a", "song-b", "song-c")
        assertThat(harness.maxActive.get()).isEqualTo(1)
    }
}

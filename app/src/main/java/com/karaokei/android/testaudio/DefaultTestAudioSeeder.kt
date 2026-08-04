package com.karaokei.android.testaudio

import android.content.Context
import android.net.Uri
import com.karaokei.core.data.repository.SongRepository
import com.karaokei.core.media.io.WavWriter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import com.karaokei.core.common.coroutines.Dispatcher
import com.karaokei.core.common.coroutines.KaraokeDispatcher
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates one small, deterministic local WAV so a fresh installation
 * can exercise import, playback, cache and pipeline UI without a user
 * supplying a file. It contains two gentle tones and silence gaps;
 * it is a performance fixture, not a musical recording.
 */
@Singleton
class DefaultTestAudioSeeder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val songRepository: SongRepository,
    @Dispatcher(KaraokeDispatcher.IO) private val io: CoroutineDispatcher,
) {
    suspend fun seed() = withContext(io) {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) {
            val samples = FloatArray(SAMPLE_RATE * DURATION_SECONDS) { index ->
                val second = index / SAMPLE_RATE
                if (second in 2..4 || second in 7..9) {
                    val t = index.toDouble() / SAMPLE_RATE
                    (0.18 * sin(2.0 * PI * 220.0 * t) +
                        0.12 * sin(2.0 * PI * 440.0 * t)).toFloat()
                } else {
                    0f
                }
            }
            WavWriter.writePcm16Mono(file, samples, SAMPLE_RATE)
        }
        // The SHA-256 song id makes this idempotent; upsert replaces
        // the same row on later launches without duplicating it.
        songRepository.import(Uri.fromFile(file))
    }

    companion object {
        private const val FILE_NAME = "karaokei-test-audio.wav"
        private const val SAMPLE_RATE = 16_000
        private const val DURATION_SECONDS = 12
    }
}

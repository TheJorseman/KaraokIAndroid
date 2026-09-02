package com.karaokei.android.testaudio

import android.content.Context
import android.net.Uri
import android.util.Log
import com.karaokei.android.pipeline.PipelineForegroundService
import com.karaokei.core.common.result.getOrNull
import com.karaokei.core.data.db.entity.SongStatus
import com.karaokei.core.data.preferences.UserPreferences
import com.karaokei.core.data.repository.SongRepository
import com.karaokei.core.media.io.WavWriter
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import com.karaokei.core.common.coroutines.Dispatcher
import com.karaokei.core.common.coroutines.KaraokeDispatcher
import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Seeds the user's library with a default song so a fresh install
 * can exercise the karaoke pipeline end-to-end without manually
 * picking a file.
 *
 * Strategy (first existing wins):
 *
 * 1. **`filesDir/te_juro_que_te_amo.mp3`** — pushed via `adb shell
 *    run-as` or downloaded. Overrides everything else; lets
 *    developers drop a fresh take of the song into the device.
 *
 * 2. **`assets/songs/te_juro_que_te_amo.mp3`** — bundled in the
 *    APK so the library is populated with the real song on a clean
 *    install, with no download or adb push required.
 *
 * 3. **Synthetic two-tone WAV** — generated in code as a last
 *    resort (CI sandboxes that strip app assets, instrumented
 *    tests that bypass the asset path, etc.).
 *
 * The song_id is the SHA-256 of the file contents, so the same MP3
 * always produces the same row in `songs` regardless of the source
 * path. `SeparateSongUseCase` and `PipelineForegroundService` only
 * treat `karaokei-test-audio.wav` (the synthetic fallback) as the
 * fixture song; the real `Te Juro Que Te Amo` flows through the
 * full separation + transcription pipeline.
 *
 * [seed] additionally kicks off the pipeline automatically when the
 * default song is freshly imported. The auto-start is gated on
 * `UserPreferences.pipelineAutoStart` (default: true) and skipped if
 * the song is already `READY` from a previous run.
 */
@Singleton
class DefaultTestAudioSeeder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val songRepository: SongRepository,
    private val userPreferences: UserPreferences,
    @Dispatcher(KaraokeDispatcher.IO) private val io: CoroutineDispatcher,
) {
    suspend fun seed() {
        val song = withContext(io) {
            val primary = resolvePrimarySong()
            Log.i(TAG, "Importing default song from ${primary.absolutePath}")
            songRepository.import(Uri.fromFile(primary)).getOrNull()?.entity
        }
        if (song == null) {
            Log.w(TAG, "Default song import returned no entity; skipping auto-start")
            return
        }
        maybeAutoStart(song.id, song.status)
    }

    /**
     * Auto-start the pipeline for a freshly imported song if the
     * user has auto-start enabled and the song is not yet processed.
     */
    private suspend fun maybeAutoStart(songId: String, status: SongStatus) {
        if (status == SongStatus.READY) {
            Log.i(TAG, "Song $songId already READY; skipping auto-start")
            return
        }
        val autoStart = userPreferences.pipelineAutoStart.first()
        if (!autoStart) {
            Log.i(TAG, "pipelineAutoStart disabled; leaving song in $status")
            return
        }
        Log.i(TAG, "Auto-starting pipeline for $songId (status=$status)")
        PipelineForegroundService.start(context, songId)
    }

    private fun resolvePrimarySong(): File {
        // 1. Files-dir override (dev push). Use a distinct filename
        //    ("...override.mp3") so the bundled extract below can't
        //    shadow a developer-pushed file with the canonical name.
        val filesDirOverride = File(context.filesDir, TE_JURO_OVERRIDE_FILE_NAME)
        if (filesDirOverride.exists()) {
            Log.i(TAG, "Using filesDir override at ${filesDirOverride.absolutePath}")
            return filesDirOverride
        }
        val filesDirTeJuro = File(context.filesDir, TE_JURO_FILE_NAME)
        if (filesDirTeJuro.exists() && filesDirTeJuro.length() >= MIN_TE_JURO_SIZE) {
            Log.i(TAG, "Using seeded Te Juro Que Te Amo at ${filesDirTeJuro.absolutePath}")
            return filesDirTeJuro
        }
        // 2. Bundled APK asset (production default)
        val extractedTeJuro = File(context.filesDir, EXTRACTED_TE_JURO_FILE_NAME)
        if (!extractedTeJuro.exists() || extractedTeJuro.length() < MIN_TE_JURO_SIZE) {
            extractedTeJuro.parentFile?.mkdirs()
            context.assets.open(BUNDLED_TE_JURO_ASSET).use { input ->
                extractedTeJuro.outputStream().use { output -> input.copyTo(output) }
            }
            Log.i(TAG, "Extracted bundled Te Juro Que Te Amo from $BUNDLED_TE_JURO_ASSET to ${extractedTeJuro.absolutePath}")
        }
        return extractedTeJuro
    }

    /**
     * Legacy path kept for CI / instrumented test sandboxes that
     * do not have access to the bundled asset (e.g. when the app
     * is run from the staging APK without the assets folder). Only
     * used when both the files-dir override and the bundled asset
     * extraction fail.
     */
    suspend fun seedSyntheticFallback() = withContext(io) {
        val synthetic = File(context.filesDir, SYNTHETIC_FILE_NAME)
        if (!synthetic.exists()) {
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
            WavWriter.writePcm16Mono(synthetic, samples, SAMPLE_RATE)
        }
        songRepository.import(Uri.fromFile(synthetic))
    }

    companion object {
        private const val TAG = "DefaultTestAudioSeeder"

        /** Real song pushed via `adb push` / `run-as cp ... files/`. */
        const val TE_JURO_FILE_NAME = "te_juro_que_te_amo.mp3"

        /**
         * Optional dev override: copy the song here to bypass the
         * bundled extract without overwriting it. If both this file
         * and [TE_JURO_FILE_NAME] exist, the override wins.
         */
        const val TE_JURO_OVERRIDE_FILE_NAME = "te_juro_que_te_amo.override.mp3"

        /** Bundled APK asset path (read-only). */
        private const val BUNDLED_TE_JURO_ASSET = "songs/te_juro_que_te_amo.mp3"

        /** Extracted copy under filesDir/ so Uri.fromFile can read it. */
        private const val EXTRACTED_TE_JURO_FILE_NAME = "te_juro_que_te_amo.mp3"

        /** Synthetic fallback generated on first launch. */
        const val SYNTHETIC_FILE_NAME = "karaokei-test-audio.wav"

        /** Floor on the bundled MP3 size so a 0-byte extract is treated
         *  as missing and the fallback kicks in. */
        private const val MIN_TE_JURO_SIZE: Long = 1L * 1024 * 1024 // 1 MB

        private const val SAMPLE_RATE = 16_000
        private const val DURATION_SECONDS = 12
    }
}

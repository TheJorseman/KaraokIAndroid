package com.karaokei.feature.modelmanager.lyrics

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.karaokei.core.common.result.AppResult
import com.karaokei.core.data.cache.SongCacheLayout
import com.karaokei.core.data.db.dao.SongDao
import com.karaokei.core.data.preferences.UserPreferences
import com.karaokei.feature.karaoke.engine.KaraokeDocument
import com.karaokei.feature.karaoke.engine.LrcParser
import com.karaokei.feature.karaoke.engine.LyricsAligner
import com.karaokei.feature.karaoke.engine.LyricsCalibrator
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fetches real lyrics for a processed song and rewrites `karaoke.json`
 * so the karaoke shows the *veridical* text synced onto the locally
 * generated timestamps (the highlight still follows the user's own
 * recording).
 *
 * - Opt-in via `UserPreferences.lyricsOnlineSync`; only title/artist/
 *   duration are sent over the network, never audio.
 * - Idempotent: a `lyrics_sync.json` marker in the song cache records
 *   the source; "Procesar / reprocesar" clears it along with the rest
 *   of the cache.
 * - When offline (or the lookup fails) a one-time WorkManager job is
 *   queued with a CONNECTED constraint so the sync is retried as soon
 *   as internet is available — without ever failing the pipeline.
 */
@Singleton
class SyncOnlineLyricsUseCase @Inject constructor(
    @ApplicationContext private val context: Context,
    private val client: LrclibClient,
    private val songDao: SongDao,
    private val cacheLayout: SongCacheLayout,
    private val preferences: UserPreferences,
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend operator fun invoke(songId: String) {
        runCatching {
            if (!preferences.lyricsOnlineSync.first()) return
            val songDir = cacheLayout.dirFor(songId)
            val marker = File(songDir, MARKER_FILE)
            if (marker.exists()) return

            val karaokeFile = cacheLayout.karaokeFile(songId)
            if (!karaokeFile.exists()) return
            val local = json.decodeFromString(
                KaraokeDocument.serializer(),
                karaokeFile.readText(),
            )
            val song = songDao.findById(songId) ?: return
            val localWords = local.lines
                .flatMap { it.words }
                .map { normalizeWord(it.text) }
                .filter { it.isNotEmpty() }
                .toSet()

            val result = withContext(Dispatchers.IO) {
                client.findBest(
                    title = song.title,
                    artist = song.artist,
                    durationSec = song.durationMs / 1000.0,
                    localWords = localWords,
                )
            }
            val track = when (result) {
                is AppResult.Success -> result.value
                is AppResult.Failure -> {
                    // Probably offline: retry when a connection appears.
                    Log.i(TAG, "lyrics lookup failed (${result.error.message}); scheduling retry")
                    scheduleRetry(songId)
                    return
                }
            }
            if (track == null) {
                Log.i(TAG, "no convincing lyrics match for $songId; keeping local transcription")
                marker.writeText(json.encodeToString(Marker.serializer(), Marker(source = "none")))
                return
            }

            val realLines = track.syncedLyrics
                ?.let { LrcParser.parse(it).map { l -> LyricsAligner.RealLine(l.text, l.startSec) } }
                ?: track.plainLyrics
                    ?.lines()
                    ?.filter { it.isNotBlank() }
                    ?.map { LyricsAligner.RealLine(it.trim(), null) }
            if (realLines.isNullOrEmpty()) return

            var aligned = LyricsAligner.align(realLines, local)
            if (aligned == null) {
                Log.w(TAG, "lyrics match too weak; keeping local transcription")
                marker.writeText(json.encodeToString(Marker.serializer(), Marker(source = "rejected")))
                return
            }

            // Phase-3 auto-calibration: shift the whole document so
            // line starts follow the vocal onsets of the user's own
            // vocals track (fixes LRC offsets from the original
            // recording and small ASR drift).
            val vocalsFile = cacheLayout.vocalsFile(songId)
            if (vocalsFile.exists()) {
                val vocals = withContext(Dispatchers.IO) {
                    com.karaokei.core.media.io.WavReader.readPcm16Mono(vocalsFile)
                }
                val calibrated = LyricsCalibrator.calibrate(aligned, vocals, VOCALS_SAMPLE_RATE)
                if (calibrated !== aligned) {
                    Log.i(TAG, "lyrics offset auto-calibrated")
                    aligned = calibrated
                }
            }

            karaokeFile.writeText(json.encodeToString(KaraokeDocument.serializer(), aligned))
            marker.writeText(
                json.encodeToString(
                    Marker.serializer(),
                    Marker(
                        source = if (track.syncedLyrics != null) "lrclib-synced" else "lrclib-plain",
                        trackName = track.trackName,
                        artistName = track.artistName,
                    ),
                ),
            )
            Log.i(TAG, "lyrics synced from LRCLIB (${track.trackName} — ${track.artistName})")
        }.onFailure { Log.w(TAG, "lyrics sync failed: ${it.message}") }
    }

    /**
     * Queue the sync as a WorkManager job (CONNECTED constraint). This
     * decouples the sync from the pipeline service lifetime — the
     * service stops itself on DONE and its scope would otherwise
     * cancel the work — and gives the "retry when internet appears"
     * behaviour for free.
     */
    fun enqueue(songId: String) {
        val request = OneTimeWorkRequestBuilder<LyricsSyncWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
            )
            .setInputData(workDataOf(LyricsSyncWorker.KEY_SONG_ID to songId))
            .addTag(TAG_PREFIX + songId)
            .build()
        WorkManager.getInstance(context).enqueue(request)
    }

    private fun scheduleRetry(songId: String) = enqueue(songId)

    private fun normalizeWord(text: String): String =
        text.lowercase().replace(NON_WORD, "")

    @kotlinx.serialization.Serializable
    data class Marker(
        val source: String,
        val trackName: String? = null,
        val artistName: String? = null,
    )

    private val NON_WORD = Regex("[^\\p{L}\\p{N}']+")

    companion object {
        private const val TAG = "LyricsSync"
        private const val MARKER_FILE = "lyrics_sync.json"
        private const val TAG_PREFIX = "lyrics-sync:"
        private const val VOCALS_SAMPLE_RATE = 16000
    }
}

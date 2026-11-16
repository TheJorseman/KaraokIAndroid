package com.karaokei.feature.modelmanager.lyrics

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * One-time CONNECTED-constrained retry for [SyncOnlineLyricsUseCase]:
 * queued when the pipeline finished while offline and run by WorkManager
 * as soon as internet is available.
 */
@HiltWorker
class LyricsSyncWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val sync: SyncOnlineLyricsUseCase,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val songId = inputData.getString(KEY_SONG_ID) ?: return Result.failure()
        sync(songId)
        return Result.success()
    }

    companion object {
        const val KEY_SONG_ID: String = "song_id"
    }
}

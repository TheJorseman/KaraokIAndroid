package com.karaokei.feature.modelmanager.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ModelDownloadScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun enqueue(modelId: String, url: String): UUID {
        val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
            .setConstraints(
                Constraints.Builder()
                    // User-initiated downloads must start on any
                    // connection; UNMETERED left them queued forever
                    // on mobile data with no feedback in the UI.
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setInputData(workDataOf(
                ModelDownloadWorker.KEY_MODEL_ID to modelId,
                ModelDownloadWorker.KEY_MODEL_URL to url,
            ))
            .addTag(TAG_PREFIX + modelId)
            .build()
        WorkManager.getInstance(context).enqueue(request)
        return request.id
    }

    /** Status updates for a download enqueued by [enqueue]. */
    fun observe(workId: UUID): Flow<WorkInfo?> =
        WorkManager.getInstance(context).getWorkInfoByIdFlow(workId)

    fun cancel(modelId: String) {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG_PREFIX + modelId)
    }

    companion object {
        private const val TAG_PREFIX = "model-download:"
    }
}

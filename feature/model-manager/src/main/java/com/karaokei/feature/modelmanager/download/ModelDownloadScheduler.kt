package com.karaokei.feature.modelmanager.download

import android.content.Context
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import dagger.hilt.android.qualifiers.ApplicationContext
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
                    .setRequiredNetworkType(NetworkType.UNMETERED)
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

    fun cancel(modelId: String) {
        WorkManager.getInstance(context).cancelAllWorkByTag(TAG_PREFIX + modelId)
    }

    companion object {
        private const val TAG_PREFIX = "model-download:"
    }
}

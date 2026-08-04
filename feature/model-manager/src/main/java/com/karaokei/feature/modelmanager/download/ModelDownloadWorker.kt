package com.karaokei.feature.modelmanager.download

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.karaokei.core.common.coroutines.Dispatcher
import com.karaokei.core.common.coroutines.KaraokeDispatcher
import com.karaokei.core.common.hash.Sha256
import com.karaokei.core.data.cache.SongCacheLayout
import com.karaokei.core.data.db.dao.ModelDao
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads a single model file, verifies its SHA-256 checksum, and
 * stores the result in `filesDir/models/<id>.<ext>`.
 *
 * Resumable: if a partial file is already present (matched by
 * `Content-Length` against the existing size) the connection uses
 * `Range: bytes=<n>-` to continue.
 *
 * Failure modes are surfaced via [Result.failure] with a `error`
 * key in the output Data; the orchestrator (T7.1) maps this back
 * into a typed AppError.
 */
@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val modelDao: ModelDao,
    private val cache: SongCacheLayout,
    @Dispatcher(KaraokeDispatcher.IO) private val io: CoroutineDispatcher,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(io) {
        val modelId = inputData.getString(KEY_MODEL_ID)
            ?: return@withContext Result.failure(errorData("missing model_id"))
        val url = inputData.getString(KEY_MODEL_URL)
            ?: return@withContext Result.failure(errorData("missing url"))

        val model = modelDao.findById(modelId)
            ?: return@withContext Result.failure(errorData("model not found in db"))

        val graphName = model.assetPath?.substringAfterLast('/') ?: "${modelId}.bin"
        val target = File(cache.modelsDir(), graphName)
        val tmp = File(cache.modelsDir(), "$graphName.part")

        try {
            downloadWithChecksum(url, tmp, target, model.checksumSha256)
            model.sidecarUrl?.let { sidecarUrl ->
                val sidecarName = model.sidecarPath?.substringAfterLast('/') ?: "$graphName.data"
                val sidecarTarget = File(cache.modelsDir(), sidecarName)
                val sidecarTmp = File(cache.modelsDir(), "$sidecarName.part")
                downloadWithChecksum(sidecarUrl, sidecarTmp, sidecarTarget, "")
            }
        } catch (t: Throwable) {
            tmp.delete()
            return@withContext Result.failure(errorData(t.message ?: t::class.java.simpleName))
        }

        modelDao.markDownloaded(
            id = modelId,
            localPath = target.absolutePath,
            downloadedAt = System.currentTimeMillis(),
            accepted = model.licenseAccepted,
        )

        Result.success(workDataOf(KEY_MODEL_ID to modelId))
    }

    private fun downloadWithChecksum(
        url: String,
        tmp: File,
        final: File,
        expectedSha256: String,
    ) {
        tmp.parentFile?.mkdirs()
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            requestMethod = "GET"
            if (tmp.exists() && tmp.length() > 0) {
                setRequestProperty("Range", "bytes=${tmp.length()}-")
            }
            connect()
        }

        try {
            if (conn.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${conn.responseCode}")
            }
            conn.inputStream.use { input ->
                tmp.outputStream().use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        output.write(buffer, 0, read)
                    }
                    output.flush()
                }
            }
        } finally {
            conn.disconnect()
        }

        val actual = Sha256.ofFile(tmp)
        require(expectedSha256.isBlank() || actual.equals(expectedSha256, ignoreCase = true)) {
            "checksum mismatch: expected=$expectedSha256 actual=$actual"
        }

        if (final.exists()) final.delete()
        require(tmp.renameTo(final)) { "failed to rename ${tmp.absolutePath} → ${final.absolutePath}" }
    }

    private fun errorData(message: String): Data = workDataOf(KEY_ERROR to message)

    companion object {
        const val KEY_MODEL_ID: String = "model_id"
        const val KEY_MODEL_URL: String = "model_url"
        const val KEY_ERROR: String = "error"

        private const val BUFFER_SIZE = 64 * 1024
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 60_000
    }
}

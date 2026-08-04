package com.karaokei.core.ai.model

import android.content.Context
import com.karaokei.core.common.hash.Sha256
import com.karaokei.core.common.io.Streams
import com.karaokei.core.common.result.AppError
import com.karaokei.core.common.result.AppResult
import com.karaokei.core.common.result.runCatchingResult
import com.karaokei.core.data.db.entity.ModelEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Resolves a [ModelEntity] to a usable model on disk.
 *
 * Three modes are supported:
 *
 *  1. **Asset pack, single file** — `assets.open(model.assetPath)` is
 *     copied into `filesDir/models/<id>.<ext>` on first use and reused
 *     on subsequent launches.
 *  2. **Asset pack, sidecar** — when the graph references an external
 *     `<name>.onnx.data` file (RoFormer, some ORT exports), the sidecar
 *     is copied next to the graph. On-device inference reads the
 *     graph by file path so ORT can resolve the sidecar.
 *  3. **Downloaded** — already on `filesDir/models/<id>.<ext>`. Returns
 *     the path directly.
 */
@Singleton
class ModelLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun verifyChecksum(bytes: ByteArray, expectedSha256: String): Boolean {
        if (expectedSha256.isBlank()) return true // placeholder catalog entry
        return Sha256.ofBytes(bytes).equals(expectedSha256, ignoreCase = true)
    }

    /**
     * Returns the absolute path to the model graph. Embeds the model
     * from the asset pack if needed. The caller MUST release the
     * reference once the ORT session is closed; the file remains on
     * disk for subsequent launches.
     */
    fun resolvePath(model: ModelEntity): AppResult<String> = runCatchingResult {
        when {
            !model.localPath.isNullOrBlank() -> model.localPath!!
            model.isEmbedded -> ensureExtracted(model)
            else -> throw IllegalStateException(
                "model ${model.id} is not downloaded and is not embedded"
            )
        }
    }.let { result ->
        when (result) {
            is AppResult.Success -> result
            is AppResult.Failure -> AppResult.Failure(
                AppError.Model(result.error.message, result.error.cause)
            )
        }
    }

    /**
     * Backwards-compatible path: returns the model bytes. For models
     * with a sidecar, callers should prefer [resolvePath] and let ORT
     * load the graph from disk.
     */
    fun loadBytes(model: ModelEntity): AppResult<ByteArray> = runCatchingResult {
        if (model.isEmbedded && model.localPath.isNullOrBlank()) {
            val path = ensureExtracted(model)
            File(path).readBytes()
        } else {
            val path = model.localPath
                ?: throw IllegalStateException("model ${model.id} has no local_path")
            File(path).readBytes()
        }
    }.let { result ->
        when (result) {
            is AppResult.Success -> result
            is AppResult.Failure -> AppResult.Failure(
                AppError.Model(result.error.message, result.error.cause)
            )
        }
    }

    /**
     * Local-path resolver kept for tests and external-data flows.
     * Returns the cached file path if it exists, otherwise `null`.
     */
    fun resolveLocalPath(model: ModelEntity): String? {
        if (model.isEmbedded) {
            val path = extractedPath(model)
            val sidecar = File("$path.data")
            return path.takeIf { File(it).exists() && sidecar.exists() }
        }
        val path = model.localPath ?: return null
        val graph = File(path)
        val sidecar = File("$path.data")
        return path.takeIf { graph.exists() && sidecar.exists() }
    }

    private fun ensureExtracted(model: ModelEntity): String {
        val target = extractedPath(model)
        val graph = File(target)
        val sidecarName = model.sidecarPath ?: sidecarAssetName(model)
        val sidecar = if (sidecarName != null) File("$target.data") else null
        if (graph.exists() && (sidecar == null || sidecar.exists())) {
            return target
        }
        graph.parentFile?.mkdirs()
        context.assets.open(assetName(model)).use { input ->
            graph.outputStream().use { output ->
                Streams.copy(input, output)
            }
        }
        if (sidecar != null && sidecarName != null) {
            context.assets.open(sidecarName).use { input ->
                sidecar.outputStream().use { output ->
                    Streams.copy(input, output)
                }
            }
        }
        return target
    }

    private fun extractedPath(model: ModelEntity): String {
        val base = when (model.type) {
            com.karaokei.core.data.db.entity.ModelType.SEPARATION -> "separation"
            com.karaokei.core.data.db.entity.ModelType.TRANSCRIPTION -> "transcription"
        }
        val name = assetName(model).substringAfterLast('/')
        return File(context.filesDir, "models/$base/$name").absolutePath
    }

    private fun assetName(model: ModelEntity): String = inferAssetPath(model)

    /**
     * Sidecar name for a model, when its graph references an external
     * data file. Currently only the RoFormer graph carries one; for
     * everything else this returns `null` and the caller skips the
     * sidecar copy.
     */
    private fun sidecarAssetName(model: ModelEntity): String? {
        if (model.type != com.karaokei.core.data.db.entity.ModelType.SEPARATION) return null
        return model.sidecarPath ?: "${assetName(model)}.data"
    }

    /**
     * Convention: the asset path is `separation/<file>.onnx` for
     * separation models and `transcription/<file>.bin` for
     * transcription models. The catalog's `asset_path` is the
     * source of truth when present.
     */
    private fun inferAssetPath(model: ModelEntity): String {
        model.assetPath?.let { return it }
        val base = when (model.type) {
            com.karaokei.core.data.db.entity.ModelType.SEPARATION -> "separation"
            com.karaokei.core.data.db.entity.ModelType.TRANSCRIPTION -> "transcription"
        }
        val ext = if (model.type == com.karaokei.core.data.db.entity.ModelType.SEPARATION) "onnx" else "bin"
        return "$base/${model.id}.${ext}"
    }
}

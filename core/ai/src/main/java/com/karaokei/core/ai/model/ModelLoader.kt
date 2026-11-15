package com.karaokei.core.ai.model

import android.content.Context
import com.karaokei.core.common.hash.Sha256
import com.karaokei.core.common.io.Streams
import com.karaokei.core.common.result.AppError
import com.karaokei.core.common.result.AppResult
import com.karaokei.core.common.result.getOrThrow
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
     *
     * A `localPath` is only trusted when the file actually exists on
     * disk; otherwise the row is stale — the file was deleted or a
     * copy was interrupted — and we fall back to extraction (embedded
     * models) or fail with an actionable message instead of letting
     * ORT throw a cryptic "failed to open ORT model file".
     */
    fun resolvePath(model: ModelEntity): AppResult<String> = runCatchingResult {
        val localPath = model.localPath
        if (!localPath.isNullOrBlank()) {
            if (isUsableFile(File(localPath), model, enforceSize = false)) {
                ensureSidecarPresent(model, File(localPath))
                return@runCatchingResult localPath
            }
            if (model.isEmbedded) {
                // Stale/missing extracted or seeded copy: re-extract.
                return@runCatchingResult ensureExtracted(model)
            }
            throw IllegalStateException(
                "model ${model.id}: el archivo no existe o está incompleto en $localPath; " +
                    "vuelve a descargarlo desde la pantalla de Modelos"
            )
        }
        if (model.isEmbedded) {
            return@runCatchingResult ensureExtracted(model)
        }
        throw IllegalStateException(
            "model ${model.id} is not downloaded and is not embedded"
        )
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
        val path = resolvePath(model).getOrThrow()
        File(path).readBytes()
    }.let { result ->
        when (result) {
            is AppResult.Success -> result
            is AppResult.Failure -> AppResult.Failure(
                AppError.Model(result.error.message, result.error.cause)
            )
        }
    }

    /**
     * Cheap readiness probe used by callers that must pick a model
     * before opening a session. True when the model can be resolved
     * without hitting the network: the file is present and intact, or
     * the model ships in the asset pack.
     */
    fun isReady(model: ModelEntity): Boolean = runCatching {
        val localPath = model.localPath
        if (!localPath.isNullOrBlank()) {
            val graph = File(localPath)
            return isUsableFile(graph, model, enforceSize = false) && sidecarSatisfied(model, graph)
        }
        model.isEmbedded
    }.getOrDefault(false)

    /**
     * Local-path resolver kept for tests and external-data flows.
     * Returns the cached file path if it is usable, otherwise `null`.
     */
    fun resolveLocalPath(model: ModelEntity): String? {
        val enforceSize = model.isEmbedded
        val path = if (model.isEmbedded) extractedPath(model) else model.localPath ?: return null
        val graph = File(path)
        return path.takeIf { isUsableFile(graph, model, enforceSize) && sidecarSatisfied(model, graph) }
    }

    /**
     * Validates a cached model file: it must exist and be non-empty.
     * For files we extracted ourselves ([enforceSize] = true, embedded
     * assets) the catalog size must also match — that catches
     * interrupted legacy copies that pass the naive `exists()` test
     * and then fail deep inside ORT. Downloaded files are trusted by
     * existence alone: their SHA-256 was verified at download time and
     * re-checking `size_bytes` here could brick a model (and loop
     * forever) if the catalog ever ships a stale size.
     */
    private fun isUsableFile(file: File, model: ModelEntity, enforceSize: Boolean): Boolean {
        if (!file.exists() || file.length() == 0L) return false
        if (!enforceSize || model.sizeBytes <= 0L) return true
        return file.length() == model.sizeBytes
    }

    /**
     * ONNX external-data sidecars are resolved by the *declared file
     * name* (e.g. `syhft_core_t1100.onnx.data`) relative to the graph
     * directory, not as `<graph>.data`. The sidecar is required only
     * when the catalog declares one.
     */
    private fun sidecarFile(model: ModelEntity, graph: File): File? {
        val sidecarName = model.sidecarPath?.takeIf { it.isNotBlank() } ?: return null
        return File(graph.parentFile, sidecarName.substringAfterLast('/'))
    }

    private fun sidecarSatisfied(model: ModelEntity, graph: File): Boolean {
        val sidecar = sidecarFile(model, graph) ?: return true
        return sidecar.exists() && sidecar.length() > 0
    }

    private fun ensureSidecarPresent(model: ModelEntity, graph: File) {
        val sidecar = sidecarFile(model, graph) ?: return
        if (!sidecar.exists() || sidecar.length() == 0L) {
            throw IllegalStateException(
                "model ${model.id}: falta el archivo externo ${sidecar.name} junto a ${graph.absolutePath}; " +
                    "vuelve a descargarlo desde la pantalla de Modelos"
            )
        }
    }

    private fun ensureExtracted(model: ModelEntity): String {
        val target = extractedPath(model)
        val graph = File(target)
        val sidecar = sidecarFile(model, graph)
        if (isUsableFile(graph, model, enforceSize = true) && sidecarSatisfied(model, graph)) {
            return target
        }
        graph.parentFile?.mkdirs()
        copyAssetAtomic(assetName(model), graph)
        sidecar?.let { copyAssetAtomic(model.sidecarPath!!, it) }
        return target
    }

    /**
     * Copy an asset to [target] through a `.part` temp file and a
     * rename, so a process kill mid-copy never leaves a truncated
     * model that would pass the `exists()` check.
     */
    private fun copyAssetAtomic(assetPath: String, target: File) {
        val tmp = File(target.parentFile, target.name + ".part")
        try {
            context.assets.open(assetPath).use { input ->
                tmp.outputStream().use { output ->
                    Streams.copy(input, output)
                }
            }
            if (target.exists()) target.delete()
            if (!tmp.renameTo(target)) {
                throw IllegalStateException("failed to move ${tmp.name} into place at ${target.absolutePath}")
            }
        } catch (t: Throwable) {
            tmp.delete()
            throw t
        }
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

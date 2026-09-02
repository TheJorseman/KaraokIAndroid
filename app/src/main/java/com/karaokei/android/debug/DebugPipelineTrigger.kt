package com.karaokei.android.debug

import android.content.Context
import android.net.Uri
import android.util.Log
import com.karaokei.android.pipeline.PipelineForegroundService
import com.karaokei.core.common.result.getOrNull
import com.karaokei.core.data.cache.SongCacheLayout
import com.karaokei.core.data.db.dao.ModelDao
import com.karaokei.core.data.db.entity.ModelEntity
import com.karaokei.core.data.db.entity.ModelTier
import com.karaokei.core.data.db.entity.ModelType
import com.karaokei.core.data.preferences.UserPreferences
import com.karaokei.core.data.repository.SongRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Debug-only helper triggered by a launch intent extra on
 * [com.karaokei.android.MainActivity].
 *
 * When the activity is started with `--es debug_seed_uri <content-uri>`
 * the seeder copies the referenced file into `filesDir` (so the
 * scoped-storage `MediaCodec` decoder can read it from the app's
 * private directory), imports it through [SongRepository], and then
 * launches [PipelineForegroundService] for the resulting song.
 *
 * The trigger is no-op when the extra is missing or the file does not
 * exist on disk. There is no production code path that reaches this
 * class; it exists exclusively so `adb` can drive the pipeline end to
 * end during automated testing.
 */
@Singleton
class DebugPipelineTrigger @Inject constructor(
    @ApplicationContext private val context: Context,
    private val songRepository: SongRepository,
    private val modelDao: ModelDao,
    private val cacheLayout: SongCacheLayout,
    private val userPreferences: UserPreferences,
) {

    fun handle(seedUri: String?) {
        if (seedUri.isNullOrBlank()) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            val src = Uri.parse(seedUri)
            val imported = songRepository.import(src)
            val song = imported.getOrNull()?.entity
            if (song == null) {
                Log.e(TAG, "Import failed for $seedUri: $imported")
                return@launch
            }
            Log.i(TAG, "Debug seed imported song ${song.id} (${song.title})")
            PipelineForegroundService.start(context, song.id)
        }
    }

    fun handleFileCopy(seedPath: String?) {
        if (seedPath.isNullOrBlank()) return
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            // Accept three layouts:
            // 1. Bare file name → look in `filesDir` first (already
            //    pushed via `adb shell run-as ...`).
            // 2. Absolute path that already lives under `filesDir`.
            // 3. Any other absolute path that the process can read.
            val candidate = java.io.File(seedPath)
            val resolved: java.io.File = when {
                candidate.isAbsolute && candidate.exists() -> candidate
                else -> java.io.File(context.filesDir, seedPath)
            }
            if (!resolved.exists()) {
                Log.e(TAG, "Debug seed source missing: $seedPath (looked at ${resolved.absolutePath})")
                return@launch
            }
            val targetName = "debug_${resolved.name}"
            val target = java.io.File(context.filesDir, targetName)
            if (resolved.absolutePath != target.absolutePath) {
                try {
                    resolved.inputStream().use { input ->
                        target.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } catch (t: Throwable) {
                    Log.e(TAG, "Failed to copy ${resolved.absolutePath} -> ${target.absolutePath}", t)
                    return@launch
                }
            }
            val imported = songRepository.import(Uri.fromFile(target))
            val song = imported.getOrNull()?.entity
            if (song == null) {
                Log.e(TAG, "Import failed for $seedPath: $imported")
                return@launch
            }
            Log.i(TAG, "Debug seed imported song ${song.id} from ${resolved.absolutePath}")
            PipelineForegroundService.start(context, song.id)
        }
    }

    /**
     * Marks an already-pushed model file as "downloaded" by inserting
     * or updating the row in the `models` table so the pipeline finds
     * it via `findByTierAndType` instead of failing with
     * "no separation model available".
     *
     * Layout:
     * - The model file must already live at `filesDir/<relativePath>`
     *   (relative path is `<tier_class>/.onnx` for separation
     *   models).
     * - The metadata id/tier/type/name are resolved from the existing
     *   catalog row so the URL stays in sync with `assets/models/catalog.json`.
     */
    fun handleSeedModel(
        tier: ModelTier,
        type: ModelType,
        modelId: String,
        relativePath: String,
    ) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            val modelFile = java.io.File(context.filesDir, relativePath)
            if (!modelFile.exists()) {
                Log.e(TAG, "Debug seed model file missing: ${modelFile.absolutePath}")
                return@launch
            }
            val existing = modelDao.findById(modelId)
            val now = System.currentTimeMillis()
            val merged = (existing ?: ModelEntity(
                id = modelId,
                name = modelId,
                tier = tier,
                type = type,
                checksumSha256 = "",
                localPath = null,
                sizeBytes = modelFile.length(),
                downloadedAt = null,
                isEmbedded = false,
                url = null,
                license = "MIT",
                licenseAccepted = true,
                assetPath = "separation/${modelFile.name}",
                sidecarUrl = null,
                sidecarPath = null,
                tierClass = "htdemucs_${tier.name.lowercase()}",
                notes = "Injected via DebugPipelineTrigger",
            )).copy(
                localPath = modelFile.absolutePath,
                downloadedAt = now,
                sizeBytes = modelFile.length(),
                licenseAccepted = true,
            )
            modelDao.upsert(merged)
            Log.i(TAG, "Debug seeded model $modelId at ${modelFile.absolutePath}")
        }
    }

    fun handleSeedModelExtra(modelId: String?, relativePath: String?) {
        if (modelId.isNullOrBlank() || relativePath.isNullOrBlank()) return
        // The simplest mapping: derive the tier from the model id
        // prefix. Both Balanced and HQ use the 4-stem base
        // (`htdemucs-fp16-fast-sep`) at integration time.
        val tier = when {
            modelId.contains("hq", ignoreCase = true) -> ModelTier.HQ
            modelId.contains("balanced", ignoreCase = true) -> ModelTier.BALANCED
            else -> ModelTier.FAST
        }
        handleSeedModel(tier, ModelType.SEPARATION, modelId, relativePath)
    }

    /**
     * Override the preferred tier so the pipeline runs with the
     * embedded (or already-extracted) Fast model instead of the
     * default Balanced. Useful when only the bundled MDX-Net has
     * been downloaded/embedded.
     */
    fun handleSetTier(tierName: String?) {
        if (tierName.isNullOrBlank()) return
        val tier = runCatching { ModelTier.valueOf(tierName.uppercase()) }
            .getOrDefault(ModelTier.FAST)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { userPreferences.setSelectedTier(tier) }
                .onFailure { Log.e(TAG, "Failed to set preferred tier to $tier", it) }
                .onSuccess { Log.i(TAG, "Preferred tier set to $tier via debug trigger") }
        }
    }

    /**
     * Override the ORT execution provider used by every separator.
     * Accepted values: `AUTO`, `XNNPACK`, `CPU`, `NNAPI`. The next
     * pipeline run picks up the new value via
     * [com.karaokei.core.ai.ort.OrtSessionFactory.activeBackend].
     */
    fun handleSetBackend(backendName: String?) {
        if (backendName.isNullOrBlank()) return
        val backend = runCatching {
            com.karaokei.core.ai.ort.OrtSessionFactory.Backend.valueOf(backendName.uppercase())
        }.getOrNull()
        if (backend == null) {
            Log.e(TAG, "Unknown backend '$backendName'; valid: AUTO, XNNPACK, CPU, NNAPI")
            return
        }
        com.karaokei.core.ai.ort.OrtSessionFactory.activeBackend = backend
        Log.i(TAG, "ORT backend set to $backend via debug trigger")
    }

    companion object {
        private const val TAG = "DebugPipelineTrigger"

        const val EXTRA_SEED_URI: String = "debug_seed_uri"
        const val EXTRA_SEED_PATH: String = "debug_seed_path"
        const val EXTRA_SEED_MODEL_ID: String = "debug_seed_model_id"
        const val EXTRA_SEED_MODEL_PATH: String = "debug_seed_model_path"
        const val EXTRA_SET_TIER: String = "debug_set_tier"
        const val EXTRA_SET_BACKEND: String = "debug_set_backend"
    }
}


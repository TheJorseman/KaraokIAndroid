package com.karaokei.feature.modelmanager.sync

import android.util.Log
import com.karaokei.core.common.result.getOrNull
import com.karaokei.core.data.db.dao.ModelDao
import com.karaokei.core.data.db.entity.ModelEntity
import com.karaokei.feature.modelmanager.catalog.CatalogEntry
import com.karaokei.feature.modelmanager.catalog.CatalogLoader
import com.karaokei.feature.modelmanager.catalog.CatalogMapper
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reconciles the on-disk catalog with the `models` table.
 *
 * - New entries (by id) are inserted.
 * - Existing entries have their metadata refreshed (size, checksum, url)
 *   but their `localPath`, `downloadedAt`, and `licenseAccepted`
 *   fields are preserved.
 */
@Singleton
class CatalogSyncer @Inject constructor(
    private val catalogLoader: CatalogLoader,
    private val modelDao: ModelDao,
    private val mapper: CatalogMapper,
) {

    suspend fun syncFromBundledCatalog() {
        val result = catalogLoader.loadBundled()
        val catalog = result.getOrNull() ?: run {
            Log.e(TAG, "Failed to load bundled catalog: $result")
            return
        }
        Log.i(TAG, "Loading ${catalog.entries.size} catalog entries")
        catalog.entries.forEach { entry ->
            val existing = modelDao.findById(entry.id)
            if (existing == null) {
                modelDao.upsert(mapper.toEntity(entry))
                Log.i(TAG, "Inserted catalog entry: ${entry.id}")
            } else {
                modelDao.upsert(merge(existing, entry))
            }
        }
    }

    private fun merge(existing: ModelEntity, entry: CatalogEntry): ModelEntity {
        val mapped = mapper.toEntity(entry)
        return existing.copy(
            name = mapped.name,
            tier = mapped.tier,
            type = mapped.type,
            checksumSha256 = mapped.checksumSha256,
            sizeBytes = mapped.sizeBytes,
            url = mapped.url,
            license = mapped.license,
            isEmbedded = mapped.isEmbedded,
            assetPath = mapped.assetPath,
            sidecarUrl = mapped.sidecarUrl,
            sidecarPath = mapped.sidecarPath,
            // Preserve download state and license acceptance.
            localPath = existing.localPath,
            downloadedAt = existing.downloadedAt,
            licenseAccepted = existing.licenseAccepted || mapped.licenseAccepted,
        )
    }

    private companion object {
        private const val TAG = "CatalogSyncer"
    }
}

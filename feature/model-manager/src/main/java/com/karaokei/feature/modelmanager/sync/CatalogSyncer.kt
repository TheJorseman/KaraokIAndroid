package com.karaokei.feature.modelmanager.sync

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
        val catalog = catalogLoader.loadBundled().getOrNull() ?: return
        catalog.entries.forEach { entry ->
            val existing = modelDao.findById(entry.id)
            if (existing == null) {
                modelDao.upsert(mapper.toEntity(entry))
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
            // Preserve download state and license acceptance.
            localPath = existing.localPath,
            downloadedAt = existing.downloadedAt,
            licenseAccepted = existing.licenseAccepted || mapped.licenseAccepted,
        )
    }
}

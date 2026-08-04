package com.karaokei.feature.modelmanager.catalog

import com.karaokei.core.data.db.entity.ModelEntity
import com.karaokei.core.data.db.entity.ModelTier
import com.karaokei.core.data.db.entity.ModelType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mapper between catalog JSON entries and the persistent `models` table.
 */
@Singleton
class CatalogMapper @Inject constructor() {

    fun toEntity(entry: CatalogEntry, downloadedAt: Long? = null, localPath: String? = null): ModelEntity {
        return ModelEntity(
            id = entry.id,
            name = entry.name,
            tier = runCatching { ModelTier.valueOf(entry.tier) }.getOrDefault(ModelTier.FAST),
            type = runCatching { ModelType.valueOf(entry.type) }.getOrDefault(ModelType.SEPARATION),
            checksumSha256 = entry.checksumSha256,
            localPath = localPath,
            sizeBytes = entry.sizeBytes,
            downloadedAt = downloadedAt,
            isEmbedded = entry.embeddedInAssetPack,
            url = entry.url,
            license = entry.license,
            licenseAccepted = entry.license.equals("MIT", ignoreCase = true) ||
                entry.license.equals("Apache-2.0", ignoreCase = true),
            assetPath = entry.assetPath,
            sidecarUrl = entry.sidecarUrl,
            sidecarPath = entry.sidecarPath,
        )
    }
}

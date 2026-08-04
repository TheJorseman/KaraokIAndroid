package com.karaokei.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Catalog entry for an AI model. May be:
 *  - embedded in the asset pack (`isEmbedded = true`, `localPath = null`,
 *    loaded from AssetManager at runtime);
 *  - downloaded into `filesDir/models/` (`isEmbedded = false`,
 *    `localPath` set, `downloadedAt != null`).
 *
 * `checksumSha256` is the expected SHA-256 of the model file as
 * published in the catalog; used to verify downloads (T2.2) and to
 * detect tampering.
 */
@Entity(tableName = "models")
data class ModelEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "name") val name: String,
    @ColumnInfo(name = "tier") val tier: ModelTier,
    @ColumnInfo(name = "type") val type: ModelType,
    @ColumnInfo(name = "checksum_sha256") val checksumSha256: String,
    @ColumnInfo(name = "local_path") val localPath: String?,
    @ColumnInfo(name = "size_bytes") val sizeBytes: Long,
    @ColumnInfo(name = "downloaded_at") val downloadedAt: Long?,
    @ColumnInfo(name = "is_embedded") val isEmbedded: Boolean,
    @ColumnInfo(name = "url") val url: String?,
    @ColumnInfo(name = "license") val license: String,
    @ColumnInfo(name = "license_accepted") val licenseAccepted: Boolean,
    @ColumnInfo(name = "asset_path") val assetPath: String? = null,
    @ColumnInfo(name = "sidecar_url") val sidecarUrl: String? = null,
    @ColumnInfo(name = "sidecar_path") val sidecarPath: String? = null,
)

enum class ModelTier { FAST, BALANCED, HQ }
enum class ModelType { SEPARATION, TRANSCRIPTION }

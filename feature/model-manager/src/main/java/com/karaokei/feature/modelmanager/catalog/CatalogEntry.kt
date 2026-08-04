package com.karaokei.feature.modelmanager.catalog

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A single model entry in the model catalog. Distributed as a JSON
 * file (T2.1) and loaded at first launch.
 */
@Serializable
data class CatalogEntry(
    @SerialName("id") val id: String,
    @SerialName("name") val name: String,
    @SerialName("tier") val tier: String, // FAST | BALANCED | HQ
    @SerialName("type") val type: String, // SEPARATION | TRANSCRIPTION
    @SerialName("url") val url: String,
    @SerialName("size_bytes") val sizeBytes: Long,
    @SerialName("checksum_sha256") val checksumSha256: String,
    @SerialName("license") val license: String,
    @SerialName("embedded_in_asset_pack") val embeddedInAssetPack: Boolean = false,
    @SerialName("asset_path") val assetPath: String? = null,
    @SerialName("min_android_sdk") val minAndroidSdk: Int = 26,
    @SerialName("notes") val notes: String? = null,
)

@Serializable
data class ModelCatalog(
    @SerialName("version") val version: Int,
    @SerialName("updated_at") val updatedAt: String,
    @SerialName("entries") val entries: List<CatalogEntry>,
)

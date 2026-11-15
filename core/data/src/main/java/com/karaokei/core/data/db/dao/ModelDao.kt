package com.karaokei.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.karaokei.core.data.db.entity.ModelEntity
import com.karaokei.core.data.db.entity.ModelTier
import com.karaokei.core.data.db.entity.ModelType
import kotlinx.coroutines.flow.Flow

@Dao
interface ModelDao {
    @Query("SELECT * FROM models ORDER BY tier, type")
    fun observeAll(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE tier = :tier AND type = :type LIMIT 1")
    suspend fun findByTierAndType(tier: ModelTier, type: ModelType): ModelEntity?

    /**
     * Tier-preferred model that can actually be loaded: either bundled
     * in the asset pack or already downloaded (`local_path` set).
     * Rows for models that were never downloaded are excluded so the
     * pipeline falls back to a usable model instead of failing at
     * session open time.
     */
    @Query("SELECT * FROM models WHERE tier = :tier AND type = :type " +
        "AND (is_embedded = 1 OR local_path IS NOT NULL) LIMIT 1")
    suspend fun findUsableByTierAndType(tier: ModelTier, type: ModelType): ModelEntity?

    @Query("SELECT * FROM models WHERE type = :type")
    suspend fun findByType(type: ModelType): List<ModelEntity>

    @Query("SELECT * FROM models WHERE type = :type AND local_path IS NOT NULL ORDER BY downloaded_at DESC")
    suspend fun findDownloadedByType(type: ModelType): List<ModelEntity>

    /** All models of [type] that can be loaded without downloading. */
    @Query("SELECT * FROM models WHERE type = :type " +
        "AND (is_embedded = 1 OR local_path IS NOT NULL) ORDER BY downloaded_at DESC")
    suspend fun findUsableByType(type: ModelType): List<ModelEntity>

    @Query("SELECT * FROM models WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ModelEntity?

    @Query("SELECT * FROM models WHERE is_embedded = 1")
    suspend fun findEmbedded(): List<ModelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(model: ModelEntity)

    @Query("UPDATE models SET local_path = :localPath, downloaded_at = :downloadedAt, license_accepted = :accepted WHERE id = :id")
    suspend fun markDownloaded(id: String, localPath: String, downloadedAt: Long, accepted: Boolean)

    @Query("UPDATE models SET license_accepted = :accepted WHERE id = :id")
    suspend fun setLicenseAccepted(id: String, accepted: Boolean)

    @Query("DELETE FROM models WHERE id = :id")
    suspend fun deleteById(id: String)
}

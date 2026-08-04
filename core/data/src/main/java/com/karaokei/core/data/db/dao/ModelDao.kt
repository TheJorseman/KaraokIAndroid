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

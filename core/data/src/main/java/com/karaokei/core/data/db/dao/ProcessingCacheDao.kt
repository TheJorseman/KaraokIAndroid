package com.karaokei.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.karaokei.core.data.db.entity.ProcessingCacheEntity
import com.karaokei.core.data.db.entity.ProcessingStage

@Dao
interface ProcessingCacheDao {
    @Query("SELECT * FROM processing_cache WHERE song_id = :songId")
    suspend fun findForSong(songId: String): List<ProcessingCacheEntity>

    @Query("SELECT * FROM processing_cache WHERE song_id = :songId AND stage = :stage LIMIT 1")
    suspend fun find(songId: String, stage: ProcessingStage): ProcessingCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: ProcessingCacheEntity)

    @Query("DELETE FROM processing_cache WHERE song_id = :songId")
    suspend fun deleteForSong(songId: String)
}

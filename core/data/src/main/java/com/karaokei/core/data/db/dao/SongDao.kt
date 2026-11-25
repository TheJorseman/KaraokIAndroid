package com.karaokei.core.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.karaokei.core.data.db.entity.SongEntity
import com.karaokei.core.data.db.entity.SongStatus
import kotlinx.coroutines.flow.Flow

@Dao
interface SongDao {
    @Query("SELECT * FROM songs ORDER BY created_at DESC")
    fun observeAll(): Flow<List<SongEntity>>

    @Query("SELECT * FROM songs WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): SongEntity?

    @Query("SELECT * FROM songs WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<SongEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(song: SongEntity)

    @Update
    suspend fun update(song: SongEntity)

    @Query("UPDATE songs SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: SongStatus)

    /**
     * Clears transient processing statuses left behind when the
     * process dies mid-pipeline (a crash leaves songs stuck at
     * "Separando voz…" forever). Safe to run at cold start, before
     * any pipeline is running.
     */
    @Query("UPDATE songs SET status = 'IMPORTED' " +
        "WHERE status IN ('SEPARATING', 'TRANSCRIBING', 'ALIGNING')")
    suspend fun resetTransientStatuses()

    /** Songs whose pipeline was interrupted (process killed mid-run). */
    @Query("SELECT id FROM songs WHERE status IN ('SEPARATING', 'TRANSCRIBING', 'ALIGNING')")
    suspend fun findInterruptedIds(): List<String>

    @Query("DELETE FROM songs WHERE id = :id")
    suspend fun deleteById(id: String)
}

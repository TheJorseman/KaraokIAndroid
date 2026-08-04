package com.karaokei.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/**
 * Tracks which pipeline stages have produced output for a given song.
 *
 * The presence of a row for a `(song_id, stage)` pair means that the
 * corresponding output is available on disk at `outputPath` and does
 * NOT need to be re-run. The pipeline orchestrator (T7.1) consults
 * this table to skip completed stages.
 */
@Entity(
    tableName = "processing_cache",
    primaryKeys = ["song_id", "stage"],
    foreignKeys = [
        ForeignKey(
            entity = SongEntity::class,
            parentColumns = ["id"],
            childColumns = ["song_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("stage")],
)
data class ProcessingCacheEntity(
    @ColumnInfo(name = "song_id") val songId: String,
    @ColumnInfo(name = "stage") val stage: ProcessingStage,
    @ColumnInfo(name = "completed_at") val completedAt: Long,
    @ColumnInfo(name = "output_path") val outputPath: String,
)

enum class ProcessingStage { SEPARATION, TRANSCRIPTION, ALIGNMENT }

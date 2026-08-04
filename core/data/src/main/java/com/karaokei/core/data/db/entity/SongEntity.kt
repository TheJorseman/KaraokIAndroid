package com.karaokei.core.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A song imported into the library.
 *
 * `id` is the SHA-256 of the original file contents (see T1.4), so the
 * same audio file always maps to the same row regardless of its
 * storage location or file name.
 */
@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "title") val title: String,
    @ColumnInfo(name = "artist") val artist: String?,
    @ColumnInfo(name = "duration_ms") val durationMs: Long,
    @ColumnInfo(name = "file_uri") val fileUri: String,
    @ColumnInfo(name = "cover_uri") val coverUri: String?,
    @ColumnInfo(name = "status") val status: SongStatus,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

enum class SongStatus {
    IMPORTED,
    SEPARATING,
    TRANSCRIBING,
    ALIGNING,
    READY,
    ERROR,
}

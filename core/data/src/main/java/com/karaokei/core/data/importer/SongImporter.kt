package com.karaokei.core.data.importer

import android.content.ContentResolver
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.karaokei.core.common.hash.Sha256
import com.karaokei.core.common.result.AppError
import com.karaokei.core.common.result.AppResult
import com.karaokei.core.common.result.runCatchingResult
import com.karaokei.core.data.db.entity.SongEntity
import com.karaokei.core.data.db.entity.SongStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import com.karaokei.core.common.coroutines.Dispatcher
import com.karaokei.core.common.coroutines.KaraokeDispatcher
import javax.inject.Inject
import javax.inject.Singleton

data class ImportedSong(
    val entity: SongEntity,
    val displayName: String,
)

/**
 * Imports songs from a content URI returned by the Storage Access
 * Framework (SAF). Computes the song_id as the SHA-256 of the file
 * contents (T1.4) and extracts basic metadata (title, artist,
 * duration) via MediaMetadataRetriever.
 *
 * The import does NOT copy the file: the SAF URI is stored in the
 * database, and the file is read on demand by the extraction and
 * playback paths. This keeps the cache minimal and respects the
 * scoped storage model.
 */
@Singleton
class SongImporter @Inject constructor(
    @ApplicationContext private val context: Context,
    @Dispatcher(KaraokeDispatcher.IO) private val io: CoroutineDispatcher,
) {

    suspend fun import(uri: Uri): AppResult<ImportedSong> = runCatchingResult {
        val resolver = context.contentResolver
        val (displayName, sizeBytes) = queryDisplayInfo(resolver, uri)
        val songId = computeSongId(resolver, uri)
        val metadata = readMetadata(resolver, uri)
        val now = System.currentTimeMillis()

        val entity = SongEntity(
            id = songId,
            title = metadata.title ?: displayName.substringBeforeLast('.').ifBlank { displayName },
            artist = metadata.artist,
            durationMs = metadata.durationMs,
            fileUri = uri.toString(),
            coverUri = null,
            status = SongStatus.IMPORTED,
            createdAt = now,
        )
        ImportedSong(entity, displayName = displayName)
    }.let { result ->
        // Wrap IO errors uniformly.
        when (result) {
            is AppResult.Success -> result
            is AppResult.Failure -> AppResult.Failure(
                AppError.Io(result.error.message, result.error.cause)
            )
        }
    }

    private data class DisplayInfo(val displayName: String, val sizeBytes: Long)
    private data class AudioMetadata(val title: String?, val artist: String?, val durationMs: Long)

    private fun queryDisplayInfo(resolver: ContentResolver, uri: Uri): DisplayInfo {
        var displayName = uri.lastPathSegment ?: "unknown"
        var sizeBytes = 0L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                    displayName = cursor.getString(nameIndex) ?: displayName
                }
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                    sizeBytes = cursor.getLong(sizeIndex)
                }
            }
        }
        return DisplayInfo(displayName, sizeBytes)
    }

    private fun computeSongId(resolver: ContentResolver, uri: Uri): String {
        // SHA-256 streamed from the content stream so we don't load the
        // whole file into memory.
        resolver.openInputStream(uri)?.use { input ->
            return Sha256.ofStream(input)
        } ?: throw IllegalStateException("cannot open input stream for $uri")
    }

    private fun readMetadata(resolver: ContentResolver, uri: Uri): AudioMetadata {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            AudioMetadata(title, artist, durationMs)
        } finally {
            try { retriever.release() } catch (_: Throwable) { /* noop */ }
        }
    }
}

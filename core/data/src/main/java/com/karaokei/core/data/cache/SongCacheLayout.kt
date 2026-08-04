package com.karaokei.core.data.cache

import android.content.Context
import com.karaokei.core.data.db.entity.SongEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * On-disk layout for a single song's pipeline cache.
 *
 *   <filesDir>/cache/<song_id>/
 *     vocals.wav
 *     instrumental.wav
 *     transcript.json
 *     karaoke.json
 *     metadata.json
 */
@Singleton
class SongCacheLayout @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun rootDir(): File = File(context.filesDir, "cache").apply { mkdirs() }

    fun dirFor(song: SongEntity): File = dirFor(song.id)

    fun dirFor(songId: String): File = File(rootDir(), songId).apply { mkdirs() }

    fun vocalsFile(songId: String): File = File(dirFor(songId), "vocals.wav")
    fun instrumentalFile(songId: String): File = File(dirFor(songId), "instrumental.wav")
    fun transcriptFile(songId: String): File = File(dirFor(songId), "transcript.json")
    fun karaokeFile(songId: String): File = File(dirFor(songId), "karaoke.json")
    fun metadataFile(songId: String): File = File(dirFor(songId), "metadata.json")

    fun hasKaraoke(songId: String): Boolean = karaokeFile(songId).exists()
    fun hasTranscript(songId: String): Boolean = transcriptFile(songId).exists()
    fun hasSeparation(songId: String): Boolean =
        vocalsFile(songId).exists() && instrumentalFile(songId).exists()

    fun deleteAll(songId: String): Boolean {
        val dir = dirFor(songId)
        if (!dir.exists()) return true
        dir.listFiles()?.forEach { it.delete() }
        return dir.delete()
    }

    fun modelsDir(): File = File(context.filesDir, "models").apply { mkdirs() }
}

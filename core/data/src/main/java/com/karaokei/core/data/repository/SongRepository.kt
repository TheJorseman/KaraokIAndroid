package com.karaokei.core.data.repository

import com.karaokei.core.common.result.AppError
import com.karaokei.core.common.result.AppResult
import com.karaokei.core.common.result.runCatchingResult
import com.karaokei.core.data.db.dao.SongDao
import com.karaokei.core.data.db.entity.SongEntity
import com.karaokei.core.data.db.entity.SongStatus
import com.karaokei.core.data.importer.ImportedSong
import com.karaokei.core.data.importer.SongImporter
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SongRepository @Inject constructor(
    private val songDao: SongDao,
    private val importer: SongImporter,
) {

    fun observeAll(): Flow<List<SongEntity>> = songDao.observeAll()

    fun observeById(id: String): Flow<SongEntity?> = songDao.observeById(id)

    suspend fun import(uri: android.net.Uri): AppResult<ImportedSong> {
        return when (val result = importer.import(uri)) {
            is AppResult.Success -> {
                songDao.upsert(result.value.entity)
                result
            }
            is AppResult.Failure -> result
        }
    }

    suspend fun findById(id: String): SongEntity? = songDao.findById(id)

    suspend fun updateStatus(id: String, status: SongStatus) = songDao.updateStatus(id, status)

    suspend fun delete(id: String): AppResult<Unit> = runCatchingResult {
        songDao.deleteById(id)
    }.let { result ->
        when (result) {
            is AppResult.Success -> AppResult.Success(Unit)
            is AppResult.Failure -> AppResult.Failure(AppError.Database(result.error.message, result.error.cause))
        }
    }
}

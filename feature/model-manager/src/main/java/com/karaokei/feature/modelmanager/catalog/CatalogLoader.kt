package com.karaokei.feature.modelmanager.catalog

import android.content.Context
import com.karaokei.core.common.result.AppError
import com.karaokei.core.common.result.AppResult
import com.karaokei.core.common.result.runCatchingResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads the bundled model catalog from `assets/models/catalog.json`.
 *
 * The catalog is shipped with the APK and can be refreshed later from
 * a remote endpoint (not in MVP). The structure includes both the
 * embedded Fast tier entries (Asset Pack) and the downloadable
 * Balanced / HQ entries.
 */
@Singleton
class CatalogLoader @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun loadBundled(): AppResult<ModelCatalog> = runCatchingResult {
        val raw = context.assets.open(CATALOG_PATH).bufferedReader().use { it.readText() }
        json.decodeFromString(ModelCatalog.serializer(), raw)
    }.let { result ->
        when (result) {
            is AppResult.Success -> result
            is AppResult.Failure -> AppResult.Failure(
                AppError.Io("failed to load bundled catalog: ${result.error.message}", result.error.cause)
            )
        }
    }

    companion object {
        const val CATALOG_PATH: String = "models/catalog.json"
    }
}

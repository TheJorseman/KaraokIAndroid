package com.karaokei.feature.modelmanager

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.karaokei.core.data.db.dao.ModelDao
import com.karaokei.core.data.db.entity.ModelEntity
import com.karaokei.core.data.db.entity.ModelTier
import com.karaokei.core.data.db.entity.ModelType
import com.karaokei.core.data.preferences.UserPreferences
import com.karaokei.feature.modelmanager.download.ModelDownloadScheduler
import com.karaokei.feature.modelmanager.sync.CatalogSyncer
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TierOption(
    val tier: ModelTier,
    val separation: ModelEntryStatus,
    val transcription: ModelEntryStatus,
)

data class ModelEntryStatus(
    val entity: ModelEntity,
    val canDownload: Boolean,
    val reasonCannotDownload: String? = null,
)

data class TierUiState(
    val selectedTier: ModelTier,
    val options: List<TierOption>,
    val nonCommercialAccepted: Boolean,
    val showLicensePrompt: Boolean,
    val error: String? = null,
)

@HiltViewModel
class ModelManagerViewModel @Inject constructor(
    private val modelDao: ModelDao,
    private val catalogSyncer: CatalogSyncer,
    private val downloadScheduler: ModelDownloadScheduler,
    private val preferences: UserPreferences,
) : ViewModel() {

    private val _showLicensePrompt = MutableStateFlow(false)
    private val _error = MutableStateFlow<String?>(null)
    private val _pendingLicenseModelId = MutableStateFlow<String?>(null)

    val state: StateFlow<TierUiState> = combine(
        modelDao.observeAll(),
        preferences.selectedTier,
        preferences.nonCommercialLicenseAccepted,
        _showLicensePrompt,
        _error,
    ) { models, tier, accepted, prompt, error ->
        buildState(models, tier, accepted, prompt, error)
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = TierUiState(
            selectedTier = ModelTier.FAST,
            options = emptyList(),
            nonCommercialAccepted = false,
            showLicensePrompt = false,
        ),
    )

    private val _effects = MutableStateFlow<Effect?>(null)
    val effects: StateFlow<Effect?> = _effects.asStateFlow()

    init {
        viewModelScope.launch {
            // The catalog table can be wiped by `pm clear` while the
            // ViewModel survives the activity restart, so re-sync
            // every time we observe an empty models list. The sync
            // is idempotent (OnConflictStrategy.REPLACE) and reads
            // only the bundled assets, so it does not interfere with
            // user downloads.
            modelDao.observeAll().collect { rows ->
                if (rows.isEmpty()) {
                    catalogSyncer.syncFromBundledCatalog()
                }
            }
        }
    }

    fun selectTier(tier: ModelTier) {
        viewModelScope.launch { preferences.setSelectedTier(tier) }
    }

    fun download(modelId: String) {
        viewModelScope.launch {
            val model = modelDao.findById(modelId) ?: return@launch
            if (!model.licenseAccepted) {
                _pendingLicenseModelId.value = modelId
                _showLicensePrompt.value = true
                return@launch
            }
            val url = model.url
            if (url.isNullOrBlank()) {
                emitMessage("Este modelo no tiene URL de descarga")
                return@launch
            }
            runCatching { downloadScheduler.enqueue(modelId, url) }
                .onSuccess { emitMessage("Descarga iniciada. Puedes seguir usando la app.") }
                .onFailure { emitMessage(it.message ?: "No se pudo iniciar la descarga") }
        }
    }

    fun acceptLicenseForCurrent() {
        viewModelScope.launch {
            preferences.setNonCommercialLicenseAccepted(true)
            _pendingLicenseModelId.value?.let { modelId ->
                modelDao.setLicenseAccepted(modelId, true)
                _pendingLicenseModelId.value = null
                download(modelId)
            }
            _showLicensePrompt.value = false
        }
    }

    fun dismissLicensePrompt() {
        _showLicensePrompt.value = false
    }

    fun clearError() {
        _error.value = null
    }

    private fun emitMessage(message: String) {
        _effects.value = Effect.ShowMessage(message)
    }

    fun consumeEffect() {
        _effects.value = null
    }

    private suspend fun buildState(
        models: List<ModelEntity>,
        tier: ModelTier,
        accepted: Boolean,
        prompt: Boolean,
        error: String?,
    ): TierUiState {
        val byTierType: (ModelTier, ModelType) -> ModelEntity? = { t, type ->
            models.firstOrNull { it.tier == t && it.type == type }
        }
        val options = ModelTier.values().map { t ->
            val sep = byTierType(t, ModelType.SEPARATION)
            val tr = byTierType(t, ModelType.TRANSCRIPTION)
            TierOption(
                tier = t,
                separation = sep?.toStatus(accepted) ?: ModelEntryStatus(
                    entity = placeholderEntity(t, ModelType.SEPARATION),
                    canDownload = false,
                    reasonCannotDownload = "Sin modelo de separación para el tier ${t.displayName()}",
                ),
                transcription = tr?.toStatus(accepted) ?: ModelEntryStatus(
                    entity = placeholderEntity(t, ModelType.TRANSCRIPTION),
                    canDownload = false,
                    reasonCannotDownload = "Sin modelo de transcripción para el tier ${t.displayName()}",
                ),
            )
        }
        return TierUiState(
            selectedTier = tier,
            options = options,
            nonCommercialAccepted = accepted,
            showLicensePrompt = prompt,
            error = error,
        )
    }

    private fun ModelTier.displayName(): String = when (this) {
        ModelTier.FAST -> "Fast"
        ModelTier.BALANCED -> "Balanced"
        ModelTier.HQ -> "HQ"
    }

    private fun ModelEntity.toStatus(accepted: Boolean): ModelEntryStatus {
        val downloadable = !isEmbedded && downloadedAt == null && !url.isNullOrBlank()
        val requiresAcceptance = !licenseAccepted &&
            !license.equals("MIT", ignoreCase = true) &&
            !license.equals("Apache-2.0", ignoreCase = true)
        return ModelEntryStatus(
            entity = this,
            canDownload = downloadable && (!requiresAcceptance || accepted),
            reasonCannotDownload = when {
                isEmbedded -> "Empaquetado en el Asset Pack"
                downloadedAt != null -> "Ya descargado"
                url.isNullOrBlank() -> "Sin URL"
                requiresAcceptance && !accepted -> "Requiere aceptar la licencia"
                else -> null
            },
        )
    }

    private fun placeholderEntity(tier: ModelTier, type: ModelType): ModelEntity =
        ModelEntity(
            id = "${tier.name.lowercase()}-${type.name.lowercase()}-missing",
            name = "${tier.name} ${type.name}",
            tier = tier,
            type = type,
            checksumSha256 = "",
            localPath = null,
            sizeBytes = 0L,
            downloadedAt = null,
            isEmbedded = false,
            url = null,
            license = "",
            licenseAccepted = false,
            assetPath = null,
            sidecarUrl = null,
            sidecarPath = null,
        )

    sealed interface Effect {
        data class ShowMessage(val text: String) : Effect
    }
}

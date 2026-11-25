package com.karaokei.feature.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.karaokei.core.data.db.entity.ModelTier
import com.karaokei.core.data.preferences.UserPreferences

private val ACTIVE_PRESETS = listOf("#FFFFD740", "#FF82B1FF", "#FF80CBC4", "#FFFF8A80", "#FFB39DDB")
private val UPCOMING_PRESETS = listOf("#B3E5FC", "#B3FFFFFF", "#B3FFD180", "#B3C5E1A5")

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val tier by viewModel.selectedTier.collectAsStateWithLifecycle()
    val language by viewModel.preferredLanguage.collectAsStateWithLifecycle()
    val lyricsSync by viewModel.lyricsOnlineSync.collectAsStateWithLifecycle()
    val hqTranscription by viewModel.highQualityTranscription.collectAsStateWithLifecycle()
    val style by viewModel.karaokeStyle.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Ajustes") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Atrás")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Section("Calidad y modelos") {
                Text(
                    "El tier define el modelo de separación; la transcripción usa el " +
                        "mejor modelo descargado si está activada la máxima calidad.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = tier == ModelTier.FAST,
                        onClick = { viewModel.setTier(ModelTier.FAST) },
                        label = { Text("Rápido") },
                    )
                    FilterChip(
                        selected = tier == ModelTier.BALANCED,
                        onClick = { viewModel.setTier(ModelTier.BALANCED) },
                        label = { Text("Equilibrado") },
                    )
                    FilterChip(
                        selected = tier == ModelTier.HQ,
                        onClick = { viewModel.setTier(ModelTier.HQ) },
                        label = { Text("Alta") },
                    )
                }
                ToggleRow(
                    title = "Transcripción de máxima calidad",
                    subtitle = "Usa el mejor Whisper descargado (small > base > tiny).",
                    checked = hqTranscription,
                    onCheckedChange = viewModel::setHighQualityTranscription,
                )
                Text("Idioma de transcripción", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("auto" to "Auto", "es" to "Español", "en" to "Inglés").forEach { (code, label) ->
                        FilterChip(
                            selected = language == code,
                            onClick = { viewModel.setLanguage(code) },
                            label = { Text(label) },
                        )
                    }
                }
            }

            Section("Letras online") {
                ToggleRow(
                    title = "Sincronizar en línea",
                    subtitle = "Busca las letras reales en LRCLIB y las ajusta a tu audio. " +
                        "Solo se envían título, artista y duración.",
                    checked = lyricsSync,
                    onCheckedChange = viewModel::setLyricsOnlineSync,
                )
            }

            Section("Estilo del karaoke") {
                Text(
                    "Tamaño de letra: ${style.fontSizeSp.toInt()} sp",
                    style = MaterialTheme.typography.titleSmall,
                )
                Slider(
                    value = style.fontSizeSp,
                    onValueChange = { viewModel.setKaraokeStyle(style.copy(fontSizeSp = it)) },
                    valueRange = 24f..64f,
                )
                Text("Color de la palabra activa", style = MaterialTheme.typography.titleSmall)
                ColorRow(ACTIVE_PRESETS, style.activeColor) { picked ->
                    viewModel.setKaraokeStyle(style.copy(activeColor = picked))
                }
                Text("Color de lo que viene", style = MaterialTheme.typography.titleSmall)
                ColorRow(UPCOMING_PRESETS, style.upcomingColor) { picked ->
                    viewModel.setKaraokeStyle(style.copy(upcomingColor = picked))
                }
                ToggleRow(
                    title = "Sombra del texto",
                    subtitle = "Mejora el contraste sobre vídeos.",
                    checked = style.shadowEnabled,
                    onCheckedChange = { viewModel.setKaraokeStyle(style.copy(shadowEnabled = it)) },
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ColorRow(presets: List<String>, selected: String, onPick: (String) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        presets.forEach { hex ->
            val color = Color(android.graphics.Color.parseColor(hex))
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(color = color, shape = CircleShape)
                    .clickable { onPick(hex) },
                contentAlignment = Alignment.Center,
            ) {
                if (hex == selected) {
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .background(Color.White, CircleShape),
                    )
                }
            }
        }
    }
}

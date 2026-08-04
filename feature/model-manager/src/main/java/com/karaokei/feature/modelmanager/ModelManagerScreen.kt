package com.karaokei.feature.modelmanager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.karaokei.core.data.db.entity.ModelTier

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun ModelManagerScreen(
    onClose: () -> Unit,
    viewModel: ModelManagerViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val effect by viewModel.effects.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(effect) {
        val message = (effect as? ModelManagerViewModel.Effect.ShowMessage)?.text
        if (message != null) {
            snackbarHostState.showSnackbar(message)
            viewModel.consumeEffect()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Modelos") },
                navigationIcon = {
                    TextButton(onClick = onClose) { Text("Cerrar") }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    "Elige la calidad. Fast viene embebido en la app. Las otras se descargan bajo demanda.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            items(state.options) { option ->
                TierCard(
                    option = option,
                    selected = option.tier == state.selectedTier,
                    onSelect = { viewModel.selectTier(option.tier) },
                    onDownload = { modelId -> viewModel.download(modelId) },
                )
            }
        }
    }

    if (state.showLicensePrompt) {
        LicensePromptDialog(
            onAccept = { viewModel.acceptLicenseForCurrent() },
            onDismiss = { viewModel.dismissLicensePrompt() },
        )
    }
}

@Composable
private fun TierCard(
    option: TierOption,
    selected: Boolean,
    onSelect: () -> Unit,
    onDownload: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onSelect,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = onSelect)
                Spacer(Modifier.height(0.dp))
                Text(
                    text = option.tier.displayName(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(8.dp))
            ModelRow(
                label = "Separación",
                status = option.separation,
                onDownload = onDownload,
            )
            Spacer(Modifier.height(4.dp))
            ModelRow(
                label = "Transcripción",
                status = option.transcription,
                onDownload = onDownload,
            )
        }
    }
}

@Composable
private fun ModelRow(
    label: String,
    status: ModelEntryStatus,
    onDownload: (String) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "${status.entity.name} · ${formatBytes(status.entity.sizeBytes)}",
                style = MaterialTheme.typography.bodySmall,
            )
            status.reasonCannotDownload?.let {
                Text(it, style = MaterialTheme.typography.labelSmall)
            }
        }
        if (status.canDownload) {
            Button(onClick = { onDownload(status.entity.id) }) {
                Text("Descargar")
            }
        }
    }
}

@Composable
private fun LicensePromptDialog(
    onAccept: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Licencia no comercial") },
        text = {
            Text(
                "Este modelo se distribuye bajo una licencia que prohíbe el uso comercial. " +
                    "Si aceptas, podrás descargarlo y usarlo en esta app. " +
                    "Si no, quédate con el tier Fast embebido en la app."
            )
        },
        confirmButton = {
            Button(onClick = onAccept) { Text("Aceptar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        },
    )
}

private fun ModelTier.displayName(): String = when (this) {
    ModelTier.FAST -> "Fast"
    ModelTier.BALANCED -> "Balanced"
    ModelTier.HQ -> "HQ"
}

private fun formatBytes(bytes: Long): String = when {
    bytes <= 0L -> "—"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
    bytes < 1024L * 1024L * 1024L -> "${bytes / (1024L * 1024L)} MB"
    else -> "%.1f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}

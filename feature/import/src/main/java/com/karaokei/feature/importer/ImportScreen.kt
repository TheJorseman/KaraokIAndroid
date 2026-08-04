package com.karaokei.feature.importer

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel

@Composable
fun ImportScreen(
    onSongImported: (String) -> Unit,
    onCancel: () -> Unit,
    viewModel: ImportViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    val pickFile = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) {
            onCancel()
        } else {
            viewModel.import(uri)
        }
    }

    LaunchedEffect(state.importedSongId) {
        state.importedSongId?.let { onSongImported(it) }
    }

    LaunchedEffect(Unit) {
        // Accept audio + video documents. SAF handles content URIs.
        pickFile.launch(arrayOf("audio/*", "video/*"))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("Selecciona un archivo de audio o video para importar.")
        if (state.error != null) {
            Text("Error: ${state.error}")
            Button(onClick = { pickFile.launch(arrayOf("audio/*", "video/*")) }) {
                Text("Reintentar")
            }
        }
        OutlinedButton(onClick = onCancel) {
            Text("Cancelar")
        }
    }
}

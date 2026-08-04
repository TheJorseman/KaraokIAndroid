package com.karaokei.feature.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun OnboardingScreen(
    onFinished: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Bienvenido a KaraokIAndroid", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Esta app procesa canciones en tu dispositivo. No envía datos a la nube.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "La app trae un modelo \"Fast\" embebido. Para mejor calidad, descarga " +
                    "modelos más grandes desde la pantalla de Modelos (se necesita conexión).",
                style = MaterialTheme.typography.bodyMedium,
            )
            Button(onClick = {
                viewModel.complete()
                onFinished()
            }) {
                Text("Empezar")
            }
        }
    }
}

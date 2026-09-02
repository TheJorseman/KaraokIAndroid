package com.karaokei.core.designsystem.component

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.karaokei.feature.pipeline.PipelineStageName
import com.karaokei.feature.pipeline.PipelineState

/**
 * Sticky top-of-screen banner that mirrors the foreground-service
 * pipeline state. Mounted in every screen via the root
 * `KaraokeNavHost` so the user always sees the current progress.
 *
 * Three visual states:
 *  - **active** — animated expand with full Material 3 progress
 *    card: stage icon, label, percent, cancel button.
 *  - **done** — collapsed to a 4 dp success stripe with a check mark.
 *  - **idle** — hidden; the bar only takes vertical space when
 *    there's something to show.
 *
 * The component is a real determinate bar: the inner
 * [LinearProgressIndicator] is fed `state.progress` so the user sees
 * the percentage move during separation, transcription and
 * alignment.
 */
@Composable
fun PipelineProgressBanner(
    state: PipelineState,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        AnimatedVisibility(
            visible = state.isActive(),
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            ActiveCard(state = state, onCancel = onCancel)
        }
        AnimatedVisibility(
            visible = state.stage == PipelineStageName.DONE,
            enter = expandVertically(),
            exit = shrinkVertically(),
        ) {
            DoneStripe()
        }
        // Always-on 4 dp top accent stripe while the pipeline is in
        // any non-idle state. Acts as a clear "something is happening"
        // cue even when the card is collapsed.
        if (state.stage != PipelineStageName.IDLE) {
            LinearProgressIndicator(
                progress = { (state.progress.coerceIn(0, 100)) / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                trackColor = when (state.stage) {
                    PipelineStageName.ERROR -> MaterialTheme.colorScheme.errorContainer
                    PipelineStageName.CANCELLED -> MaterialTheme.colorScheme.surfaceVariant
                    else -> MaterialTheme.colorScheme.primaryContainer
                },
                color = when (state.stage) {
                    PipelineStageName.ERROR -> MaterialTheme.colorScheme.error
                    PipelineStageName.CANCELLED -> MaterialTheme.colorScheme.outline
                    else -> MaterialTheme.colorScheme.primary
                },
            )
        }
    }
}

@Composable
private fun ActiveCard(state: PipelineState, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                Icon(
                    imageVector = stageIcon(state.stage),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp).padding(end = 8.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stageLabel(state),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Etapa ${state.stage.ordinal + 1} / ${PipelineStageName.values().size - 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Text(
                text = "${state.progress.coerceIn(0, 100)}%",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            IconButton(onClick = onCancel) {
                Icon(
                    imageVector = Icons.Filled.Cancel,
                    contentDescription = "Cancelar pipeline",
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        }
        LinearProgressIndicator(
            progress = { (state.progress.coerceIn(0, 100)) / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .height(8.dp),
            trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

@Composable
private fun DoneStripe() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(16.dp).padding(end = 6.dp),
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
        )
        Text(
            text = "Listo",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onTertiaryContainer,
        )
    }
}

private fun PipelineState.isActive(): Boolean =
    stage != PipelineStageName.IDLE &&
        stage != PipelineStageName.DONE &&
        stage != PipelineStageName.ERROR &&
        stage != PipelineStageName.CANCELLED

private fun stageIcon(stage: PipelineStageName) = when (stage) {
    PipelineStageName.IDLE -> Icons.Filled.HourglassEmpty
    PipelineStageName.SEPARATION -> Icons.Filled.GraphicEq
    PipelineStageName.TRANSCRIBING -> Icons.Filled.GraphicEq
    PipelineStageName.ALIGNING -> Icons.Filled.GraphicEq
    PipelineStageName.DONE -> Icons.Filled.CheckCircle
    PipelineStageName.ERROR -> Icons.Filled.Error
    PipelineStageName.CANCELLED -> Icons.Filled.Cancel
}

private fun stageLabel(state: PipelineState): String = when (state.stage) {
    PipelineStageName.IDLE -> "En cola"
    PipelineStageName.SEPARATION ->
        if (state.testFixture) "Procesando canción de prueba (separación)"
        else "Separando voz"
    PipelineStageName.TRANSCRIBING ->
        if (state.testFixture) "Transcribiendo (demo)"
        else "Transcribiendo"
    PipelineStageName.ALIGNING ->
        if (state.testFixture) "Alineando (demo)"
        else "Alineando letra"
    PipelineStageName.DONE -> "Listo"
    PipelineStageName.ERROR -> "Error"
    PipelineStageName.CANCELLED -> "Cancelado"
}

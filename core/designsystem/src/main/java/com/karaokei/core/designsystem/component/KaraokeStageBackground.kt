package com.karaokei.core.designsystem.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Box
import com.karaokei.core.designsystem.theme.KaraokePalette

@Composable
fun KaraokeStageBackground(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable () -> Unit = {},
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        KaraokePalette.StageBackgroundLight,
                        KaraokePalette.StageBackgroundDark,
                    ),
                ),
            ),
        contentAlignment = contentAlignment,
    ) {
        content()
    }
}

@Composable
fun Placeholder(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White,
        modifier = modifier,
    )
}

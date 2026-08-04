package com.karaokei.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.karaokei.android.navigation.KaraokeNavHost
import com.karaokei.core.designsystem.theme.KaraokeTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            KaraokeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    KaraokeNavHost()
                }
            }
        }
    }
}

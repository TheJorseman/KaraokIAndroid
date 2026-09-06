package com.karaokei.android

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.karaokei.android.debug.DebugPipelineTrigger
import com.karaokei.android.navigation.KaraokeNavHost
import com.karaokei.core.designsystem.theme.KaraokeTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var debugPipelineTrigger: DebugPipelineTrigger

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handleDebugIntents(intent)
        setContent {
            KaraokeTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    KaraokeNavHost()
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Re-dispatch debug extras when the activity is brought back
        // to the foreground via `adb shell am start --es ...`. Without
        // this hook, intents delivered to an already-running activity
        // are silently dropped.
        setIntent(intent)
        handleDebugIntents(intent)
    }

    private fun handleDebugIntents(source: Intent?) {
        if (source == null) return
        debugPipelineTrigger.handleFileCopy(source.getStringExtra(DebugPipelineTrigger.EXTRA_SEED_PATH))
        debugPipelineTrigger.handle(source.getStringExtra(DebugPipelineTrigger.EXTRA_SEED_URI))
        debugPipelineTrigger.handleSeedModelExtra(
            source.getStringExtra(DebugPipelineTrigger.EXTRA_SEED_MODEL_ID),
            source.getStringExtra(DebugPipelineTrigger.EXTRA_SEED_MODEL_PATH),
        )
        debugPipelineTrigger.handleSetTier(source.getStringExtra(DebugPipelineTrigger.EXTRA_SET_TIER))
        debugPipelineTrigger.handleSetBackend(source.getStringExtra(DebugPipelineTrigger.EXTRA_SET_BACKEND))
        debugPipelineTrigger.handleSetAutoStart(source.getStringExtra(DebugPipelineTrigger.EXTRA_SET_AUTO_START))
        debugPipelineTrigger.handleSetLanguage(source.getStringExtra(DebugPipelineTrigger.EXTRA_SET_LANGUAGE))
    }
}

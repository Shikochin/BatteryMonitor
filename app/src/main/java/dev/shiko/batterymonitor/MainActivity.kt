package dev.shiko.batterymonitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.shiko.batterymonitor.ui.PowerStatsScreen
import dev.shiko.batterymonitor.ui.theme.BatteryMonitorTheme

class MainActivity : ComponentActivity() {

    private val viewModel: PowerStatsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            BatteryMonitorTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                PowerStatsScreen(
                    state = state,
                    onBack = { finish() },
                    onClearData = { viewModel.clearSessionData() },
                    onRefreshScenarios = { viewModel.refreshScenarios() },
                    onRestartDaemon = { viewModel.restartDaemon() },
                    // The screen owns neither the paused flag nor the call that flips it: it only
                    // reports the desired direction, and the repository settles the sampler.
                    onToggleRecordingPause = { viewModel.setRecordingPaused(!state.recordingPaused) },
                    onRequestShizuku = { viewModel.requestShizukuPermission() },
                    onDeleteHistory = { startedAtMillis -> viewModel.deleteSessionRecord(startedAtMillis) },
                    onClearHistory = { viewModel.clearSessionHistory() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // A sampler that died while the app was frozen must be restarted before the first frame
        // that shows the footer, and the footer must reflect a fresh probe — not the last cached
        // "running" from before the app went to the background.
        viewModel.onAppForeground()
    }

}

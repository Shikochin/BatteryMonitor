package dev.shiko.batterymonitor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.shiko.batterymonitor.ui.PowerStatsScreen
import dev.shiko.batterymonitor.ui.theme.BatteryMonitorTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            BatteryMonitorTheme {
                val viewModel: PowerStatsViewModel = viewModel()
                val state by viewModel.state.collectAsStateWithLifecycle()
                PowerStatsScreen(
                    state = state,
                    onBack = { finish() },
                    onClearData = { viewModel.clearSessionData() },
                    onRefreshScenarios = { viewModel.refreshScenarios() },
                    onRequestShizuku = { viewModel.requestShizukuPermission() },
                    onDeleteHistory = { startedAtMillis -> viewModel.deleteSessionRecord(startedAtMillis) },
                    onClearHistory = { viewModel.clearSessionHistory() },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Permissions may have been granted while we were away (Settings, Shizuku dialog).
        // The repository re-checks them on its next sampling tick, which updates the StateFlow
        // this screen collects, so there is nothing to do here.
    }

}

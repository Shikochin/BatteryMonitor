package dev.shiko.batterymonitor

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import dev.shiko.batterymonitor.data.PowerStatsRepository
import dev.shiko.batterymonitor.model.PowerUiState
import kotlinx.coroutines.flow.StateFlow

/**
 * Thin lifecycle bridge between the screen and [PowerStatsRepository].
 *
 * All aggregation lives in the repository so the data pipeline stays testable without Android UI.
 */
class PowerStatsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = PowerStatsRepository(application)

    val state: StateFlow<PowerUiState> = repository.state

    init {
        repository.startMonitoring()
    }

    fun refreshScenarios() = repository.refreshScenarios()

    /** The activity is visible again: re-check Shizuku and verify the sampler for real. */
    fun onAppForeground() = repository.onAppForeground()

    /** Kills any running root sampler and starts a fresh one. */
    fun restartDaemon() = repository.restartDaemon()

    fun clearSessionData() = repository.clearSessionData()

    /** Asks the Shizuku service for shell access so we can read `dumpsys batterystats`. */
    fun requestShizukuPermission() = repository.requestShizukuPermission()

    /** Removes one finished session from the history list. */
    fun deleteSessionRecord(startedAtMillis: Long) = repository.deleteSessionRecord(startedAtMillis)

    /** Wipes the whole history list. */
    fun clearSessionHistory() = repository.clearSessionHistory()

    override fun onCleared() {
        repository.stopMonitoring()
        super.onCleared()
    }
}

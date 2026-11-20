package com.galaxy.autofire

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val monitor: MonitorSnapshot = MonitorSnapshot(),
    val config: DetectionConfig = DetectionConfig(),
    val timings: Timings = Timings(),
)

@OptIn(FlowPreview::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = SettingsRepository(app)
    private val config = MutableStateFlow(DetectionConfig())
    private val timings = MutableStateFlow(Timings())

    val ui: StateFlow<UiState> =
        combine(MonitorBus.state, config, timings) { m, c, t -> UiState(m, c, t) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState())

    init {
        viewModelScope.launch {
            config.value = repo.config.first()
            timings.value = repo.timings.first()
            // Guardado automático con debounce: sin botones "Guardar".
            launch { config.debounce(400).collect { repo.saveConfig(it) } }
            launch { timings.debounce(400).collect { repo.saveTimings(it) } }
        }
    }

    fun updateConfig(transform: (DetectionConfig) -> DetectionConfig) =
        config.update { transform(it).sanitized() }

    fun updateTiming(index: Int, value: Int) =
        timings.update { it.withValue(index, value) }

    fun resetDefaults() {
        config.value = DetectionConfig()
        timings.value = Timings()
    }
}

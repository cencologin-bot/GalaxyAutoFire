package com.galaxy.autofire

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class MonitorSnapshot(
    val running: Boolean = false,
    /** null = aún sin datos (o monitoreo detenido). */
    val state: LightState? = null,
    val greenRatio: Float = 0f,
    val redRatio: Float = 0f,
    val samples: Int = 0,
    val fps: Int = 0,
    val error: String? = null,
)

/** Canal en memoria servicio → UI (mismo proceso). Sustituye a las escrituras por fotograma en SharedPreferences. */
object MonitorBus {
    private val _state = MutableStateFlow(MonitorSnapshot())
    val state: StateFlow<MonitorSnapshot> = _state.asStateFlow()

    fun update(transform: (MonitorSnapshot) -> MonitorSnapshot) = _state.update(transform)
}

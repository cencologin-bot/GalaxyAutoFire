@file:OptIn(ExperimentalMaterial3Api::class)

package com.galaxy.autofire.ui

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.galaxy.autofire.DetectionConfig
import com.galaxy.autofire.Timings
import com.galaxy.autofire.UiState
import kotlin.math.roundToInt

private val tabs = listOf("Monitor", "Región", "Ajustes")

@Composable
fun GalaxyApp(
    ui: UiState,
    onToggle: () -> Unit,
    onConfig: ((DetectionConfig) -> DetectionConfig) -> Unit,
    onTiming: (Int, Int) -> Unit,
    onReset: () -> Unit,
) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    Scaffold(
        containerColor = Palette.Ink,
        bottomBar = {
            NavigationBar(containerColor = Palette.Panel) {
                tabs.forEachIndexed { i, label ->
                    NavigationBarItem(
                        selected = tab == i,
                        onClick = { tab = i },
                        icon = { NavGlyph(i, if (tab == i) Palette.Blue else Palette.Muted) },
                        label = { Text(label) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedTextColor = Palette.Blue,
                            unselectedTextColor = Palette.Muted,
                            indicatorColor = Palette.PanelHigh,
                        ),
                    )
                }
            }
        },
    ) { padding ->
        Crossfade(tab, modifier = Modifier.padding(padding).fillMaxSize(), label = "tabs") { current ->
            when (current) {
                0 -> MonitorScreen(ui, onToggle)
                1 -> RegionScreen(ui.config, onConfig)
                else -> SettingsScreen(ui, onConfig, onTiming, onReset)
            }
        }
    }
}

@Composable
private fun ScreenColumn(content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content,
    )
}

@Composable
private fun Header(title: String, subtitle: String, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(subtitle, color = Palette.Muted, style = MaterialTheme.typography.bodySmall)
        }
        trailing()
    }
}

@Composable
private fun MonitorScreen(ui: UiState, onToggle: () -> Unit) {
    val m = ui.monitor
    ScreenColumn {
        Header("Galaxy Auto Fire", "Monitor visual de luz") { StatusPill(m.running) }
        SignalOrb(
            state = m.state,
            running = m.running,
            modifier = Modifier.fillMaxWidth(0.76f).align(Alignment.CenterHorizontally),
        )
        GlassCard {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                RatioMeter("VERDE", m.greenRatio, ui.config.minCoverage, Palette.Green)
                RatioMeter("ROJO", m.redRatio, ui.config.minCoverage, Palette.Red)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    StatBlock("FPS", m.fps.toString())
                    StatBlock("MUESTRAS", m.samples.toString())
                    StatBlock("UMBRAL", "${(ui.config.minCoverage * 100).roundToInt()}%")
                }
            }
        }
        m.error?.let { message ->
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = Palette.Red.copy(alpha = 0.12f),
                border = BorderStroke(1.dp, Palette.Red.copy(alpha = 0.4f)),
            ) {
                Text(message, Modifier.padding(14.dp), color = Palette.Red, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Button(
            onClick = onToggle,
            modifier = Modifier.fillMaxWidth().height(58.dp),
            shape = RoundedCornerShape(20.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (m.running) Palette.Red else Palette.Blue,
                contentColor = Palette.Ink,
            ),
        ) {
            Text(if (m.running) "Detener monitoreo" else "Iniciar monitoreo", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            "Solo observa la pantalla que compartes. No ejecuta toques ni acciones sobre el juego.",
            color = Palette.Muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun RegionScreen(config: DetectionConfig, onConfig: ((DetectionConfig) -> DetectionConfig) -> Unit) {
    ScreenColumn {
        Header("Área de análisis", "Define dónde está la luz del minijuego")
        GlassCard {
            Row(Modifier.padding(18.dp), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.CenterVertically) {
                RegionPreview(config, Modifier.width(120.dp))
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatBlockStart("ANCHO", "${((config.right - config.left) * 100).roundToInt()}%")
                    StatBlockStart("ALTO", "${((config.bottom - config.top) * 100).roundToInt()}%")
                }
            }
        }
        GlassCard {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RangeRow("Horizontal", config.left, config.right) { a, b -> onConfig { it.copy(left = a, right = b) } }
                RangeRow("Vertical", config.top, config.bottom) { a, b -> onConfig { it.copy(top = a, bottom = b) } }
            }
        }
        Text(
            "Cuanto más pequeña sea el área, menos falsos positivos y menos consumo.",
            color = Palette.Muted,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SettingsScreen(
    ui: UiState,
    onConfig: ((DetectionConfig) -> DetectionConfig) -> Unit,
    onTiming: (Int, Int) -> Unit,
    onReset: () -> Unit,
) {
    val c = ui.config
    ScreenColumn {
        Header("Ajustes", "Se guardan automáticamente")
        GlassCard {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("SENSIBILIDAD", color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
                LabeledSlider("Saturación mínima", "${(c.minSaturation * 100).roundToInt()}%", c.minSaturation, 0f..1f) { v ->
                    onConfig { it.copy(minSaturation = v) }
                }
                LabeledSlider("Brillo mínimo", "${(c.minValue * 100).roundToInt()}%", c.minValue, 0f..1f) { v ->
                    onConfig { it.copy(minValue = v) }
                }
                LabeledSlider("Cobertura mínima", "${(c.minCoverage * 100).roundToInt()}%", c.minCoverage, 0.01f..METER_MAX) { v ->
                    onConfig { it.copy(minCoverage = v) }
                }
            }
        }
        GlassCard {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("INTERVALOS", color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
                Text(
                    "Reservados para una versión futura. Hoy solo se guardan; no disparan acciones.",
                    color = Palette.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
                ui.timings.values.forEachIndexed { i, ms ->
                    LabeledSlider("Intervalo ${i + 1}", "$ms ms", ms.toFloat(), 0f..Timings.MAX_MS.toFloat(), steps = Timings.MAX_MS / 10 - 1) { v ->
                        onTiming(i, (v / 10f).roundToInt() * 10)
                    }
                }
            }
        }
        OutlinedButton(
            onClick = onReset,
            modifier = Modifier.fillMaxWidth().height(50.dp),
            shape = RoundedCornerShape(16.dp),
            border = BorderStroke(1.dp, Palette.Line),
        ) { Text("Restablecer valores", color = Palette.Text) }
    }
}

@Composable
private fun StatBlockStart(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineMedium)
        Text(label, color = Palette.Muted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun sliderColors() = SliderDefaults.colors(
    thumbColor = Palette.Blue,
    activeTrackColor = Palette.Blue,
    inactiveTrackColor = Palette.PanelHigh,
    activeTickColor = Palette.Blue.copy(alpha = 0f),
    inactiveTickColor = Palette.Blue.copy(alpha = 0f),
)

@Composable
private fun LabeledSlider(
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int = 0,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title)
            Text(valueText, color = Palette.Blue, style = MaterialTheme.typography.titleMedium)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range, steps = steps, colors = sliderColors())
    }
}

@Composable
private fun RangeRow(title: String, low: Float, high: Float, onChange: (Float, Float) -> Unit) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title)
            Text("${(low * 100).roundToInt()}% – ${(high * 100).roundToInt()}%", color = Palette.Blue, style = MaterialTheme.typography.titleMedium)
        }
        RangeSlider(
            value = low..high,
            onValueChange = { onChange(it.start, it.endInclusive) },
            valueRange = 0f..1f,
            colors = sliderColors(),
        )
    }
}

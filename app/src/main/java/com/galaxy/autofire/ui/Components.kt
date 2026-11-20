package com.galaxy.autofire.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.galaxy.autofire.DetectionConfig
import com.galaxy.autofire.LightState
import kotlin.math.roundToInt

/** Escala visual de los medidores: 0..50 % de cobertura (coincide con el rango del ajuste). */
const val METER_MAX = 0.5f

@Composable
fun GlassCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = Palette.Panel,
        border = BorderStroke(1.dp, Palette.Line),
    ) { Column(content = content) }
}

@Composable
fun SignalOrb(state: LightState?, running: Boolean, modifier: Modifier = Modifier) {
    val target = when {
        !running -> Palette.Idle
        state == LightState.GREEN -> Palette.Green
        state == LightState.RED -> Palette.Red
        else -> Palette.Blue
    }
    val color by animateColorAsState(target, tween(450), label = "orbColor")
    val transition = rememberInfiniteTransition(label = "orb")
    val sweep by transition.animateFloat(
        0f, 360f, infiniteRepeatable(tween(3200, easing = LinearEasing)), label = "sweep",
    )
    val pulse by transition.animateFloat(
        0.9f, 1f, infiniteRepeatable(tween(1500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse",
    )
    val title = when {
        !running -> "EN PAUSA"
        state == null -> "ANALIZANDO"
        state == LightState.GREEN -> "VERDE"
        state == LightState.RED -> "ROJO"
        else -> "SIN SEÑAL"
    }

    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = center
            val r = size.minDimension / 2f
            val glowScale = if (running) pulse else 0.9f
            drawCircle(
                Brush.radialGradient(listOf(color.copy(alpha = 0.38f), Color.Transparent), center = c, radius = r),
                radius = r * glowScale,
            )
            drawCircle(Palette.Line, radius = r * 0.82f, style = Stroke(2.dp.toPx()))
            drawCircle(Palette.Line, radius = r * 0.62f, style = Stroke(1.dp.toPx()))
            if (running) {
                rotate(sweep, pivot = c) {
                    val arcR = r * 0.82f
                    val topLeft = Offset(c.x - arcR, c.y - arcR)
                    val arcSize = Size(arcR * 2, arcR * 2)
                    drawArc(color.copy(alpha = 0.25f), -150f, 60f, false, topLeft, arcSize,
                        style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
                    drawArc(color, -90f, 50f, false, topLeft, arcSize,
                        style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
                }
            }
            drawCircle(
                Brush.radialGradient(listOf(color.copy(alpha = 0.95f), color.copy(alpha = 0.35f)), center = c, radius = r * 0.5f),
                radius = r * 0.5f,
            )
        }
        Text(
            title,
            color = Palette.Ink,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.ExtraBold,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun RatioMeter(label: String, value: Float, threshold: Float, color: Color, modifier: Modifier = Modifier) {
    val animated by animateFloatAsState((value / METER_MAX).coerceIn(0f, 1f), tween(250), label = "meter")
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, color = Palette.Muted, style = MaterialTheme.typography.labelMedium)
            Text("${(value * 100).roundToInt()}%", fontWeight = FontWeight.SemiBold)
        }
        Canvas(Modifier.fillMaxWidth().height(10.dp)) {
            val radius = CornerRadius(size.height / 2)
            drawRoundRect(Palette.PanelHigh, cornerRadius = radius)
            if (animated > 0f) {
                drawRoundRect(color, size = Size(size.width * animated, size.height), cornerRadius = radius)
            }
            val x = size.width * (threshold / METER_MAX).coerceIn(0f, 1f)
            drawLine(Palette.Text, Offset(x, -2.dp.toPx()), Offset(x, size.height + 2.dp.toPx()), 2.dp.toPx())
        }
    }
}

@Composable
fun StatBlock(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleMedium)
        Text(label, color = Palette.Muted, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
fun StatusPill(running: Boolean) {
    val transition = rememberInfiniteTransition(label = "pill")
    val alpha by transition.animateFloat(
        0.35f, 1f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "dot",
    )
    val dot = if (running) Palette.Green else Palette.Idle
    Surface(shape = CircleShape, color = Palette.PanelHigh, border = BorderStroke(1.dp, Palette.Line)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).padding(0.dp)) {
                Canvas(Modifier.fillMaxSize()) { drawCircle(dot.copy(alpha = if (running) alpha else 1f)) }
            }
            Text(
                if (running) "  EN VIVO" else "  INACTIVO",
                style = MaterialTheme.typography.labelSmall,
                color = if (running) Palette.Green else Palette.Muted,
            )
        }
    }
}

@Composable
fun RegionPreview(config: DetectionConfig, modifier: Modifier = Modifier) {
    Canvas(modifier.aspectRatio(9f / 19.5f)) {
        val corner = CornerRadius(26.dp.toPx())
        drawRoundRect(Palette.PanelHigh, cornerRadius = corner)
        drawRoundRect(Palette.Line, cornerRadius = corner, style = Stroke(2.dp.toPx()))
        drawRoundRect(
            Palette.Line, topLeft = Offset(size.width * 0.38f, size.height * 0.02f),
            size = Size(size.width * 0.24f, 5.dp.toPx()), cornerRadius = CornerRadius(3.dp.toPx()),
        )
        val topLeft = Offset(size.width * config.left, size.height * config.top)
        val area = Size(size.width * (config.right - config.left), size.height * (config.bottom - config.top))
        drawRect(Palette.Blue.copy(alpha = 0.18f), topLeft, area)
        drawRect(
            Palette.Blue, topLeft, area,
            style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(16f, 10f))),
        )
    }
}

/** Iconos de navegación dibujados a mano: cero dependencias de iconos. */
@Composable
fun NavGlyph(index: Int, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(24.dp)) {
        val s = size.minDimension
        val w = 2.dp.toPx()
        val stroke = Stroke(width = w, cap = StrokeCap.Round)
        when (index) {
            0 -> {
                drawCircle(tint, radius = s * 0.42f, style = stroke)
                drawCircle(tint, radius = s * 0.15f)
            }
            1 -> {
                val m = s * 0.14f
                val len = s * 0.28f
                fun corner(x: Float, y: Float, dx: Float, dy: Float) {
                    drawLine(tint, Offset(x, y), Offset(x + dx * len, y), w, StrokeCap.Round)
                    drawLine(tint, Offset(x, y), Offset(x, y + dy * len), w, StrokeCap.Round)
                }
                corner(m, m, 1f, 1f)
                corner(s - m, m, -1f, 1f)
                corner(m, s - m, 1f, -1f)
                corner(s - m, s - m, -1f, -1f)
                drawCircle(tint, radius = s * 0.07f)
            }
            else -> {
                val knobs = listOf(0.65f, 0.35f, 0.6f)
                listOf(0.25f, 0.5f, 0.75f).forEachIndexed { i, y ->
                    drawLine(tint, Offset(s * 0.12f, s * y), Offset(s * 0.88f, s * y), w, StrokeCap.Round)
                    drawCircle(tint, radius = s * 0.09f, center = Offset(s * knobs[i], s * y))
                }
            }
        }
    }
}

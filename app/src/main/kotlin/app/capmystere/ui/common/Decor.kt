package app.capmystere.ui.common

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.capmystere.theme.Cap
import app.capmystere.theme.CapColors
import app.capmystere.theme.PathSpec
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Fond d'écran thématisé : couleur, motif d'ambiance (vagues, stores…) et grain. */
@Composable
fun ThemedBackground(modifier: Modifier = Modifier, animated: Boolean = true, content: @Composable BoxScope.() -> Unit) {
    val colors = Cap.colors
    val decor = Cap.spec.decor
    val still = LocalInspectionMode.current || !animated
    val phase = if (still || decor.background != "waves") 0f else {
        val t = rememberInfiniteTransition(label = "decor")
        val v by t.animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(14_000, easing = LinearEasing), RepeatMode.Restart), label = "phase")
        v
    }
    Box(
        modifier
            .background(colors.background)
            .drawWithCache {
                val grain = grainPoints(size, decor.grain)
                onDrawBehind {
                    when (decor.background) {
                        "waves" -> drawWaves(colors, phase)
                        "blinds" -> drawBlinds(colors)
                    }
                    if (grain.isNotEmpty()) {
                        drawPoints(grain, PointMode.Points, colors.text.copy(alpha = 0.10f), strokeWidth = 1.5.dp.toPx() / 1.5f, cap = StrokeCap.Round)
                    }
                }
            },
        content = content,
    )
}

private fun grainPoints(size: Size, amount: Float): List<Offset> {
    if (amount <= 0f || size.width <= 0f) return emptyList()
    val rnd = Random(7)
    val count = (size.width * size.height / 900f * amount * 10f).toInt().coerceAtMost(6000)
    return List(count) { Offset(rnd.nextFloat() * size.width, rnd.nextFloat() * size.height) }
}

/** Houle : quelques lignes de crête dans le bas de l'écran. */
private fun DrawScope.drawWaves(colors: CapColors, phase: Float) {
    val lines = 6
    for (i in 0 until lines) {
        val y0 = size.height * (0.62f + i * 0.07f)
        val amp = size.height * (0.008f + i * 0.0025f)
        val wavelength = size.width * (0.55f + i * 0.09f)
        val path = Path()
        var x = -20f
        path.moveTo(x, y0)
        while (x <= size.width + 20f) {
            val y = y0 + amp * sin((x / wavelength) * 2 * PI.toFloat() + phase * (if (i % 2 == 0) 1 else -1) + i * 1.3f)
            path.lineTo(x, y)
            x += 12f
        }
        drawPath(path, colors.primary.copy(alpha = 0.05f + i * 0.018f), style = Stroke(width = 1.2.dp.toPx(), cap = StrokeCap.Round))
    }
}

/** Lumière de réverbère filtrée par des stores vénitiens. */
private fun DrawScope.drawBlinds(colors: CapColors) {
    val light = if (colors.isNight) Color.White else Color.Black
    rotate(-18f, pivot = Offset(size.width, 0f)) {
        val band = size.height * 0.034f
        for (i in 0 until 9) {
            val top = size.height * 0.05f + i * band * 2.1f
            drawRect(
                brush = Brush.horizontalGradient(
                    0f to Color.Transparent, 0.35f to light.copy(alpha = if (colors.isNight) 0.055f else 0.04f), 1f to light.copy(alpha = 0.01f),
                    startX = -size.width * 0.2f, endX = size.width * 1.3f,
                ),
                topLeft = Offset(-size.width * 0.3f, top),
                size = Size(size.width * 1.8f, band),
            )
        }
    }
    // Vignette : les coins se perdent dans l'ombre.
    drawRect(Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = if (colors.isNight) 0.45f else 0.10f)), center = center, radius = size.maxDimension * 0.75f))
}

/** Forme des cartes selon le thème. */
@Composable
fun cardShape(): Shape {
    val shape = Cap.spec.shape
    val density = LocalDensity.current
    return remember(shape, density) {
        when (shape.card) {
            "parchment" -> parchmentShape(with(density) { 2.2.dp.toPx() }, with(density) { 22.dp.toPx() })
            "cut" -> CutCornerShape(topStart = 0.dp, topEnd = 14.dp, bottomEnd = 0.dp, bottomStart = 14.dp)
            else -> RoundedCornerShape(shape.corner.dp)
        }
    }
}

@Composable
fun buttonShape(): Shape {
    val shape = Cap.spec.shape
    return remember(shape) {
        when (shape.card) {
            "cut" -> CutCornerShape(topStart = 0.dp, topEnd = 10.dp, bottomEnd = 0.dp, bottomStart = 10.dp)
            else -> RoundedCornerShape(shape.corner.dp)
        }
    }
}

/** Bords légèrement déchirés, comme un vieux papier. Déterministe : pas de scintillement. */
private fun parchmentShape(amplitude: Float, step: Float): Shape = GenericShape { size, _ ->
    val rnd = Random(42)
    fun j() = (rnd.nextFloat() - 0.5f) * 2f * amplitude
    val inset = amplitude
    moveTo(inset, inset)
    var x = inset
    while (x < size.width - inset) { x = (x + step).coerceAtMost(size.width - inset); lineTo(x, inset + j()) }
    var y = inset
    while (y < size.height - inset) { y = (y + step).coerceAtMost(size.height - inset); lineTo(size.width - inset + j(), y) }
    x = size.width - inset
    while (x > inset) { x = (x - step).coerceAtLeast(inset); lineTo(x, size.height - inset + j()) }
    y = size.height - inset
    while (y > inset) { y = (y - step).coerceAtLeast(inset); lineTo(inset + j(), y) }
    close()
}

/** Illustration vectorielle du thème (chemins SVG décrits dans theme.json, boîte 100×100). */
@Composable
fun Illustration(name: String, modifier: Modifier = Modifier, size: Dp = 96.dp, tint: Color? = null) {
    val specs = Cap.spec.illustrations[name].orEmpty()
    val colors = Cap.colors
    val parsed = remember(specs) { specs.map { it to runCatching { PathParser().parsePathString(it.d).toPath() }.getOrDefault(Path()) } }
    androidx.compose.foundation.Canvas(modifier.size(size)) {
        val scale = this.size.minDimension / 100f
        drawContext.transform.scale(scale, scale, Offset.Zero)
        for ((spec, path) in parsed) drawSpec(spec, path, tint ?: colors.token(spec.color))
    }
}

private fun DrawScope.drawSpec(spec: PathSpec, path: Path, color: Color) {
    val c = color.copy(alpha = color.alpha * spec.alpha)
    if (spec.stroke > 0f) drawPath(path, c, style = Stroke(spec.stroke, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round))
    else drawPath(path, c)
}


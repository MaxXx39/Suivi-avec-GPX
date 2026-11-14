package app.capmystere.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.capmystere.core.guidance.ManeuverType
import app.capmystere.theme.Cap
import kotlin.math.cos
import kotlin.math.sin

/** Pictogramme de manœuvre, dessiné dans une boîte 100×100. Angle : 0 = tout droit, + = droite. */
@Composable
fun ManeuverIcon(type: ManeuverType?, modifier: Modifier = Modifier, exit: Int = 0, size: Dp = 96.dp, color: Color = Cap.colors.primary) {
    if (type == ManeuverType.ARRIVE) {
        Illustration("stage", modifier, size)
        return
    }
    val sharp = Cap.spec.shape.card == "cut"
    val muted = Cap.colors.textMuted
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val s = this.size.minDimension / 100f
            drawContext.transform.scale(s, s, Offset.Zero)
            val stroke = Stroke(11f, cap = if (sharp) StrokeCap.Butt else StrokeCap.Round, join = if (sharp) StrokeJoin.Miter else StrokeJoin.Round)
            when (type) {
                ManeuverType.ROUNDABOUT -> roundabout(exit, color, muted, stroke)
                ManeuverType.UTURN -> uturn(color, stroke)
                else -> turn(angleOf(type), color, stroke)
            }
        }
        if (type == ManeuverType.ROUNDABOUT && exit > 0) {
            Text(exit.toString(), color = color, style = Cap.type.distance.copy(fontSize = (size.value * 0.26f).sp), modifier = Modifier.align(Alignment.Center))
        }
    }
}

private fun angleOf(type: ManeuverType?): Float = when (type) {
    ManeuverType.SLIGHT_RIGHT, ManeuverType.KEEP_RIGHT, ManeuverType.EXIT_RIGHT -> 40f
    ManeuverType.RIGHT -> 90f
    ManeuverType.SHARP_RIGHT -> 135f
    ManeuverType.SLIGHT_LEFT, ManeuverType.KEEP_LEFT, ManeuverType.EXIT_LEFT -> -40f
    ManeuverType.LEFT -> -90f
    ManeuverType.SHARP_LEFT -> -135f
    else -> 0f
}

private fun dir(angleDeg: Float): Offset {
    val a = Math.toRadians(angleDeg.toDouble())
    return Offset(sin(a).toFloat(), -cos(a).toFloat())
}

private fun DrawScope.arrowHead(tip: Offset, angleDeg: Float, color: Color, size: Float = 22f) {
    val d = dir(angleDeg)
    val n = Offset(-d.y, d.x)
    val base = tip - d * size
    val path = Path().apply {
        moveTo(tip.x + d.x * 6f, tip.y + d.y * 6f)
        lineTo(base.x + n.x * size * 0.75f, base.y + n.y * size * 0.75f)
        lineTo(base.x - n.x * size * 0.75f, base.y - n.y * size * 0.75f)
        close()
    }
    drawPath(path, color)
}

private fun DrawScope.turn(angle: Float, color: Color, stroke: Stroke) {
    // On décale la tige à l'opposé du virage pour que le dessin reste centré.
    val shift = -sin(Math.toRadians(angle.toDouble())).toFloat() * 16f
    val bottom = Offset(50f + shift, 92f)
    if (angle == 0f) {
        val tip = Offset(50f, 30f)
        drawLine(color, bottom, tip, stroke.width, stroke.cap)
        arrowHead(Offset(50f, 14f), 0f, color)
        return
    }
    val elbow = Offset(50f + shift, if (kotlin.math.abs(angle) > 100f) 44f else 54f)
    val d = dir(angle)
    val end = elbow + d * 30f
    val path = Path().apply { moveTo(bottom.x, bottom.y); lineTo(elbow.x, elbow.y); lineTo(end.x, end.y) }
    drawPath(path, color, style = stroke)
    arrowHead(end + d * 14f, angle, color)
}

private fun DrawScope.uturn(color: Color, stroke: Stroke) {
    val path = Path().apply {
        moveTo(68f, 92f)
        lineTo(68f, 42f)
        arcTo(Rect(Offset(32f, 24f), Size(36f, 36f)), 0f, -180f, false)
        lineTo(32f, 62f)
    }
    drawPath(path, color, style = stroke)
    arrowHead(Offset(32f, 78f), 180f, color)
}

private fun DrawScope.roundabout(exit: Int, color: Color, muted: Color, stroke: Stroke) {
    val center = Offset(50f, 48f)
    val r = 21f
    drawCircle(muted.copy(alpha = 0.55f), r, center, style = Stroke(7f))
    // Entrée par le bas.
    drawLine(color, Offset(50f, 96f), Offset(50f, center.y + r), stroke.width, stroke.cap)
    // Sortie : 1re à droite, 2e en face, 3e à gauche, au-delà vers l'arrière gauche.
    val angle = when (exit) { 1 -> 90f; 2, 0 -> 0f; 3 -> -90f; else -> -140f }
    // Arc parcouru (sens anti-horaire, conduite à droite) du bas jusqu'à la sortie.
    val sweep = -(180f - angle)
    drawArc(color, startAngle = 90f, sweepAngle = sweep, useCenter = false, topLeft = center - Offset(r, r), size = Size(r * 2, r * 2), style = Stroke(7f, cap = StrokeCap.Butt))
    val d = dir(angle)
    val start = center + d * r
    val end = center + d * (r + 12f)
    drawLine(color, start, end, stroke.width, StrokeCap.Butt)
    arrowHead(end + d * 12f, angle, color, size = 18f)
}

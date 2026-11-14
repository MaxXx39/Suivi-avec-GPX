package app.capmystere.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.capmystere.theme.Cap

/** Petit jeu d'icônes au trait, dessiné maison (boîte 24×24) pour garder une identité cohérente. */
enum class Glyph(val d: String) {
    BACK("M15 5 L8 12 L15 19"),
    CLOSE("M6 6 L18 18 M18 6 L6 18"),
    PLUS("M12 5 L12 19 M5 12 L19 12"),
    CHECK("M5 12.5 L10 17.5 L19 7"),
    UP("M6 14 L12 8 L18 14"),
    DOWN("M6 10 L12 16 L18 10"),
    TRASH("M5 7 L19 7 M9 7 L9 4 L15 4 L15 7 M7 7 L8 20 L16 20 L17 7 M10.5 11 L10.5 16 M13.5 11 L13.5 16"),
    SHARE("M12 15 L12 4 M8 8 L12 4 L16 8 M5 13 L5 20 L19 20 L19 13"),
    PLAY("M8 5 L19 12 L8 19 Z"),
    STOP("M7 7 L17 7 L17 17 L7 17 Z"),
    EYE("M2 12 C5 6 19 6 22 12 C19 18 5 18 2 12 Z M12 9.5 A2.5 2.5 0 1 1 11.99 9.5"),
    VOICE("M4 10 L4 14 L8 14 L13 18 L13 6 L8 10 Z M16 9 C17.5 10.5 17.5 13.5 16 15 M18.5 6.5 C22 10 22 14 18.5 17.5"),
    MUTE("M4 10 L4 14 L8 14 L13 18 L13 6 L8 10 Z M16.5 9.5 L21 14.5 M21 9.5 L16.5 14.5"),
    BOOK("M4 5 C7 4 10 4.5 12 6 C14 4.5 17 4 20 5 L20 19 C17 18 14 18.5 12 20 C10 18.5 7 18 4 19 Z M12 6 L12 20"),
    CAMERA("M4 8 L8 8 L9.5 5.5 L14.5 5.5 L16 8 L20 8 L20 19 L4 19 Z M12 10.5 A3 3 0 1 1 11.99 10.5"),
    QR("M4 4 L10 4 L10 10 L4 10 Z M14 4 L20 4 L20 10 L14 10 Z M4 14 L10 14 L10 20 L4 20 Z M14 14 L14 17 M17 14 L20 14 L20 17 M14 20 L17 20 L17 17 M20 20 L20 20.01"),
    PIN("M12 21 C7 15 5.5 12.5 5.5 9.5 A6.5 6.5 0 0 1 18.5 9.5 C18.5 12.5 17 15 12 21 Z M12 7.5 A2 2 0 1 1 11.99 7.5"),
    SEND("M4 12 L20 4 L14 20 L11.5 13.5 Z M11.5 13.5 L20 4"),
    MIC("M12 4 A2.5 2.5 0 0 1 14.5 6.5 L14.5 11 A2.5 2.5 0 0 1 9.5 11 L9.5 6.5 A2.5 2.5 0 0 1 12 4 Z M6.5 11 A5.5 5.5 0 0 0 17.5 11 M12 16.5 L12 20"),
    IMAGE("M4 5 L20 5 L20 19 L4 19 Z M4 16 L9 11 L13 15 L16 12 L20 16 M15 8.5 A1 1 0 1 1 14.99 8.5"),
    SEARCH("M10.5 4 A6.5 6.5 0 1 1 10.49 4 M15.5 15.5 L20 20"),
    SETTINGS("M5 7 L19 7 M5 12 L19 12 M5 17 L19 17 M9 5 L9 9 M15 10 L15 14 M8 15 L8 19"),
    LOCK("M7 11 L17 11 L17 20 L7 20 Z M9 11 L9 8 A3 3 0 0 1 15 8 L15 11"),
    SKIP("M6 6 L13 12 L6 18 M13 6 L13 18 M17 6 L17 18"),
    EDIT("M5 19 L5 15.5 L16 4.5 L19.5 8 L8.5 19 Z M14 6.5 L17.5 10"),
    LIVE("M12 10.5 A1.5 1.5 0 1 1 11.99 10.5 M8 8 C6 10 6 14 8 16 M16 8 C18 10 18 14 16 16 M5.5 5.5 C2 9 2 15 5.5 18.5 M18.5 5.5 C22 9 22 15 18.5 18.5"),
    BULB("M9 17 L15 17 M10 20 L14 20 M8.5 14 C6 12 6 8 8 6 A5.6 5.6 0 0 1 16 6 C18 8 18 12 15.5 14 L15 17 L9 17 Z"),
    ;
}

@Composable
fun GlyphIcon(glyph: Glyph, modifier: Modifier = Modifier, size: Dp = 24.dp, tint: Color = Cap.colors.text, strokeWidth: Float = 1.8f) {
    val path = remember(glyph) { runCatching { PathParser().parsePathString(glyph.d).toPath() }.getOrDefault(Path()) }
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension / 24f
        drawContext.transform.scale(s, s, Offset.Zero)
        drawPath(path, tint, style = Stroke(strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun IconAction(glyph: Glyph, onClick: () -> Unit, modifier: Modifier = Modifier, contentDescription: String, tint: Color = Cap.colors.text, enabled: Boolean = true) {
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        GlyphIcon(glyph, tint = if (enabled) tint else tint.copy(alpha = 0.35f))
    }
}

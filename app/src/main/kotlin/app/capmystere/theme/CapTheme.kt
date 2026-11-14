package app.capmystere.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import app.capmystere.core.model.Audience

fun parseColor(hex: String): Color = Color(android.graphics.Color.parseColor(hex))

@Immutable
data class CapColors(
    val background: Color, val surface: Color, val surfaceHigh: Color,
    val primary: Color, val onPrimary: Color, val secondary: Color,
    val text: Color, val textMuted: Color, val outline: Color,
    val success: Color, val danger: Color,
    val route: Color, val routeCasing: Color, val puck: Color,
    val isNight: Boolean,
) {
    fun token(name: String): Color = when (name) {
        "background" -> background; "surface" -> surface; "surfaceHigh" -> surfaceHigh
        "primary" -> primary; "onPrimary" -> onPrimary; "secondary" -> secondary
        "text" -> text; "textMuted" -> textMuted; "outline" -> outline
        "success" -> success; "danger" -> danger
        else -> primary
    }
}

fun PaletteSpec.toColors(isNight: Boolean) = CapColors(
    parseColor(background), parseColor(surface), parseColor(surfaceHigh),
    parseColor(primary), parseColor(onPrimary), parseColor(secondary),
    parseColor(text), parseColor(textMuted), parseColor(outline),
    parseColor(success), parseColor(danger),
    parseColor(route), parseColor(routeCasing), parseColor(puck), isNight,
)

/** Échelle typographique expressive : le titrage et l'instruction ont chacun leur police. */
@Immutable
data class CapType(
    val hero: TextStyle,          // très grand titrage (accueil, révélations)
    val display: TextStyle,       // titres d'écran
    val title: TextStyle,         // titres de carte
    val instruction: TextStyle,   // l'instruction de guidage, lisible à bout de bras
    val distance: TextStyle,      // le chiffre de distance
    val body: TextStyle,
    val bodyLarge: TextStyle,
    val quote: TextStyle,         // narration, messages de l'hôte
    val label: TextStyle,         // petites capitales espacées
    val button: TextStyle,
    val displayUppercase: Boolean,
    val instructionUppercase: Boolean,
)

/** Ajustements liés au public : taille des textes, tutoiement, voix par défaut. */
@Immutable
data class AudienceProfile(val fontScale: Float, val informal: Boolean, val voiceByDefault: Boolean, val maxAttempts: Int) {
    companion object {
        fun of(audience: Audience) = when (audience) {
            Audience.KIDS -> AudienceProfile(fontScale = 1.22f, informal = true, voiceByDefault = true, maxAttempts = 4)
            Audience.FRIENDS -> AudienceProfile(fontScale = 1.0f, informal = true, voiceByDefault = false, maxAttempts = 3)
            Audience.COUPLE -> AudienceProfile(fontScale = 1.0f, informal = true, voiceByDefault = false, maxAttempts = 3)
            Audience.ADULTS -> AudienceProfile(fontScale = 1.0f, informal = false, voiceByDefault = false, maxAttempts = 3)
        }
    }
}

@Immutable
data class CapThemeData(
    val spec: ThemeSpec,
    val colors: CapColors,
    val type: CapType,
    val audience: Audience,
    val profile: AudienceProfile,
    val tone: Tone,
)

val LocalCap = staticCompositionLocalOf<CapThemeData> { error("CapTheme manquant") }

/** Accès court : `Cap.colors`, `Cap.type`, `Cap.tone`. */
object Cap {
    val colors: CapColors @Composable get() = LocalCap.current.colors
    val type: CapType @Composable get() = LocalCap.current.type
    val tone: Tone @Composable get() = LocalCap.current.tone
    val spec: ThemeSpec @Composable get() = LocalCap.current.spec
    val current: CapThemeData @Composable get() = LocalCap.current
}

@Composable
fun CapTheme(
    spec: ThemeSpec,
    audience: Audience = Audience.FRIENDS,
    night: Boolean = true,
    content: @Composable () -> Unit,
) {
    val assets = LocalContext.current.assets
    val data = remember(spec, audience, night) {
        val colors = (spec.palette[if (night) "night" else "day"] ?: spec.palette.values.first()).toColors(night)
        val profile = AudienceProfile.of(audience)
        fun family(path: String, weight: FontWeight = FontWeight.Normal, style: FontStyle = FontStyle.Normal) =
            runCatching { FontFamily(Font(path, assets, weight, style)) }.getOrDefault(FontFamily.Default)
        val t = spec.typography
        val display = family(t.display)
        val instruction = family(t.instruction)
        val body = FontFamily(
            Font(t.body, assets, FontWeight.Normal),
            Font(t.bodyItalic, assets, FontWeight.Normal, FontStyle.Italic),
            Font(t.bodyBold, assets, FontWeight.Bold),
        )
        val s = profile.fontScale
        val trim = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
        val type = CapType(
            hero = TextStyle(fontFamily = display, fontSize = (64 * t.displayScale).sp, lineHeight = (62 * t.displayScale).sp, letterSpacing = t.displayTracking.em, lineHeightStyle = trim),
            display = TextStyle(fontFamily = display, fontSize = (40 * t.displayScale).sp, lineHeight = (42 * t.displayScale).sp, letterSpacing = t.displayTracking.em, lineHeightStyle = trim),
            title = TextStyle(fontFamily = display, fontSize = (26 * t.displayScale).sp, lineHeight = (30 * t.displayScale).sp, letterSpacing = t.displayTracking.em),
            instruction = TextStyle(fontFamily = instruction, fontSize = (38 * t.instructionScale * s).sp, lineHeight = (40 * t.instructionScale * s).sp, letterSpacing = t.instructionTracking.em, lineHeightStyle = trim),
            distance = TextStyle(fontFamily = instruction, fontSize = (30 * t.instructionScale * s).sp, lineHeight = (32 * t.instructionScale * s).sp, letterSpacing = t.instructionTracking.em),
            body = TextStyle(fontFamily = body, fontSize = (16 * s).sp, lineHeight = (23 * s).sp),
            bodyLarge = TextStyle(fontFamily = body, fontSize = (19 * s).sp, lineHeight = (27 * s).sp),
            quote = TextStyle(fontFamily = body, fontStyle = FontStyle.Italic, fontSize = (20 * s).sp, lineHeight = (29 * s).sp),
            label = TextStyle(fontFamily = body, fontWeight = FontWeight.Bold, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.14.em),
            button = TextStyle(fontFamily = body, fontWeight = FontWeight.Bold, fontSize = (17 * s).sp, lineHeight = 22.sp, letterSpacing = 0.02.em),
            displayUppercase = t.displayUppercase,
            instructionUppercase = t.instructionUppercase,
        )
        CapThemeData(spec, colors, type, audience, profile, Tone(spec.tone, audience))
    }
    CompositionLocalProvider(LocalCap provides data) {
        MaterialTheme(
            colorScheme = data.colors.toMaterial(),
            typography = Typography(
                bodyLarge = data.type.body, bodyMedium = data.type.body, bodySmall = data.type.body.copy(fontSize = 13.sp),
                titleLarge = data.type.title, titleMedium = data.type.bodyLarge, labelLarge = data.type.button,
                labelMedium = data.type.label, labelSmall = data.type.label,
            ),
            content = content,
        )
    }
}

private fun CapColors.toMaterial(): ColorScheme {
    val base = if (isNight) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary, onPrimary = onPrimary, secondary = secondary, onSecondary = background,
        background = background, onBackground = text, surface = surface, onSurface = text,
        surfaceVariant = surfaceHigh, onSurfaceVariant = textMuted, outline = outline, outlineVariant = outline,
        error = danger, onError = Color.White, primaryContainer = surfaceHigh, onPrimaryContainer = text,
        secondaryContainer = surfaceHigh, onSecondaryContainer = text,
        surfaceContainer = surface, surfaceContainerHigh = surfaceHigh, surfaceContainerHighest = surfaceHigh,
        surfaceContainerLow = surface, surfaceContainerLowest = background, surfaceTint = Color.Transparent,
        scrim = Color.Black,
    )
}

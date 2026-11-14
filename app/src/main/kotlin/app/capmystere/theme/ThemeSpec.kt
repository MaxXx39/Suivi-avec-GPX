package app.capmystere.theme

import kotlinx.serialization.Serializable

/**
 * Description complète d'un thème, lue depuis `assets/themes/<id>/theme.json`.
 * Ajouter un thème = déposer un dossier avec ce fichier (et ses sons) : aucun code à écrire.
 */
@Serializable
data class ThemeSpec(
    val id: String,
    val name: String,
    val tagline: String = "",
    /** Clés "night" et "day". */
    val palette: Map<String, PaletteSpec>,
    val typography: TypographySpec,
    val shape: ShapeSpec = ShapeSpec(),
    val decor: DecorSpec = DecorSpec(),
    val map: MapSpec,
    /** Illustrations vectorielles (chemins SVG dans une boîte 100×100) : emblem, stage, clue, challenge. */
    val illustrations: Map<String, List<PathSpec>> = emptyMap(),
    /** Sons relatifs au dossier du thème : cue, arrive, clue, fail. */
    val sounds: Map<String, String> = emptyMap(),
    val voice: VoiceSpec = VoiceSpec(),
    val tone: ToneSpec = ToneSpec(),
)

@Serializable
data class PaletteSpec(
    val background: String, val surface: String, val surfaceHigh: String,
    val primary: String, val onPrimary: String, val secondary: String,
    val text: String, val textMuted: String, val outline: String,
    val success: String, val danger: String,
    val route: String, val routeCasing: String, val puck: String,
)

@Serializable
data class TypographySpec(
    val display: String, val instruction: String, val body: String,
    val bodyItalic: String = body, val bodyBold: String = body,
    val displayUppercase: Boolean = false, val instructionUppercase: Boolean = false,
    /** Interlettrage en em. */
    val displayTracking: Float = 0f, val instructionTracking: Float = 0f,
    val displayScale: Float = 1f, val instructionScale: Float = 1f,
)

/** card : "parchment" (bords irréguliers), "cut" (coins coupés), "plain" (arrondi). */
@Serializable
data class ShapeSpec(val corner: Float = 12f, val card: String = "plain", val border: Float = 1f)

/** background : "waves", "blinds" ou "none". */
@Serializable
data class DecorSpec(val background: String = "none", val grain: Float = 0f, val routeDash: List<Float> = emptyList())

@Serializable
data class MapPaletteSpec(
    val land: String, val water: String, val park: String, val building: String,
    val roadMinor: String, val roadMajor: String, val roadCasing: String, val rail: String,
    val label: String, val labelHalo: String,
)

@Serializable
data class MapSpec(val night: MapPaletteSpec, val day: MapPaletteSpec, val streetNames: Boolean = true)

/** color : jeton de palette (primary, secondary, text, textMuted, background, surface…). stroke > 0 = contour. */
@Serializable
data class PathSpec(val d: String, val color: String = "primary", val alpha: Float = 1f, val stroke: Float = 0f)

@Serializable
data class VoiceSpec(val pitch: Float = 1f, val rate: Float = 1f)

@Serializable
data class ToneOverride(val words: Map<String, String> = emptyMap(), val narration: Map<String, String> = emptyMap())

@Serializable
data class SuggestionSpec(
    val kind: String, val prompt: String,
    val options: List<String> = emptyList(), val correctOption: Int = 0,
    val acceptedAnswers: List<String> = emptyList(),
)

@Serializable
data class ToneSpec(
    val words: Map<String, String> = emptyMap(),
    /** Répliques d'ambiance par famille de manœuvre : DEPART, STRAIGHT, LEFT, RIGHT, ROUNDABOUT, UTURN, ARRIVE. */
    val flavor: Map<String, List<String>> = emptyMap(),
    val narration: Map<String, String> = emptyMap(),
    /** Surcharges par public (clé = nom de l'enum Audience). */
    val audiences: Map<String, ToneOverride> = emptyMap(),
    /** Défis suggérés par public. */
    val suggestions: Map<String, List<SuggestionSpec>> = emptyMap(),
)

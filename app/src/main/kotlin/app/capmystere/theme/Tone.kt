package app.capmystere.theme

import app.capmystere.core.guidance.ManeuverType
import app.capmystere.core.model.Audience

/** Ton des textes d'un thème, résolu pour un public donné. */
class Tone(private val spec: ToneSpec, audience: Audience) {
    private val override = spec.audiences[audience.name]
    private val audienceKey = audience.name

    /** Vocabulaire du thème : host, traveler, stage, challenge, clue, clueBook, trip. */
    fun word(key: String, fallback: String): String = override?.words?.get(key) ?: spec.words[key] ?: fallback

    fun narration(key: String, fallback: String = ""): String =
        override?.narration?.get(key) ?: spec.narration[key] ?: fallback

    /** Réplique d'ambiance, stable pour une graine donnée (ne change pas à chaque recomposition). */
    fun flavor(type: ManeuverType?, seed: Int): String? {
        val list = spec.flavor[group(type)].orEmpty()
        return if (list.isEmpty()) null else list[Math.floorMod(seed, list.size)]
    }

    val suggestions: List<SuggestionSpec> get() = spec.suggestions[audienceKey].orEmpty()

    companion object {
        fun group(type: ManeuverType?): String = when (type) {
            null, ManeuverType.CONTINUE, ManeuverType.MERGE, ManeuverType.FERRY -> "STRAIGHT"
            ManeuverType.DEPART -> "DEPART"
            ManeuverType.LEFT, ManeuverType.SLIGHT_LEFT, ManeuverType.SHARP_LEFT, ManeuverType.EXIT_LEFT, ManeuverType.KEEP_LEFT -> "LEFT"
            ManeuverType.RIGHT, ManeuverType.SLIGHT_RIGHT, ManeuverType.SHARP_RIGHT, ManeuverType.EXIT_RIGHT, ManeuverType.KEEP_RIGHT -> "RIGHT"
            ManeuverType.ROUNDABOUT -> "ROUNDABOUT"
            ManeuverType.UTURN -> "UTURN"
            ManeuverType.ARRIVE -> "ARRIVE"
        }
    }
}

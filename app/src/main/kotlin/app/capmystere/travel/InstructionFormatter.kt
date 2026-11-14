package app.capmystere.travel

import android.content.Context
import app.capmystere.R
import app.capmystere.core.guidance.CueLevel
import app.capmystere.core.guidance.Distances
import app.capmystere.core.guidance.GuidanceState
import app.capmystere.core.guidance.Maneuver
import app.capmystere.core.guidance.ManeuverType

/** Ce que l'écran de guidage affiche. Aucun de ces champs ne porte d'information sur la destination. */
data class InstructionText(
    /** « Dans 2,3 km », « Pendant 18 km », ou vide quand c'est maintenant. */
    val lead: String,
    /** « Tournez à droite », « Au rond-point, 2ᵉ sortie ». */
    val main: String,
    /** « puis à gauche », si la manœuvre suivante s'enchaîne. */
    val then: String?,
    /** Manœuvre représentée par le pictogramme (`null` = flèche tout droit). */
    val icon: Maneuver?,
)

/**
 * Compose les instructions à partir du type de manœuvre et des chaînes de ressources.
 * Aucun nom de rue, de ville ou de panneau : rien qui puisse trahir la destination.
 */
class InstructionFormatter(private val context: Context, private val informal: Boolean, private val farThresholdM: Double) {
    private fun s(id: Int, vararg args: Any): String = context.getString(id, *args)

    fun verb(m: Maneuver): String {
        val t = informal
        return when (m.type) {
            ManeuverType.DEPART -> s(if (t) R.string.m_depart_tu else R.string.m_depart)
            ManeuverType.CONTINUE -> s(if (t) R.string.m_continue_tu else R.string.m_continue)
            ManeuverType.SLIGHT_RIGHT -> s(if (t) R.string.m_slight_right_tu else R.string.m_slight_right)
            ManeuverType.RIGHT -> s(if (t) R.string.m_right_tu else R.string.m_right)
            ManeuverType.SHARP_RIGHT -> s(if (t) R.string.m_sharp_right_tu else R.string.m_sharp_right)
            ManeuverType.SLIGHT_LEFT -> s(if (t) R.string.m_slight_left_tu else R.string.m_slight_left)
            ManeuverType.LEFT -> s(if (t) R.string.m_left_tu else R.string.m_left)
            ManeuverType.SHARP_LEFT -> s(if (t) R.string.m_sharp_left_tu else R.string.m_sharp_left)
            ManeuverType.UTURN -> s(if (t) R.string.m_uturn_tu else R.string.m_uturn)
            ManeuverType.ROUNDABOUT ->
                if (m.exit > 0) s(if (t) R.string.m_roundabout_exit_tu else R.string.m_roundabout_exit, ordinal(m.exit))
                else s(if (t) R.string.m_roundabout_tu else R.string.m_roundabout)
            ManeuverType.EXIT_RIGHT -> s(if (t) R.string.m_exit_right_tu else R.string.m_exit_right)
            ManeuverType.EXIT_LEFT -> s(if (t) R.string.m_exit_left_tu else R.string.m_exit_left)
            ManeuverType.KEEP_RIGHT -> s(if (t) R.string.m_keep_right_tu else R.string.m_keep_right)
            ManeuverType.KEEP_LEFT -> s(if (t) R.string.m_keep_left_tu else R.string.m_keep_left)
            ManeuverType.MERGE -> s(if (t) R.string.m_merge_tu else R.string.m_merge)
            ManeuverType.FERRY -> s(if (t) R.string.m_ferry_tu else R.string.m_ferry)
            ManeuverType.ARRIVE -> s(if (t) R.string.m_arrive_tu else R.string.m_arrive)
        }
    }

    private fun ordinal(n: Int): String = if (n == 1) s(R.string.ordinal_first) else s(R.string.ordinal_nth, n)

    private fun shortThen(m: Maneuver): String = when (m.type) {
        ManeuverType.LEFT, ManeuverType.SLIGHT_LEFT, ManeuverType.SHARP_LEFT, ManeuverType.KEEP_LEFT, ManeuverType.EXIT_LEFT -> s(R.string.then_left)
        ManeuverType.RIGHT, ManeuverType.SLIGHT_RIGHT, ManeuverType.SHARP_RIGHT, ManeuverType.KEEP_RIGHT, ManeuverType.EXIT_RIGHT -> s(R.string.then_right)
        ManeuverType.ROUNDABOUT -> s(R.string.then_roundabout)
        ManeuverType.ARRIVE -> s(R.string.then_arrive)
        else -> s(R.string.then_generic, verb(m).replaceFirstChar { it.lowercase() })
    }

    fun display(state: GuidanceState, nowM: Double): InstructionText {
        val next = state.next ?: return InstructionText("", s(if (informal) R.string.m_continue_tu else R.string.m_continue), null, null)
        val d = state.distanceToNextM
        return when {
            // Loin de la prochaine manœuvre : « Continuez tout droit pendant 18 km ».
            d > farThresholdM -> InstructionText(
                lead = s(R.string.lead_for, Distances.format(d)),
                main = s(if (informal) R.string.m_continue_tu else R.string.m_continue),
                then = null, icon = null,
            )
            d <= nowM -> InstructionText("", verb(next), state.then?.let(::shortThen), next)
            else -> InstructionText(s(R.string.lead_in, Distances.format(d)), verb(next), state.then?.let(::shortThen), next)
        }
    }

    /** Phrase prononcée par la synthèse vocale pour une annonce. */
    fun spoken(level: CueLevel, m: Maneuver, distanceM: Double, then: Maneuver?): String {
        val v = verb(m)
        val base = when (level) {
            CueLevel.NOW -> v
            else -> s(R.string.spoken_in, Distances.format(distanceM), v.replaceFirstChar { it.lowercase() })
        }
        return if (then != null && level != CueLevel.FAR) "$base, ${shortThen(then)}" else base
    }
}

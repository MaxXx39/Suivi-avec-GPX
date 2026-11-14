package app.capmystere.core.guidance

import kotlin.math.roundToInt

/** Arrondis « parlants » des distances, façon GPS. */
object Distances {
    data class Rounded(val value: Double, val isKm: Boolean)

    fun round(meters: Double): Rounded = when {
        meters < 30 -> Rounded(((meters / 5).roundToInt() * 5).toDouble(), false)
        meters < 100 -> Rounded(((meters / 10).roundToInt() * 10).toDouble(), false)
        meters < 950 -> Rounded(((meters / 50).roundToInt() * 50).toDouble().coerceAtLeast(100.0), false)
        meters < 10_000 -> Rounded((meters / 100).roundToInt() / 10.0, true)
        else -> Rounded((meters / 1000).roundToInt().toDouble(), true)
    }

    /** « 2,3 km », « 18 km », « 400 m » (séparateur décimal français). */
    fun format(meters: Double): String {
        val r = round(meters)
        return if (r.isKm) {
            val v = r.value
            if (v >= 10 || v == v.toInt().toDouble()) "${v.toInt()} km"
            else "${v.toString().replace('.', ',')} km"
        } else "${r.value.toInt()} m"
    }

    /** Durée lisible, réservée à l'hôte. */
    fun formatDuration(seconds: Double): String {
        val min = (seconds / 60).roundToInt()
        return if (min < 60) "$min min" else "${min / 60} h ${(min % 60).toString().padStart(2, '0')}"
    }
}

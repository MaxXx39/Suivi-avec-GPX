package app.capmystere.core.guidance

import app.capmystere.core.geo.Geo
import app.capmystere.core.model.LatLon
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

enum class ManeuverType {
    DEPART, CONTINUE, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, UTURN, SHARP_LEFT, LEFT, SLIGHT_LEFT,
    ROUNDABOUT, EXIT_RIGHT, EXIT_LEFT, KEEP_RIGHT, KEEP_LEFT, MERGE, FERRY, ARRIVE
}

/** Manœuvre localisée sur la polyligne par [shapeIndex]. */
@Serializable
data class Maneuver(
    val type: ManeuverType,
    val shapeIndex: Int,
    /** Numéro de sortie pour un rond-point. */
    val exit: Int = 0,
)

/** Tronçon : géométrie + manœuvres. Distance et durée ne sont affichées que côté hôte. */
@Serializable
data class Route(
    val points: List<LatLon>,
    val maneuvers: List<Maneuver>,
    val distanceM: Double = 0.0,
    val durationS: Double = 0.0,
) {
    @Transient
    val cumulative: DoubleArray = Geo.cumulative(points)

    val lengthM: Double get() = if (cumulative.isEmpty()) 0.0 else cumulative.last()

    /** Abscisse curviligne (m) d'une manœuvre. */
    fun maneuverOffset(m: Maneuver): Double = cumulative[m.shapeIndex.coerceIn(0, cumulative.lastIndex)]

    /** Point situé à [offsetM] du départ le long du tracé, avec son cap. */
    fun pointAt(offsetM: Double): Pair<LatLon, Double> {
        if (points.size < 2) return (points.firstOrNull() ?: LatLon(0.0, 0.0)) to 0.0
        val o = offsetM.coerceIn(0.0, lengthM)
        var i = cumulative.binarySearch(o).let { if (it >= 0) it else -it - 2 }.coerceIn(0, points.size - 2)
        // Saute les segments de longueur nulle.
        while (i < points.size - 2 && cumulative[i + 1] - cumulative[i] <= 0.0) i++
        val segLen = cumulative[i + 1] - cumulative[i]
        val t = if (segLen <= 0.0) 0.0 else (o - cumulative[i]) / segLen
        return Geo.interpolate(points[i], points[i + 1], t) to Geo.bearing(points[i], points[i + 1])
    }

    /** Portion du tracé entre deux abscisses (pour n'afficher que la route proche). */
    fun slice(fromM: Double, toM: Double): List<LatLon> {
        if (points.size < 2) return points
        val a = fromM.coerceIn(0.0, lengthM)
        val b = toM.coerceIn(a, lengthM)
        val out = ArrayList<LatLon>()
        out.add(pointAt(a).first)
        for (i in points.indices) if (cumulative[i] > a && cumulative[i] < b) out.add(points[i])
        out.add(pointAt(b).first)
        return out
    }
}

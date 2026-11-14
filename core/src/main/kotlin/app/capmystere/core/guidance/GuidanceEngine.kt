package app.capmystere.core.guidance

import app.capmystere.core.geo.Geo
import app.capmystere.core.model.LatLon
import app.capmystere.core.model.TravelProfile
import kotlin.math.max

/** Position GPS brute. */
data class Fix(
    val position: LatLon,
    val accuracyM: Float = 10f,
    val speedMps: Float = 0f,
    val bearingDeg: Float? = null,
    val timeMs: Long = 0,
)

enum class GuidanceStatus { ON_ROUTE, OFF_ROUTE, ARRIVED }

/** Niveau d'annonce d'une manœuvre : lointaine, proche, immédiate. */
enum class CueLevel { FAR, NEAR, NOW }

/** Annonce à prononcer / faire vibrer (émise une seule fois par manœuvre et par niveau). */
data class Cue(val level: CueLevel, val maneuver: Maneuver, val distanceM: Double)

data class GuidanceState(
    val status: GuidanceStatus,
    /** Position recalée sur le tracé (ou position brute si hors route). */
    val snapped: LatLon,
    /** Cap du tracé à cet endroit, pour orienter la carte. */
    val bearingDeg: Double,
    /** Abscisse curviligne sur le tronçon. Usage interne : jamais affichée au voyageur. */
    val progressM: Double,
    val next: Maneuver?,
    val distanceToNextM: Double,
    /** Manœuvre suivante si elle s'enchaîne rapidement (« puis… »). */
    val then: Maneuver?,
    val offRouteDistanceM: Double,
    val cue: Cue? = null,
    val speedMps: Float = 0f,
)

/** Réglages dépendant du mode de déplacement. */
data class GuidanceConfig(
    val farM: Double,
    val nearM: Double,
    val nowM: Double,
    val offRouteM: Double,
    val offRouteFixes: Int = 3,
    /** Deux manœuvres séparées de moins que ça sont annoncées ensemble. */
    val chainM: Double,
) {
    companion object {
        fun forProfile(profile: TravelProfile): GuidanceConfig = when (profile) {
            TravelProfile.CAR -> GuidanceConfig(farM = 2000.0, nearM = 500.0, nowM = 60.0, offRouteM = 60.0, chainM = 150.0)
            TravelProfile.BIKE -> GuidanceConfig(farM = 500.0, nearM = 150.0, nowM = 25.0, offRouteM = 40.0, chainM = 60.0)
            TravelProfile.WALK -> GuidanceConfig(farM = 200.0, nearM = 60.0, nowM = 12.0, offRouteM = 30.0, chainM = 25.0)
        }
    }
}

/**
 * Moteur de guidage local : aucune dépendance réseau ni Android.
 * Projette chaque position sur la polyligne, déduit la prochaine manœuvre, déclenche les
 * annonces aux bons seuils, détecte la sortie de route et l'arrivée dans le rayon de validation.
 */
class GuidanceEngine(
    val route: Route,
    private val target: LatLon,
    private val arrivalRadiusM: Double,
    private val config: GuidanceConfig,
) {
    private var lastSegment = 0
    private var offRouteCount = 0
    private var arrived = false
    private val announced = HashMap<Int, CueLevel>()

    /** Manœuvres « actionnables » (on ignore le départ). */
    private val steps: List<Maneuver> = route.maneuvers.filter { it.type != ManeuverType.DEPART }

    fun update(fix: Fix): GuidanceState {
        val distToTarget = Geo.distance(fix.position, target)
        if (arrived || distToTarget <= arrivalRadiusM) {
            arrived = true
            return GuidanceState(
                GuidanceStatus.ARRIVED, fix.position, fix.bearingDeg?.toDouble() ?: 0.0,
                route.lengthM, null, 0.0, null, 0.0, speedMps = fix.speedMps,
            )
        }
        if (route.points.size < 2) {
            return GuidanceState(GuidanceStatus.OFF_ROUTE, fix.position, 0.0, 0.0, null, 0.0, null, distToTarget)
        }

        val snap = snap(fix.position)
        val tolerance = config.offRouteM + fix.accuracyM.coerceAtMost(40f)
        if (snap.distanceM > tolerance) offRouteCount++ else offRouteCount = 0
        val offRoute = offRouteCount >= config.offRouteFixes

        if (!offRoute && snap.distanceM <= tolerance) lastSegment = snap.segment
        val progress = snap.offsetM
        val bearing = Geo.bearing(route.points[snap.segment], route.points[snap.segment + 1])

        // Prochaine manœuvre : première dont l'abscisse est devant nous (petite marge pour le bruit GPS).
        val nextIdx = steps.indexOfFirst { route.maneuverOffset(it) > progress + 5.0 }
            .let { if (it < 0) steps.lastIndex else it }
        val next = steps.getOrNull(nextIdx)
        val distToNext = next?.let { max(0.0, route.maneuverOffset(it) - progress) } ?: 0.0
        val then = steps.getOrNull(nextIdx + 1)?.takeIf {
            next != null && route.maneuverOffset(it) - route.maneuverOffset(next) <= config.chainM
        }

        val cue = if (!offRoute && next != null) cueFor(nextIdx, next, distToNext) else null

        return GuidanceState(
            status = if (offRoute) GuidanceStatus.OFF_ROUTE else GuidanceStatus.ON_ROUTE,
            snapped = if (offRoute) fix.position else snap.point,
            bearingDeg = if (offRoute) fix.bearingDeg?.toDouble() ?: bearing else bearing,
            progressM = progress,
            next = next,
            distanceToNextM = distToNext,
            then = then,
            offRouteDistanceM = snap.distanceM,
            cue = cue,
            speedMps = fix.speedMps,
        )
    }

    private fun cueFor(index: Int, m: Maneuver, dist: Double): Cue? {
        val level = when {
            dist <= config.nowM -> CueLevel.NOW
            dist <= config.nearM -> CueLevel.NEAR
            dist <= config.farM -> CueLevel.FAR
            else -> return null
        }
        val previous = announced[index]
        if (previous != null && previous.ordinal >= level.ordinal) return null
        announced[index] = level
        return Cue(level, m, dist)
    }

    private data class Snap(val segment: Int, val point: LatLon, val offsetM: Double, val distanceM: Double)

    /**
     * Recalage : on cherche d'abord dans une fenêtre autour du dernier segment connu (évite de
     * sauter sur un tronçon parallèle ou un aller-retour), puis globalement si l'on est trop loin.
     */
    private fun snap(p: LatLon): Snap {
        val cum = route.cumulative
        val windowEnd = run {
            var i = lastSegment
            val limit = cum[lastSegment] + WINDOW_AHEAD_M
            while (i < route.points.size - 2 && cum[i] < limit) i++
            i
        }
        val local = best(p, max(0, lastSegment - 3), windowEnd)
        if (local.distanceM <= config.offRouteM * 1.5) return local
        val global = best(p, 0, route.points.size - 2)
        return if (global.distanceM + 15.0 < local.distanceM) global else local
    }

    private fun best(p: LatLon, from: Int, to: Int): Snap {
        var bestSnap: Snap? = null
        for (i in from..to) {
            val pr = Geo.projectOnSegment(p, route.points[i], route.points[i + 1])
            if (bestSnap == null || pr.distanceM < bestSnap.distanceM) {
                val off = route.cumulative[i] + (route.cumulative[i + 1] - route.cumulative[i]) * pr.t
                bestSnap = Snap(i, pr.point, off, pr.distanceM)
            }
        }
        return bestSnap!!
    }

    companion object {
        private const val WINDOW_AHEAD_M = 600.0
    }
}

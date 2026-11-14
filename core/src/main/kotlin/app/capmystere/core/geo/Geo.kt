package app.capmystere.core.geo

import app.capmystere.core.model.LatLon
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

object Geo {
    const val EARTH_RADIUS_M = 6_371_008.8
    private const val DEG = PI / 180.0

    fun distance(a: LatLon, b: LatLon): Double {
        val dLat = (b.lat - a.lat) * DEG
        val dLon = (b.lon - a.lon) * DEG
        val h = sin(dLat / 2).let { it * it } +
            cos(a.lat * DEG) * cos(b.lat * DEG) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /** Cap initial de a vers b, en degrés [0, 360). */
    fun bearing(a: LatLon, b: LatLon): Double {
        val la = a.lat * DEG
        val lb = b.lat * DEG
        val dLon = (b.lon - a.lon) * DEG
        val y = sin(dLon) * cos(lb)
        val x = cos(la) * sin(lb) - sin(la) * cos(lb) * cos(dLon)
        return (atan2(y, x) / DEG + 360.0) % 360.0
    }

    /** Écart angulaire signé b - a dans (-180, 180]. */
    fun angleDiff(a: Double, b: Double): Double {
        var d = (b - a) % 360.0
        if (d > 180) d -= 360.0
        if (d <= -180) d += 360.0
        return d
    }

    fun interpolate(a: LatLon, b: LatLon, t: Double): LatLon =
        LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)

    /** Point à [distanceM] de [from] dans la direction [bearingDeg]. */
    fun offset(from: LatLon, bearingDeg: Double, distanceM: Double): LatLon {
        val d = distanceM / EARTH_RADIUS_M
        val br = bearingDeg * DEG
        val la = from.lat * DEG
        val lat = kotlin.math.asin(sin(la) * cos(d) + cos(la) * sin(d) * cos(br))
        val lon = from.lon * DEG + atan2(sin(br) * sin(d) * cos(la), cos(d) - sin(la) * sin(lat))
        return LatLon(lat / DEG, lon / DEG)
    }

    data class Projection(val point: LatLon, val t: Double, val distanceM: Double)

    /** Projection de [p] sur le segment [a, b] (approximation plane locale, suffisante à cette échelle). */
    fun projectOnSegment(p: LatLon, a: LatLon, b: LatLon): Projection {
        val cosLat = cos(a.lat * DEG)
        val ax = 0.0
        val ay = 0.0
        val bx = (b.lon - a.lon) * DEG * cosLat * EARTH_RADIUS_M
        val by = (b.lat - a.lat) * DEG * EARTH_RADIUS_M
        val px = (p.lon - a.lon) * DEG * cosLat * EARTH_RADIUS_M
        val py = (p.lat - a.lat) * DEG * EARTH_RADIUS_M
        val len2 = bx * bx + by * by
        val t = if (len2 == 0.0) 0.0 else max(0.0, min(1.0, ((px - ax) * bx + (py - ay) * by) / len2))
        val qx = bx * t
        val qy = by * t
        val d = sqrt((px - qx) * (px - qx) + (py - qy) * (py - qy))
        return Projection(interpolate(a, b, t), t, d)
    }

    /** Distances cumulées le long d'une polyligne (taille = points.size, premier = 0). */
    fun cumulative(points: List<LatLon>): DoubleArray {
        val out = DoubleArray(points.size)
        for (i in 1 until points.size) out[i] = out[i - 1] + distance(points[i - 1], points[i])
        return out
    }

    /** Décodage d'une polyligne encodée (précision 6 pour Valhalla, 5 pour Google/OSRM). */
    fun decodePolyline(encoded: String, precision: Int = 6): List<LatLon> {
        val factor = Math.pow(10.0, precision.toDouble())
        val out = ArrayList<LatLon>(encoded.length / 4)
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            var result = 0L
            var shift = 0
            var b: Int
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20)
            lat += if (result and 1L != 0L) (result shr 1).inv() else result shr 1
            result = 0
            shift = 0
            do {
                b = encoded[index++].code - 63
                result = result or ((b and 0x1f).toLong() shl shift)
                shift += 5
            } while (b >= 0x20)
            lon += if (result and 1L != 0L) (result shr 1).inv() else result shr 1
            out.add(LatLon(lat / factor, lon / factor))
        }
        return out
    }

    /** Simplification Douglas-Peucker (tolérance en mètres). */
    fun simplify(points: List<LatLon>, toleranceM: Double): List<LatLon> {
        if (points.size < 3) return points
        val keep = BooleanArray(points.size)
        keep[0] = true
        keep[points.lastIndex] = true
        val stack = ArrayDeque<Pair<Int, Int>>()
        stack.add(0 to points.lastIndex)
        while (stack.isNotEmpty()) {
            val (s, e) = stack.removeLast()
            var maxD = 0.0
            var idx = -1
            for (i in s + 1 until e) {
                val d = projectOnSegment(points[i], points[s], points[e]).distanceM
                if (d > maxD) { maxD = d; idx = i }
            }
            if (idx >= 0 && maxD > toleranceM) {
                keep[idx] = true
                stack.add(s to idx)
                stack.add(idx to e)
            }
        }
        return points.filterIndexed { i, _ -> keep[i] }
    }

    /**
     * Polygone (anneau fermé) formant un couloir de [halfWidthM] autour d'une polyligne.
     * Sert à délimiter la zone de tuiles à pré-télécharger pour le hors-ligne.
     */
    fun corridor(points: List<LatLon>, halfWidthM: Double): List<LatLon> {
        if (points.isEmpty()) return emptyList()
        val line = simplify(points, halfWidthM / 4).let { if (it.size == 1) listOf(it[0], it[0]) else it }
        val left = ArrayList<LatLon>(line.size + 2)
        val right = ArrayList<LatLon>(line.size + 2)
        for (i in line.indices) {
            var miter = 1.0
            val brg = when (i) {
                0 -> bearing(line[0], line[min(1, line.lastIndex)])
                line.lastIndex -> bearing(line[i - 1], line[i])
                else -> {
                    val b1 = bearing(line[i - 1], line[i])
                    val half = angleDiff(b1, bearing(line[i], line[i + 1])) / 2
                    // Onglet : on allonge le décalage dans les virages pour conserver la largeur du couloir.
                    miter = min(2.0, 1.0 / max(0.2, cos(half * DEG)))
                    b1 + half
                }
            }
            left.add(offset(line[i], brg - 90, halfWidthM * miter))
            right.add(offset(line[i], brg + 90, halfWidthM * miter))
        }
        // Capuchons : on prolonge le couloir avant le départ et après l'arrivée.
        val startBrg = bearing(line[0], line[min(1, line.lastIndex)])
        val endBrg = bearing(line[max(0, line.lastIndex - 1)], line.last())
        val ring = ArrayList<LatLon>()
        ring.add(offset(left.first(), startBrg + 180, halfWidthM))
        ring.addAll(left)
        ring.add(offset(left.last(), endBrg, halfWidthM))
        ring.add(offset(right.last(), endBrg, halfWidthM))
        ring.addAll(right.asReversed())
        ring.add(offset(right.first(), startBrg + 180, halfWidthM))
        ring.add(ring.first())
        return ring
    }

    data class Bounds(val south: Double, val west: Double, val north: Double, val east: Double)

    fun bounds(points: List<LatLon>, paddingM: Double = 0.0): Bounds? {
        if (points.isEmpty()) return null
        var s = 90.0; var n = -90.0; var w = 180.0; var e = -180.0
        for (p in points) { s = min(s, p.lat); n = max(n, p.lat); w = min(w, p.lon); e = max(e, p.lon) }
        val dLat = paddingM / EARTH_RADIUS_M / DEG
        val dLon = dLat / max(0.1, cos(abs((s + n) / 2) * DEG))
        return Bounds(s - dLat, w - dLon, n + dLat, e + dLon)
    }
}

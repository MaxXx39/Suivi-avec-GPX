package app.capmystere.core.gpx

import app.capmystere.core.geo.Geo
import app.capmystere.core.guidance.Fix
import app.capmystere.core.model.LatLon

object Gpx {
    private val POINT = Regex("""<(trkpt|rtept|wpt)\b([^>]*)>""")
    private val LAT = Regex("""\blat\s*=\s*["']([-0-9.eE+]+)["']""")
    private val LON = Regex("""\blon\s*=\s*["']([-0-9.eE+]+)["']""")

    /**
     * Extrait la trace d'un GPX : points de trace (trkpt) en priorité, sinon de route (rtept),
     * sinon les waypoints. Tolérant : pas de validation stricte du XML.
     */
    fun parse(xml: String): List<LatLon> {
        val byTag = HashMap<String, MutableList<LatLon>>()
        for (m in POINT.findAll(xml)) {
            val attrs = m.groupValues[2]
            val lat = LAT.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            val lon = LON.find(attrs)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) continue
            byTag.getOrPut(m.groupValues[1]) { ArrayList() }.add(LatLon(lat, lon))
        }
        return byTag["trkpt"] ?: byTag["rtept"] ?: byTag["wpt"] ?: emptyList()
    }

    fun write(points: List<LatLon>, name: String = "Cap Mystère"): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        append("<gpx version=\"1.1\" creator=\"Cap Mystere\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        append("  <trk><name>").append(name.replace("&", "&amp;").replace("<", "&lt;")).append("</name><trkseg>\n")
        for (p in points) append("    <trkpt lat=\"${p.lat}\" lon=\"${p.lon}\"/>\n")
        append("  </trkseg></trk>\n</gpx>\n")
    }
}

/**
 * Rejoue une trace à vitesse constante : donne la position simulée après [elapsedS] secondes.
 * Déterministe (pas d'horloge interne) pour être testable.
 */
class TrackSimulator(private val points: List<LatLon>, private val speedMps: Double) {
    private val cumulative = Geo.cumulative(points)
    val lengthM: Double = if (cumulative.isEmpty()) 0.0 else cumulative.last()
    val durationS: Double = if (speedMps > 0) lengthM / speedMps else 0.0

    fun isFinished(elapsedS: Double): Boolean = elapsedS * speedMps >= lengthM

    fun fixAt(elapsedS: Double, timeMs: Long = (elapsedS * 1000).toLong()): Fix {
        if (points.isEmpty()) return Fix(LatLon(0.0, 0.0), timeMs = timeMs)
        if (points.size == 1) return Fix(points[0], timeMs = timeMs)
        val o = (elapsedS * speedMps).coerceIn(0.0, lengthM)
        var i = cumulative.binarySearch(o).let { if (it >= 0) it else -it - 2 }.coerceIn(0, points.size - 2)
        while (i < points.size - 2 && cumulative[i + 1] - cumulative[i] <= 0.0) i++
        val seg = cumulative[i + 1] - cumulative[i]
        val t = if (seg <= 0.0) 0.0 else (o - cumulative[i]) / seg
        return Fix(
            position = Geo.interpolate(points[i], points[i + 1], t),
            accuracyM = 5f,
            speedMps = if (isFinished(elapsedS)) 0f else speedMps.toFloat(),
            bearingDeg = Geo.bearing(points[i], points[i + 1]).toFloat(),
            timeMs = timeMs,
        )
    }
}

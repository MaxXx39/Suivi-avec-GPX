package app.capmystere.core.geo

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin

/** Hauteur du soleil, pour basculer automatiquement la carte en mode nuit. */
object Sun {
    private const val RAD = PI / 180.0

    /** Élévation du soleil en degrés à l'instant [epochMs] (UTC) pour la position donnée. Précision ~1°. */
    fun elevationDeg(lat: Double, lon: Double, epochMs: Long): Double {
        val days = epochMs / 86_400_000.0 - 10957.5 // jours depuis J2000.0
        val meanLon = (280.460 + 0.9856474 * days) % 360.0
        val meanAnomaly = ((357.528 + 0.9856003 * days) % 360.0) * RAD
        val eclLon = (meanLon + 1.915 * sin(meanAnomaly) + 0.020 * sin(2 * meanAnomaly)) * RAD
        val obliquity = (23.439 - 0.0000004 * days) * RAD
        val declination = asin(sin(obliquity) * sin(eclLon))
        val rightAsc = kotlin.math.atan2(cos(obliquity) * sin(eclLon), cos(eclLon))
        val gmst = (18.697374558 + 24.06570982441908 * days) % 24.0
        val hourAngle = (gmst * 15.0 + lon) * RAD - rightAsc
        val la = lat * RAD
        return asin(sin(la) * sin(declination) + cos(la) * cos(declination) * cos(hourAngle)) / RAD
    }

    /** Nuit dès que le soleil passe sous -3° (crépuscule civil entamé). */
    fun isNight(lat: Double, lon: Double, epochMs: Long): Boolean = elevationDeg(lat, lon, epochMs) < -3.0
}

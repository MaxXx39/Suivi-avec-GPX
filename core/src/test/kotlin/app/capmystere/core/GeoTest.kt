package app.capmystere.core

import app.capmystere.core.geo.Geo
import app.capmystere.core.model.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {
    private val paris = LatLon(48.8566, 2.3522)
    private val lyon = LatLon(45.7640, 4.8357)

    @Test fun `haversine Paris-Lyon`() {
        assertEquals(391_500.0, Geo.distance(paris, lyon), 1_500.0)
    }

    @Test fun `bearing points cardinaux`() {
        val o = LatLon(45.0, 5.0)
        assertEquals(0.0, Geo.bearing(o, LatLon(45.1, 5.0)), 0.01)
        assertEquals(90.0, Geo.bearing(o, LatLon(45.0, 5.1)), 0.1)
        assertEquals(180.0, Geo.bearing(o, LatLon(44.9, 5.0)), 0.01)
        assertEquals(270.0, Geo.bearing(o, LatLon(45.0, 4.9)), 0.1)
    }

    @Test fun `angleDiff gere le passage par le nord`() {
        assertEquals(20.0, Geo.angleDiff(350.0, 10.0), 1e-9)
        assertEquals(-20.0, Geo.angleDiff(10.0, 350.0), 1e-9)
    }

    @Test fun `offset puis distance sont coherents`() {
        val p = Geo.offset(paris, 73.0, 1234.0)
        assertEquals(1234.0, Geo.distance(paris, p), 0.5)
        assertEquals(73.0, Geo.bearing(paris, p), 0.1)
    }

    @Test fun `projection sur segment`() {
        val a = LatLon(45.0, 5.0)
        val b = Geo.offset(a, 90.0, 1000.0)
        val p = Geo.offset(Geo.offset(a, 90.0, 400.0), 0.0, 30.0)
        val pr = Geo.projectOnSegment(p, a, b)
        assertEquals(0.4, pr.t, 0.005)
        assertEquals(30.0, pr.distanceM, 0.5)
        // Au-delà de l'extrémité : on reste borné au segment.
        assertEquals(1.0, Geo.projectOnSegment(Geo.offset(a, 90.0, 1500.0), a, b).t, 1e-9)
    }

    @Test fun `decodage polyligne precision 5`() {
        val pts = Geo.decodePolyline("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)
        assertEquals(3, pts.size)
        assertEquals(38.5, pts[0].lat, 1e-6); assertEquals(-120.2, pts[0].lon, 1e-6)
        assertEquals(43.252, pts[2].lat, 1e-6); assertEquals(-126.453, pts[2].lon, 1e-6)
    }

    @Test fun `simplification conserve les extremites et les coudes`() {
        val a = LatLon(45.0, 5.0)
        val line = (0..100).map { Geo.offset(a, 90.0, it * 10.0) } + (1..100).map { Geo.offset(Geo.offset(a, 90.0, 1000.0), 0.0, it * 10.0) }
        val s = Geo.simplify(line, 5.0)
        assertEquals(3, s.size)
        assertEquals(line.first(), s.first()); assertEquals(line.last(), s.last())
    }

    @Test fun `le couloir contient la route et a la bonne largeur`() {
        val a = LatLon(45.0, 5.0)
        val line = listOf(a, Geo.offset(a, 90.0, 5000.0), Geo.offset(Geo.offset(a, 90.0, 5000.0), 0.0, 5000.0))
        val ring = Geo.corridor(line, 2000.0)
        assertEquals(ring.first(), ring.last())
        assertTrue(ring.size >= 8)
        val b = Geo.bounds(ring)!!
        assertTrue(b.south < a.lat && b.west < a.lon && b.north > line.last().lat && b.east > line.last().lon)
        // Chaque sommet du couloir est à ~2 km (au plus ~3 km aux coins) de la route.
        for (p in ring) {
            val d = line.zipWithNext().minOf { (u, v) -> Geo.projectOnSegment(p, u, v).distanceM }
            assertTrue("sommet à $d m", d in 1500.0..3200.0)
        }
    }
}

class SunTest {
    private fun utc(y: Int, m: Int, d: Int, h: Int, min: Int = 0): Long =
        java.time.ZonedDateTime.of(y, m, d, h, min, 0, 0, java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

    @Test fun `midi solaire a Paris au solstice d'ete`() {
        assertEquals(64.6, app.capmystere.core.geo.Sun.elevationDeg(48.8566, 2.3522, utc(2026, 6, 21, 11, 52)), 1.0)
    }

    @Test fun `jour et nuit`() {
        val s = app.capmystere.core.geo.Sun
        assertTrue(s.isNight(48.8566, 2.3522, utc(2026, 11, 14, 23)))
        assertTrue(!s.isNight(48.8566, 2.3522, utc(2026, 11, 14, 12)))
        assertTrue(s.isNight(48.8566, 2.3522, utc(2026, 11, 14, 18))) // coucher vers 16h10 UTC en novembre
        assertTrue(!s.isNight(48.8566, 2.3522, utc(2026, 6, 21, 19)))  // coucher vers 19h58 UTC en juin
        assertTrue(s.isNight(-33.87, 151.21, utc(2026, 6, 21, 12)))    // Sydney, 22 h locales
    }
}

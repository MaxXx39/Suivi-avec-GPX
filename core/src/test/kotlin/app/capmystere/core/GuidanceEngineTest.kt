package app.capmystere.core

import app.capmystere.core.geo.Geo
import app.capmystere.core.gpx.TrackSimulator
import app.capmystere.core.guidance.CueLevel
import app.capmystere.core.guidance.Fix
import app.capmystere.core.guidance.GuidanceConfig
import app.capmystere.core.guidance.GuidanceEngine
import app.capmystere.core.guidance.GuidanceStatus
import app.capmystere.core.guidance.Maneuver
import app.capmystere.core.guidance.ManeuverType
import app.capmystere.core.guidance.Route
import app.capmystere.core.model.LatLon
import app.capmystere.core.model.TravelProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidanceEngineTest {
    private val origin = LatLon(47.0, 6.0)
    private val corner1 = Geo.offset(origin, 90.0, 5000.0)       // 5 km plein est, puis à gauche
    private val rbt = Geo.offset(corner1, 0.0, 3000.0)           // 3 km plein nord, rond-point
    private val quick = Geo.offset(rbt, 90.0, 100.0)             // 100 m plus loin, à droite
    private val end = Geo.offset(quick, 180.0, 800.0)

    /** Tracé densifié (un point tous les ~50 m) pour ressembler à une vraie géométrie. */
    private fun densify(vararg pts: LatLon): Pair<List<LatLon>, List<Int>> {
        val out = ArrayList<LatLon>(); val idx = ArrayList<Int>()
        for (i in 0 until pts.size - 1) {
            idx.add(out.size)
            val n = (Geo.distance(pts[i], pts[i + 1]) / 50).toInt().coerceAtLeast(1)
            for (k in 0 until n) out.add(Geo.interpolate(pts[i], pts[i + 1], k / n.toDouble()))
        }
        idx.add(out.size); out.add(pts.last())
        return out to idx
    }

    private val route: Route = run {
        val (pts, idx) = densify(origin, corner1, rbt, quick, end)
        Route(
            pts,
            listOf(
                Maneuver(ManeuverType.DEPART, idx[0]),
                Maneuver(ManeuverType.LEFT, idx[1]),
                Maneuver(ManeuverType.ROUNDABOUT, idx[2], exit = 2),
                Maneuver(ManeuverType.RIGHT, idx[3]),
                Maneuver(ManeuverType.ARRIVE, idx[4]),
            ),
        )
    }

    private fun engine(radius: Double = 80.0) =
        GuidanceEngine(route, end, radius, GuidanceConfig.forProfile(TravelProfile.CAR))

    private fun fix(p: LatLon) = Fix(p, accuracyM = 5f, speedMps = 20f)

    @Test fun `au depart la prochaine manoeuvre est le virage a gauche a 5 km`() {
        val s = engine().update(fix(origin))
        assertEquals(GuidanceStatus.ON_ROUTE, s.status)
        assertEquals(ManeuverType.LEFT, s.next?.type)
        assertEquals(5000.0, s.distanceToNextM, 5.0)
        assertEquals(90.0, s.bearingDeg, 1.0)
        assertNull("trop loin pour annoncer", s.cue)
        assertNull(s.then)
    }

    @Test fun `la position est recalee sur le trace`() {
        val off = Geo.offset(Geo.offset(origin, 90.0, 1000.0), 0.0, 18.0) // 18 m à côté de la route
        val s = engine().update(fix(off))
        assertEquals(GuidanceStatus.ON_ROUTE, s.status)
        assertEquals(1000.0, s.progressM, 3.0)
        assertEquals(18.0, s.offRouteDistanceM, 1.0)
        assertTrue(Geo.distance(s.snapped, Geo.offset(origin, 90.0, 1000.0)) < 2.0)
    }

    @Test fun `les annonces sont emises une fois par niveau et dans l'ordre`() {
        val e = engine()
        val sim = TrackSimulator(route.points, 20.0)
        val cues = ArrayList<Pair<CueLevel, ManeuverType>>()
        var t = 0.0
        while (!sim.isFinished(t)) {
            val s = e.update(sim.fixAt(t))
            s.cue?.let { cues.add(it.level to it.maneuver.type) }
            if (s.status == GuidanceStatus.ARRIVED) break
            t += 1.0
        }
        val left = cues.filter { it.second == ManeuverType.LEFT }.map { it.first }
        assertEquals(listOf(CueLevel.FAR, CueLevel.NEAR, CueLevel.NOW), left)
        val rbtCues = cues.filter { it.second == ManeuverType.ROUNDABOUT }.map { it.first }
        assertEquals(listOf(CueLevel.FAR, CueLevel.NEAR, CueLevel.NOW), rbtCues)
        // Le virage à droite arrive 100 m après le rond-point : pas d'annonce « lointaine » fantôme en double.
        assertEquals(cues.size, cues.distinct().size)
    }

    @Test fun `les manoeuvres rapprochees sont chainees`() {
        val before = Geo.offset(rbt, 180.0, 300.0)
        val s = engine().also { it.update(fix(Geo.offset(corner1, 0.0, 100.0))) }.let { e ->
            // On avance progressivement pour que la fenêtre de recalage suive.
            var last = e.update(fix(Geo.offset(corner1, 0.0, 500.0)))
            for (d in 1000..2700 step 100) last = e.update(fix(Geo.offset(corner1, 0.0, d.toDouble())))
            last
        }
        assertTrue(Geo.distance(before, s.snapped) < 5.0)
        assertEquals(ManeuverType.ROUNDABOUT, s.next?.type)
        assertEquals(2, s.next?.exit)
        assertEquals(300.0, s.distanceToNextM, 5.0)
        assertEquals(ManeuverType.RIGHT, s.then?.type)
    }

    @Test fun `sortie de route apres plusieurs positions eloignees seulement`() {
        val e = engine()
        e.update(fix(Geo.offset(origin, 90.0, 500.0)))
        val away = { d: Double -> fix(Geo.offset(Geo.offset(origin, 90.0, 700.0 + d), 180.0, 250.0)) }
        assertEquals("une position aberrante ne suffit pas", GuidanceStatus.ON_ROUTE, e.update(away(0.0)).status)
        assertEquals(GuidanceStatus.ON_ROUTE, e.update(away(20.0)).status)
        val s = e.update(away(40.0))
        assertEquals(GuidanceStatus.OFF_ROUTE, s.status)
        assertEquals(250.0, s.offRouteDistanceM, 5.0)
        assertNull(s.cue)
        // Retour sur la route : le guidage reprend.
        assertEquals(GuidanceStatus.ON_ROUTE, e.update(fix(Geo.offset(origin, 90.0, 900.0))).status)
    }

    @Test fun `une mauvaise precision GPS elargit la tolerance`() {
        val e = engine()
        val p = Geo.offset(Geo.offset(origin, 90.0, 700.0), 180.0, 85.0)
        repeat(5) { assertEquals(GuidanceStatus.ON_ROUTE, e.update(Fix(p, accuracyM = 35f)).status) }
    }

    @Test fun `arrivee dans le rayon de validation et pas avant`() {
        val e = engine(radius = 80.0)
        assertEquals(GuidanceStatus.ON_ROUTE, e.update(fix(Geo.offset(end, 0.0, 120.0))).status)
        val s = e.update(fix(Geo.offset(end, 0.0, 70.0)))
        assertEquals(GuidanceStatus.ARRIVED, s.status)
        // L'arrivée est acquise même si le GPS ressort du rayon ensuite.
        assertEquals(GuidanceStatus.ARRIVED, e.update(fix(Geo.offset(end, 0.0, 150.0))).status)
    }

    @Test fun `un rayon plus large valide plus tot`() {
        val p = fix(Geo.offset(end, 0.0, 250.0))
        assertEquals(GuidanceStatus.ON_ROUTE, engine(80.0).update(p).status)
        assertEquals(GuidanceStatus.ARRIVED, engine(300.0).update(p).status)
    }

    @Test fun `un aller-retour ne fait pas sauter la progression`() {
        // Route qui va 2 km à l'est puis revient sur ses pas : les deux sens se superposent.
        val far = Geo.offset(origin, 90.0, 2000.0)
        val (pts, idx) = densify(origin, far, origin)
        val r = Route(pts, listOf(Maneuver(ManeuverType.DEPART, 0), Maneuver(ManeuverType.UTURN, idx[1]), Maneuver(ManeuverType.ARRIVE, idx[2])))
        val e = GuidanceEngine(r, Geo.offset(origin, 0.0, 5000.0), 50.0, GuidanceConfig.forProfile(TravelProfile.CAR))
        var s = e.update(fix(origin))
        for (d in 100..600 step 100) s = e.update(fix(Geo.offset(origin, 90.0, d.toDouble())))
        assertEquals(600.0, s.progressM, 5.0) // et non 3400 (le retour)
        assertEquals(ManeuverType.UTURN, s.next?.type)
        assertEquals(1400.0, s.distanceToNextM, 5.0)
    }

    @Test fun `les seuils dependent du profil`() {
        val car = GuidanceConfig.forProfile(TravelProfile.CAR)
        val walk = GuidanceConfig.forProfile(TravelProfile.WALK)
        assertTrue(car.farM > walk.farM && car.nowM > walk.nowM && car.offRouteM > walk.offRouteM)
        val e = GuidanceEngine(route, end, 80.0, walk)
        val s = e.update(fix(Geo.offset(origin, 90.0, 4850.0))) // 150 m avant le virage
        assertNotNull(s.cue); assertEquals(CueLevel.FAR, s.cue!!.level)
    }

    @Test fun `pointAt et slice restent sur le trace`() {
        val (p, brg) = route.pointAt(2500.0)
        assertTrue(Geo.distance(p, Geo.offset(origin, 90.0, 2500.0)) < 1.0)
        assertEquals(90.0, brg, 1.0)
        val sl = route.slice(4900.0, 5200.0)
        assertEquals(300.0, Geo.cumulative(sl).last(), 2.0)
    }
}

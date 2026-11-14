package app.capmystere.core

import app.capmystere.core.challenge.Answers
import app.capmystere.core.geo.Geo
import app.capmystere.core.gpx.Gpx
import app.capmystere.core.gpx.TrackSimulator
import app.capmystere.core.guidance.Distances
import app.capmystere.core.guidance.ManeuverType
import app.capmystere.core.model.Challenge
import app.capmystere.core.model.ChallengeKind
import app.capmystere.core.model.ClueFragment
import app.capmystere.core.model.ClueKind
import app.capmystere.core.model.LatLon
import app.capmystere.core.model.TravelProfile
import app.capmystere.core.model.TripCode
import app.capmystere.core.routing.Valhalla
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MiscTest {
    @Test fun `distances arrondies a la francaise`() {
        assertEquals("2,3 km", Distances.format(2312.0))
        assertEquals("18 km", Distances.format(18_240.0))
        assertEquals("400 m", Distances.format(389.0))
        assertEquals("50 m", Distances.format(52.0))
        assertEquals("1 km", Distances.format(1020.0))
        assertEquals("100 m", Distances.format(104.0))
        assertEquals("1 h 05", Distances.formatDuration(3900.0))
        assertEquals("42 min", Distances.formatDuration(2520.0))
    }

    @Test fun `normalisation des reponses`() {
        assertEquals("tour eiffel", Answers.normalize("  La Tour-Eiffel ! "))
        assertEquals("etretat", Answers.normalize("ÉTRETAT"))
        assertEquals("coeur", Answers.normalize("Cœur"))
        assertEquals("1889", Answers.normalize("1889."))
        assertEquals("ile de re", Answers.normalize("l'Île de Ré"))
        assertEquals("la", Answers.normalize("La")) // un article seul reste une réponse
    }

    @Test fun `verification par empreinte sans reponse en clair`() {
        val c = Challenge("c1", ChallengeKind.FREE_TEXT, prompt = "?", acceptedAnswers = listOf("Le Mont-Saint-Michel", "Mont St Michel"),
            reward = ClueFragment("f", ClueKind.LETTER, "E", 0))
        val t = Answers.toTraveler(c, "sel")
        assertTrue(t.hasReward)
        assertTrue(t.answerHashes.none { it.contains("mont") })
        assertTrue(Answers.matches(t, "mont saint michel"))
        assertTrue(Answers.matches(t, "MONT ST-MICHEL"))
        assertFalse(Answers.matches(t, "saint malo"))
        // Le sel change l'empreinte.
        assertFalse(Answers.toTraveler(c, "autre").answerHashes[0] == t.answerHashes[0])
    }

    @Test fun `QCM et QR`() {
        val quiz = Answers.toTraveler(Challenge("q", ChallengeKind.QUIZ, options = listOf("a", "b", "c"), correctOption = 2), "s")
        assertTrue(Answers.matches(quiz, "2")); assertFalse(Answers.matches(quiz, "0"))
        val qr = Answers.toTraveler(Challenge("r", ChallengeKind.QR_SCAN, acceptedAnswers = listOf("TRESOR-42")), "s")
        assertTrue(Answers.matches(qr, "TRESOR-42")); assertFalse(Answers.matches(qr, "tresor-42"))
        val photo = Answers.toTraveler(Challenge("p", ChallengeKind.PHOTO), "s")
        assertTrue(photo.answerHashes.isEmpty())
    }

    @Test fun `codes de voyage`() {
        repeat(200) { assertTrue(TripCode.isValid(TripCode.generate())) }
        assertEquals("ABC234", TripCode.fromLinkOrCode("capmystere://join/abc234"))
        assertEquals("ABC234", TripCode.fromLinkOrCode("https://exemple.fr/j?code=ABC234"))
        assertEquals("ABC234", TripCode.fromLinkOrCode(" abc 234 "))
        assertNull(TripCode.fromLinkOrCode("ABC"))
    }

    @Test fun `lecture et ecriture GPX`() {
        val xml = """<gpx><wpt lat="1" lon="2"/><trk><trkseg>
            <trkpt lat="47.1" lon="6.1"><ele>300</ele></trkpt>
            <trkpt lon='6.2' lat='47.2'/>
            <trkpt lat="999" lon="6.2"/>
        </trkseg></trk></gpx>"""
        val pts = Gpx.parse(xml)
        assertEquals(listOf(LatLon(47.1, 6.1), LatLon(47.2, 6.2)), pts)
        assertEquals(pts, Gpx.parse(Gpx.write(pts)))
        assertEquals(listOf(LatLon(1.0, 2.0)), Gpx.parse("""<gpx><wpt lat="1" lon="2"/></gpx>"""))
        assertTrue(Gpx.parse("pas du gpx").isEmpty())
    }

    @Test fun `simulateur de trace`() {
        val a = LatLon(47.0, 6.0)
        val pts = listOf(a, Geo.offset(a, 90.0, 1000.0), Geo.offset(Geo.offset(a, 90.0, 1000.0), 0.0, 1000.0))
        val sim = TrackSimulator(pts, 10.0)
        assertEquals(2000.0, sim.lengthM, 2.0)
        assertEquals(200.0, sim.durationS, 0.5)
        assertEquals(500.0, Geo.distance(a, sim.fixAt(50.0).position), 1.0)
        assertEquals(90f, sim.fixAt(50.0).bearingDeg!!, 1f)
        assertEquals(0f, sim.fixAt(150.0).bearingDeg!!, 1f)
        assertFalse(sim.isFinished(199.0)); assertTrue(sim.isFinished(201.0))
        assertEquals(pts.last(), sim.fixAt(9999.0).position)
    }

    @Test fun `lecture d'une vraie reponse Valhalla`() {
        val body = javaClass.getResource("/valhalla_besancon.json")!!.readText()
        val legs = Valhalla.parse(body)
        assertEquals(1, legs.size)
        val r = legs[0]
        assertTrue(r.points.size > 100)
        assertEquals(47.238, r.points.first().lat, 0.01); assertEquals(6.024, r.points.first().lon, 0.01)
        assertEquals(47.202, r.points.last().lat, 0.01)
        assertEquals(ManeuverType.DEPART, r.maneuvers.first().type)
        assertEquals(ManeuverType.ARRIVE, r.maneuvers.last().type)
        assertEquals(r.points.lastIndex, r.maneuvers.last().shapeIndex)
        assertTrue(r.maneuvers.zipWithNext().all { (a, b) -> a.shapeIndex <= b.shapeIndex })
        assertTrue(r.maneuvers.any { it.type == ManeuverType.ROUNDABOUT && it.exit > 0 })
        // La longueur géométrique colle au résumé du serveur.
        assertEquals(r.distanceM, r.lengthM, r.distanceM * 0.02)
        assertTrue(r.durationS > 600)
    }

    @Test fun `requete Valhalla`() {
        val body = Valhalla.requestBody(listOf(LatLon(1.0, 2.0), LatLon(3.0, 4.0)), TravelProfile.BIKE, headingDeg = 123.4)
        assertTrue(body.contains("\"costing\":\"bicycle\""))
        assertTrue(body.contains("\"heading\":123"))
        assertEquals("pedestrian", Valhalla.costing(TravelProfile.WALK))
    }
}

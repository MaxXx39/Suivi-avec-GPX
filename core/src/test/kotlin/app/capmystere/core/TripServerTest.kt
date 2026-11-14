package app.capmystere.core

import app.capmystere.core.geo.Geo
import app.capmystere.core.model.Challenge
import app.capmystere.core.model.ChallengeKind
import app.capmystere.core.model.ChallengePlacement
import app.capmystere.core.model.ChallengeStatus
import app.capmystere.core.model.ClueFragment
import app.capmystere.core.model.ClueKind
import app.capmystere.core.model.LatLon
import app.capmystere.core.model.MediaRef
import app.capmystere.core.model.PhotoValidation
import app.capmystere.core.model.Stage
import app.capmystere.core.model.StageMessage
import app.capmystere.core.model.TimedClue
import app.capmystere.core.model.TravelerSnapshot
import app.capmystere.core.model.Trip
import app.capmystere.core.server.SessionState
import app.capmystere.core.server.TripServer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripServerTest {
    private val p1 = LatLon(47.10, 6.10)
    private val p2 = LatLon(47.35, 6.45)
    private val p3 = LatLon(48.123456, 7.654321) // destination finale secrète

    private fun letter(id: String, l: String, slot: Int) = ClueFragment(id, ClueKind.LETTER, l, slot)

    private val trip = Trip(
        id = "t1", code = "ABC234", title = "Week-end surprise", clueWord = "COLMAR",
        stages = listOf(
            Stage("s1", "Le Vieux Pont", p1, 80, StageMessage("MESSAGE-UN"), listOf(
                Challenge("c1", ChallengeKind.FREE_TEXT, prompt = "Année sur le pont ?", acceptedAnswers = listOf("1765"), reward = letter("f1", "C", 0), failClue = ClueFragment("f1b", ClueKind.TEXT, "Ça commence par C")),
                Challenge("c1r", ChallengeKind.QUIZ, ChallengePlacement.EN_ROUTE, "En route vers 1 ?", listOf("a", "b"), 1, reward = letter("f0", "O", 1)),
            )),
            Stage("s2", "La Cascade Cachée", p2, 100, StageMessage("MESSAGE-DEUX"), listOf(
                Challenge("c2", ChallengeKind.PHOTO, prompt = "Photo de la cascade", photoValidation = PhotoValidation.HOST, reward = letter("f2", "L", 2)),
                Challenge("c2r", ChallengeKind.RIDDLE, ChallengePlacement.EN_ROUTE, "ENIGME-EN-ROUTE-DEUX", acceptedAnswers = listOf("echo")),
            )),
            Stage("s3", "Colmar", p3, 150, StageMessage("MESSAGE-FINAL"), listOf(
                Challenge("c3", ChallengeKind.FREE_TEXT, prompt = "DEFI-FINAL", acceptedAnswers = listOf("oui")),
            )),
        ),
        timedClues = listOf(TimedClue("tc", 30, ClueFragment("ft", ClueKind.TEXT, "INDICE-TEMPS"))),
    )
    private val start = SessionState("sess", "t1", startedAt = 1_000_000)
    private val json = Json

    private fun snap(s: SessionState, now: Long = 1_000_000): Pair<TravelerSnapshot, String> =
        TripServer.snapshot(trip, s, now).let { it to json.encodeToString(TravelerSnapshot.serializer(), it) }

    @Test fun `au depart le voyageur ne recoit que la cible de la premiere etape`() {
        val (s, raw) = snap(start)
        assertEquals(p1, s.target?.position)
        assertTrue(s.unlocked.isEmpty())
        assertEquals(listOf("c1r"), s.enRouteChallenges.map { it.id })
        assertEquals(6, s.clueWordLength)
        // Rien de la suite ne fuit : ni titres, ni messages, ni coordonnées, ni défis, ni mot mystère.
        for (secret in listOf("Vieux Pont", "MESSAGE-UN", "Cascade", "MESSAGE-DEUX", "Colmar", "COLMAR", "MESSAGE-FINAL", "DEFI-FINAL",
            "ENIGME-EN-ROUTE-DEUX", "47.35", "6.45", "48.123456", "7.654321", "1765", "INDICE-TEMPS")) {
            assertFalse("fuite de « $secret »", raw.contains(secret))
        }
    }

    @Test fun `la validation exige d'etre dans le rayon`() {
        assertNull(TripServer.validateStage(trip, start, 0, Geo.offset(p1, 10.0, 400.0)))
        assertNull("on ne saute pas d'étape", TripServer.validateStage(trip, start, 1, p2))
        val ok = TripServer.validateStage(trip, start, 0, Geo.offset(p1, 10.0, 120.0))
        assertNotNull(ok); assertEquals(1, ok!!.currentIndex)
        // Idempotence : une validation rejouée (file hors ligne) ne fait pas avancer deux fois.
        assertEquals(ok, TripServer.validateStage(trip, ok, 0, p1))
    }

    @Test fun `apres validation on recoit le message de l'etape et la cible suivante seulement`() {
        val s1 = TripServer.validateStage(trip, start, 0, p1)!!
        val (s, raw) = snap(s1)
        assertEquals("MESSAGE-UN", s.unlocked.single().message.text)
        assertEquals(listOf("c1"), s.unlocked.single().challenges.map { it.id })
        assertEquals(p2, s.target?.position)
        assertEquals(listOf("c2r"), s.enRouteChallenges.map { it.id })
        for (secret in listOf("MESSAGE-DEUX", "Cascade", "Colmar", "MESSAGE-FINAL", "48.123456", "1765")) assertFalse(raw.contains(secret))
    }

    @Test fun `fin de voyage`() {
        var s = start
        listOf(p1, p2, p3).forEachIndexed { i, p -> s = TripServer.validateStage(trip, s, i, p)!! }
        assertTrue(s.finished)
        val (sn, _) = snap(s)
        assertNull(sn.target); assertEquals(3, sn.unlocked.size); assertEquals("MESSAGE-FINAL", sn.unlocked.last().message.text)
    }

    @Test fun `reponse libre - reussite, tentatives et indice de consolation`() {
        val s1 = TripServer.validateStage(trip, start, 0, p1)!!
        val good = TripServer.submit(trip, s1, "c1", " 1765 ", null, false, 5)
        assertEquals(ChallengeStatus.SUCCESS, good.status["c1"]); assertEquals(listOf("f1"), good.clues.map { it.id })
        // Une fois réussi, on ne rejoue pas.
        assertEquals(good, TripServer.submit(trip, good, "c1", "x", null, false, 6))

        var bad = s1
        repeat(2) { bad = TripServer.submit(trip, bad, "c1", "1900", null, false, 7L + it) }
        assertEquals(ChallengeStatus.PENDING, bad.status["c1"]); assertTrue(bad.clues.isEmpty())
        bad = TripServer.submit(trip, bad, "c1", "1901", null, false, 9)
        assertEquals(ChallengeStatus.FAILED, bad.status["c1"]); assertEquals(listOf("f1b"), bad.clues.map { it.id })

        val gaveUp = TripServer.submit(trip, s1, "c1", "", null, true, 5)
        assertEquals(ChallengeStatus.FAILED, gaveUp.status["c1"])
    }

    @Test fun `on ne peut pas repondre a un defi pas encore debloque`() {
        assertEquals(start, TripServer.submit(trip, start, "c1", "1765", null, false, 5))   // à l'étape, pas encore atteinte
        assertEquals(start, TripServer.submit(trip, start, "c2r", "echo", null, false, 5))  // tronçon suivant
        assertEquals(start, TripServer.submit(trip, start, "inconnu", "x", null, false, 5))
        val enRoute = TripServer.submit(trip, start, "c1r", "1", null, false, 5)
        assertEquals(ChallengeStatus.SUCCESS, enRoute.status["c1r"])
    }

    @Test fun `photo validee par l'hote`() {
        var s = TripServer.validateStage(trip, start, 0, p1)!!
        s = TripServer.validateStage(trip, s, 1, p2)!!
        assertEquals("sans photo, rien", s, TripServer.submit(trip, s, "c2", "", null, false, 5))
        val sent = TripServer.submit(trip, s, "c2", "", MediaRef(remotePath = "p.jpg"), false, 5, submissionId = "sub1")
        assertEquals(ChallengeStatus.AWAITING_HOST, sent.status["c2"]); assertTrue(sent.clues.isEmpty())
        val ok = TripServer.review(trip, sent, "sub1", true)
        assertEquals(ChallengeStatus.SUCCESS, ok.status["c2"]); assertEquals(listOf("f2"), ok.clues.map { it.id })
        val ko = TripServer.review(trip, sent, "sub1", false)
        assertEquals(ChallengeStatus.FAILED, ko.status["c2"])
        assertEquals(ok, TripServer.review(trip, ok, "sub1", false)) // déjà tranché
    }

    @Test fun `indice programme par le temps`() {
        assertTrue(snap(start, 1_000_000 + 29 * 60_000).first.clues.isEmpty())
        assertEquals(listOf("ft"), snap(start, 1_000_000 + 31 * 60_000L).first.clues.map { it.id })
    }

    @Test fun `passage d'etape force par l'hote et suivi`() {
        val s = TripServer.skipStage(trip, start)
        assertEquals(1, s.currentIndex)
        val t = TripServer.addPositions(s, listOf(p1, p2), 42)
        assertEquals(p2, TripServer.live(t).lastPosition); assertEquals(42, TripServer.live(t).lastSeenAt)
    }
}

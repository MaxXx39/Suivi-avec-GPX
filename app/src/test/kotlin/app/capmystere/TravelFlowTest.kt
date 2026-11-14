package app.capmystere

import android.content.Context
import android.os.Looper
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.capmystere.audio.Narrator
import app.capmystere.core.geo.Geo
import app.capmystere.core.guidance.Maneuver
import app.capmystere.core.guidance.ManeuverType
import app.capmystere.core.guidance.Route
import app.capmystere.core.model.Challenge
import app.capmystere.core.model.ChallengeKind
import app.capmystere.core.model.ChallengeStatus
import app.capmystere.core.model.ClueFragment
import app.capmystere.core.model.ClueKind
import app.capmystere.core.model.LatLon
import app.capmystere.core.model.Stage
import app.capmystere.core.model.StageMessage
import app.capmystere.core.model.TravelProfile
import app.capmystere.core.model.Trip
import app.capmystere.data.Prefs
import app.capmystere.data.backend.BackendRouter
import app.capmystere.data.backend.LocalBackend
import app.capmystere.data.db.CapDatabase
import app.capmystere.data.routing.RoutingProvider
import app.capmystere.location.FusedLocationSource
import app.capmystere.map.OfflineTiles
import app.capmystere.theme.ThemeRepository
import app.capmystere.travel.AnswerOutcome
import app.capmystere.travel.Simulation
import app.capmystere.travel.SyncEngine
import app.capmystere.travel.TravelController
import app.capmystere.travel.TravelPhase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * Bout en bout, sans appareil : un voyage publié sur le backend embarqué est rejoint par code puis
 * parcouru en simulation. Vérifie l'enchaînement guidage → arrivée → révélation → tronçon suivant
 * → fin, et qu'à aucun moment le voyageur ne détient la suite du parcours.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class TravelFlowTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val start = LatLon(47.0, 6.0)
    private val p1 = Geo.offset(start, 90.0, 1500.0)
    private val p2 = Geo.offset(p1, 0.0, 1200.0)

    /** Itinéraire factice : ligne droite densifiée (pas de réseau dans les tests). */
    private val routing = object : RoutingProvider {
        var calls = 0
        override suspend fun route(points: List<LatLon>, profile: TravelProfile, headingDeg: Double?): List<Route> {
            calls++
            val (a, b) = points
            val n = (Geo.distance(a, b) / 40).toInt().coerceAtLeast(1)
            val pts = (0..n).map { Geo.interpolate(a, b, it / n.toDouble()) }
            return listOf(Route(pts, listOf(Maneuver(ManeuverType.DEPART, 0), Maneuver(ManeuverType.ARRIVE, pts.lastIndex)), Geo.distance(a, b), 60.0))
        }
    }

    @Test
    fun `voyage simule de bout en bout`() {
        val db = Room.inMemoryDatabaseBuilder(context, CapDatabase::class.java).allowMainThreadQueries().build()
        val local = LocalBackend(db.trips(), db.cache())
        val backend = BackendRouter(local, null)
        val sync = SyncEngine(context, backend, db.outbox(), db.cache())
        val controller = TravelController(
            context, backend, routing, sync, db.cache(), db.outbox(), Prefs(context), ThemeRepository(context),
            Narrator(context), FusedLocationSource(context), OfflineTiles(context),
        )
        val trip = Trip(
            id = "trip-1", title = "Test", start = start,
            stages = listOf(
                Stage("s1", "PREMIERE", p1, 80, StageMessage("MSG-1"), listOf(
                    Challenge("c1", ChallengeKind.FREE_TEXT, prompt = "?", acceptedAnswers = listOf("Étretat"), reward = ClueFragment("f1", ClueKind.LETTER, "E", 0)),
                )),
                Stage("s2", "DESTINATION-SECRETE", p2, 80, StageMessage("MSG-FINAL")),
            ),
        )
        val sessionId = runBlocking { val published = local.publish(trip); backend.join(published.code).sessionId }

        controller.start(sessionId, Simulation.FollowRoute(start))
        controller.setSimSpeed(4)
        val reveals = ArrayList<String>()
        var sawGuiding = false
        var maxAhead = 0.0
        var answered: AnswerOutcome? = null
        for (tick in 0 until 400) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(500))
            Thread.sleep(3) // laisse respirer les fils d'E/S (Room, DataStore)
            val s = controller.state.value
            if (s.phase == TravelPhase.GUIDING) {
                sawGuiding = true
                maxAhead = maxOf(maxAhead, Geo.cumulative(s.routeAhead).lastOrNull() ?: 0.0)
            }
            // Tant que la destination n'est pas atteinte, rien ne la trahit dans l'état du voyageur.
            if (s.snapshot?.finished == false) {
                assertFalse(s.snapshot.toString().contains("DESTINATION-SECRETE"))
                assertFalse(s.snapshot.toString().contains("MSG-FINAL"))
                if (s.snapshot!!.currentIndex == 0) assertFalse(s.snapshot.toString().contains(p2.lat.toString()))
            }
            val reveal = s.reveal
            if (reveal != null) {
                reveals += reveal.title
                if (reveal.index == 0 && answered == null) {
                    val challenge = reveal.challenges.single()
                    assertEquals(AnswerOutcome.RETRY, runBlocking { controller.answer(challenge, "Fécamp", null) })
                    answered = runBlocking { controller.answer(challenge, "  etretat ", null) }
                }
                controller.dismissReveal()
                controller.dismissClue()
            }
            if (s.phase == TravelPhase.FINISHED && s.reveal == null) break
        }
        val end = controller.state.value
        assertTrue("le guidage a démarré", sawGuiding)
        assertEquals(listOf("PREMIERE", "DESTINATION-SECRETE"), reveals.distinct())
        assertEquals(TravelPhase.FINISHED, end.phase)
        assertTrue(end.snapshot!!.finished)
        assertEquals(AnswerOutcome.SUCCESS, answered)
        assertEquals(ChallengeStatus.SUCCESS, end.statusOf("c1"))
        assertEquals(listOf("f1"), end.snapshot!!.clues.map { it.id })
        assertEquals("un calcul d'itinéraire par tronçon", 2, routing.calls)
        assertTrue("la carte ne reçoit qu'une portion proche de la route ($maxAhead m)", maxAhead <= TravelController.AHEAD_M + TravelController.BEHIND_M + 1)
        // Côté hôte : le suivi en direct a reçu des positions et la réponse.
        val live = runBlocking { backend.live("trip-1") }
        assertNotNull(live)
        assertTrue(live!!.finished && live.trail.isNotEmpty() && live.submissions.any { it.status == ChallengeStatus.SUCCESS })
        controller.stop()
        db.close()
    }
}

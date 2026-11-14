package app.capmystere.core

import app.capmystere.core.model.Challenge
import app.capmystere.core.model.ChallengeKind
import app.capmystere.core.model.ChallengePlacement
import app.capmystere.core.model.ClueFragment
import app.capmystere.core.model.ClueKind
import app.capmystere.core.model.LatLon
import app.capmystere.core.model.MediaRef
import app.capmystere.core.model.PhotoValidation
import app.capmystere.core.model.Stage
import app.capmystere.core.model.StageMessage
import app.capmystere.core.model.TimedClue
import app.capmystere.core.model.Trip
import app.capmystere.core.server.ServerDoc
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ServerDocTest {
    private val trip = Trip(
        id = "11111111-1111-1111-1111-111111111111", code = "ABC234", title = "Week-end surprise", clueWord = "COLMAR",
        stages = listOf(
            Stage("s1", "Le Vieux Pont", LatLon(47.10, 6.10), 80, StageMessage("MESSAGE-UN", image = MediaRef("/data/local/secret.jpg", "trips/11111111-1111-1111-1111-111111111111/a.jpg")), listOf(
                Challenge("c1", ChallengeKind.FREE_TEXT, prompt = "Année sur le pont ?", acceptedAnswers = listOf("1765"), reward = ClueFragment("f1", ClueKind.LETTER, "C", 0), failClue = ClueFragment("f1b", ClueKind.TEXT, "Ça commence par C")),
                Challenge("c1r", ChallengeKind.QUIZ, ChallengePlacement.EN_ROUTE, "En route vers 1 ?", listOf("a", "b"), 1, reward = ClueFragment("f0", ClueKind.LETTER, "O", 1)),
            )),
            Stage("s2", "La Cascade Cachée", LatLon(47.35, 6.45), 100, StageMessage("MESSAGE-DEUX"), listOf(
                Challenge("c2", ChallengeKind.PHOTO, prompt = "Photo de la cascade", photoValidation = PhotoValidation.HOST, reward = ClueFragment("f2", ClueKind.LETTER, "L", 2)),
            )),
            Stage("s3", "Colmar", LatLon(48.123456, 7.654321), 150, StageMessage("MESSAGE-FINAL")),
        ),
        timedClues = listOf(TimedClue("tc", 30, ClueFragment("ft", ClueKind.TEXT, "INDICE-TEMPS"))),
    )

    @Test fun `la projection publique ne contient ni reponses ni chemins locaux`() {
        val doc = ServerDoc.build(trip, maxAttempts = 3)
        val pub = doc["pub"]!!.toString()
        assertFalse(pub.contains("1765"))
        assertFalse(pub.contains("COLMAR"))
        assertFalse(doc.toString().contains("/data/local"))
        assertTrue(pub.contains("trips/11111111-1111-1111-1111-111111111111/a.jpg"))
        val stages = doc["pub"]!!.jsonObject["stages"]!!.jsonArray
        assertEquals(3, stages.size)
        assertEquals(1, stages[0].jsonObject["atStage"]!!.jsonArray.size)
        assertEquals(1, stages[0].jsonObject["enRoute"]!!.jsonArray.size)
        assertTrue(doc["rewards"]!!.jsonObject.keys.containsAll(listOf("c1", "c1r", "c2")))
        // Sert de jeu de données au test SQL (supabase/tests/run.sh).
        File("build/serverdoc.json").apply { parentFile.mkdirs() }.writeText(doc.toString())
    }
}

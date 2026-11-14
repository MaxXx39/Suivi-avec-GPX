package app.capmystere

import android.content.Context
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import app.capmystere.core.guidance.Maneuver
import app.capmystere.core.guidance.ManeuverType
import app.capmystere.core.model.Audience
import app.capmystere.core.model.ChallengeKind
import app.capmystere.core.model.ChallengePlacement
import app.capmystere.core.model.ChallengeStatus
import app.capmystere.core.model.ClueFragment
import app.capmystere.core.model.ClueKind
import app.capmystere.core.model.LatLon
import app.capmystere.core.model.StageMessage
import app.capmystere.core.model.Target
import app.capmystere.core.model.TravelProfile
import app.capmystere.core.model.TravelerChallenge
import app.capmystere.core.model.TravelerSnapshot
import app.capmystere.core.model.UnlockedStage
import app.capmystere.map.MapStyleFactory
import app.capmystere.theme.Cap
import app.capmystere.theme.CapTheme
import app.capmystere.theme.ThemeRepository
import app.capmystere.theme.ThemeSpec
import app.capmystere.travel.InstructionText
import app.capmystere.travel.TravelPhase
import app.capmystere.travel.TravelState
import app.capmystere.ui.common.CapTopBar
import app.capmystere.ui.common.ThemedBackground
import app.capmystere.ui.home.HomeContent
import app.capmystere.ui.host.TripSettingsContent
import app.capmystere.ui.traveler.ChallengeContent
import app.capmystere.ui.traveler.ClueBookContent
import app.capmystere.ui.traveler.JoinContent
import app.capmystere.ui.traveler.JoinState
import app.capmystere.ui.traveler.StageReveal
import app.capmystere.ui.traveler.TravelOverlay
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Planche de captures pour valider la direction artistique sans appareil :
 * `./gradlew :app:testDebugUnitTest -Pscreenshots` → app/build/screenshots/.
 * La carte (vue native MapLibre) n'est pas rendue ici : l'écran de guidage est capturé sur fond
 * transparent, et le style de carte de chaque thème est exporté en JSON à côté.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h891dp-420dpi")
class ScreenshotTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val themes = ThemeRepository(context)
    private val out = File("build/screenshots").apply { mkdirs() }

    private fun shot(name: String, spec: ThemeSpec, audience: Audience = Audience.FRIENDS, night: Boolean = true, content: @Composable () -> Unit) {
        captureRoboImage(File(out, "${spec.id}-$name.png").path) {
            // Mode « inspection » : fige les animations infinies (houle, boussole, brouillard) pour la capture.
            androidx.compose.runtime.CompositionLocalProvider(androidx.compose.ui.platform.LocalInspectionMode provides true) {
                CapTheme(spec, audience, night) { content() }
            }
        }
    }

    private val snapshot = TravelerSnapshot(
        tripId = "t", sessionId = "s", title = "Week-end surprise", themeId = "pirates", audience = Audience.FRIENDS, profile = TravelProfile.CAR,
        currentIndex = 2, target = Target(LatLon(47.2, 6.0), 80),
        unlocked = listOf(
            UnlockedStage(0, "Le belvédère", StageMessage("Premier arrêt. Regarde vers l'est : c'est par là qu'on va, mais chut.")),
            UnlockedStage(1, "La vieille forge", StageMessage("Ici, on forgeait des ancres. Il t'en faudra une pour la suite… Cherche l'année gravée au-dessus de la porte.")),
        ),
        enRouteChallenges = listOf(TravelerChallenge("c1", ChallengeKind.RIDDLE, ChallengePlacement.EN_ROUTE, "J'ai des aiguilles mais ne pique pas, je montre toujours le nord. Qui suis-je ?", hasReward = true)),
        clues = listOf(
            ClueFragment("a", ClueKind.LETTER, "É", 0), ClueFragment("b", ClueKind.LETTER, "R", 2), ClueFragment("c", ClueKind.LETTER, "T", 4),
            ClueFragment("d", ClueKind.TEXT, "On y entend les goélands avant de voir la mer."),
        ),
        clueWordLength = 7,
    )

    @Test
    fun planche() {
        for (spec in themes.themes) {
            for (night in listOf(true, false)) {
                File(out, "${spec.id}-map-${if (night) "night" else "day"}.json").writeText(
                    MapStyleFactory.build(if (night) spec.map.night else spec.map.day, "https://tiles.openfreemap.org", MapStyleFactory.Options(forTraveler = true, streetNames = false))
                )
            }
            shot("01-accueil", spec) { HomeContent(null, false, {}, {}, {}, {}) }
            shot("02-rejoindre", spec) {
                ThemedBackground(Modifier.fillMaxSize()) {
                    androidx.compose.foundation.layout.Column { CapTopBar("Rejoindre", onBack = {}); JoinContent("KX7", JoinState(), {}, {}, {}, Modifier.padding(horizontal = 24.dp)) }
                }
            }
            val state = TravelState(phase = TravelPhase.GUIDING, snapshot = snapshot, position = LatLon(47.0, 6.0), night = true, moving = false)
            val turn = InstructionText("Dans 2,3 km", "Tourne à droite", "puis à gauche", Maneuver(ManeuverType.RIGHT, 0))
            val rbt = InstructionText("Dans 400 m", "Au rond-point, 2ᵉ sortie", null, Maneuver(ManeuverType.ROUNDABOUT, 0, exit = 2))
            for ((name, ins, night) in listOf(Triple("03-guidage-nuit", turn, true), Triple("04-guidage-rondpoint-nuit", rbt, true), Triple("05-guidage-jour", turn, false))) {
                shot(name, spec, night = night) { TravelOverlay(state, ins, voiceOn = true, {}, {}, {}, {}, {}, {}) }
            }
            shot("06-etape", spec) { StageReveal(snapshot.unlocked[1], finished = false, resolve = { null }, pending = 1, onChallenges = {}, onContinue = {}) }
            shot("07-carnet", spec) {
                ThemedBackground(Modifier.fillMaxSize()) {
                    androidx.compose.foundation.layout.Column {
                        CapTopBar(Cap.tone.word("clueBook", "Carnet"), onBack = {}, overline = snapshot.title)
                        ClueBookContent(snapshot, { null }, Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 22.dp))
                    }
                }
            }
            shot("08-defi", spec) {
                ThemedBackground(Modifier.fillMaxSize()) {
                    androidx.compose.foundation.layout.Column {
                        CapTopBar(Cap.tone.word("challenge", "Défi"), onBack = {}, overline = "Énigme")
                        ChallengeContent(snapshot.enRouteChallenges[0], ChallengeStatus.PENDING, null, false, null, {}, {}, {}, {}, {}, Modifier.padding(horizontal = 22.dp))
                    }
                }
            }
            shot("09-enfants-guidage", spec, audience = Audience.KIDS) { TravelOverlay(state.copy(snapshot = snapshot.copy(audience = Audience.KIDS)), turn, true, {}, {}, {}, {}, {}, {}) }
        }
        val pirates = themes.get("pirates")
        shot("10-reglages-voyage", pirates) {
            ThemedBackground(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize()) {
                    TripSettingsContent(
                        app.capmystere.core.model.Trip("t", title = "Week-end surprise", clueWord = "ÉTRETAT"), themes.themes, { null }, { _, _ -> }, {}, {},
                        Modifier.padding(horizontal = 22.dp, vertical = 40.dp),
                    )
                }
            }
        }
    }
}

package app.capmystere.ui.traveler

import android.content.Context
import android.media.MediaPlayer
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.capmystere.R
import app.capmystere.core.model.MediaRef
import app.capmystere.core.model.TravelerChallenge
import app.capmystere.data.Prefs
import app.capmystere.data.Settings
import app.capmystere.data.MediaFiles
import app.capmystere.data.backend.TripBackend
import app.capmystere.theme.AudienceProfile
import app.capmystere.theme.ThemeRepository
import app.capmystere.travel.AnswerOutcome
import app.capmystere.travel.TravelController
import app.capmystere.travel.TravelService
import app.capmystere.ui.common.ButtonKind
import app.capmystere.ui.common.CapButton
import app.capmystere.ui.common.Glyph
import app.capmystere.ui.common.GlyphIcon
import coil.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class TravelViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    val controller: TravelController,
    val themes: ThemeRepository,
    private val prefs: Prefs,
    private val backend: TripBackend,
    private val media: MediaFiles,
) : ViewModel() {
    val state = controller.state
    val settings = prefs.settings.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), Settings())

    /** Voix effective : réglage explicite, sinon défaut du public (activée pour les enfants). */
    fun voiceOn(s: Settings): Boolean = s.voice ?: (state.value.snapshot?.let { AudienceProfile.of(it.audience).voiceByDefault } ?: false)

    fun toggleVoice() = viewModelScope.launch { prefs.setVoice(!voiceOn(settings.value)) }

    /** Quitte le voyage. Une simulation s'arrête simplement ; un vrai voyage est abandonné sur cet appareil. */
    fun exit() {
        if (state.value.simulated) controller.stop() else { controller.leave(); TravelService.stop(context) }
    }

    suspend fun resolve(ref: MediaRef): File? = backend.resolve(ref)

    suspend fun answer(challenge: TravelerChallenge, answer: String, photo: File? = null, gaveUp: Boolean = false): AnswerOutcome =
        controller.answer(challenge, answer, photo, gaveUp)

    fun newPhotoFile(): File = media.newFile("jpg")
}

/** Image d'un média (locale ou téléchargée à la demande). */
@Composable
fun MediaImage(ref: MediaRef, resolve: suspend (MediaRef) -> File?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    val file by produceState<File?>(null, ref) { value = resolve(ref) }
    if (file != null) AsyncImage(model = file, contentDescription = null, modifier = modifier, contentScale = contentScale) else Box(modifier)
}

/** Bouton lecture/arrêt pour un message audio. */
@Composable
fun AudioButton(ref: MediaRef, resolve: suspend (MediaRef) -> File?, modifier: Modifier = Modifier) {
    val file by produceState<File?>(null, ref) { value = resolve(ref) }
    var playing by remember { mutableStateOf(false) }
    val player = remember { MediaPlayer() }
    DisposableEffect(Unit) { onDispose { runCatching { player.release() } } }
    CapButton(
        stringResource(if (playing) R.string.audio_stop else R.string.audio_play),
        onClick = {
            val f = file ?: return@CapButton
            runCatching {
                if (playing) { player.stop(); playing = false } else {
                    player.reset(); player.setDataSource(f.absolutePath); player.prepare(); player.start(); playing = true
                    player.setOnCompletionListener { playing = false }
                }
            }
        },
        modifier = modifier, kind = ButtonKind.GHOST, enabled = file != null,
        leading = { GlyphIcon(if (playing) Glyph.STOP else Glyph.PLAY, size = 20.dp) },
    )
}

package app.capmystere.ui.traveler

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.capmystere.R
import app.capmystere.core.model.Audience
import app.capmystere.core.model.ChallengeKind
import app.capmystere.core.model.ChallengeStatus
import app.capmystere.core.model.PhotoValidation
import app.capmystere.core.model.TravelerChallenge
import app.capmystere.theme.Cap
import app.capmystere.theme.CapTheme
import app.capmystere.travel.AnswerOutcome
import app.capmystere.ui.common.BodyText
import app.capmystere.ui.common.ButtonKind
import app.capmystere.ui.common.CameraGate
import app.capmystere.ui.common.CapButton
import app.capmystere.ui.common.CapCard
import app.capmystere.ui.common.CapTextField
import app.capmystere.ui.common.CapTopBar
import app.capmystere.ui.common.DisplayText
import app.capmystere.ui.common.Glyph
import app.capmystere.ui.common.GlyphIcon
import app.capmystere.ui.common.Illustration
import app.capmystere.ui.common.Label
import app.capmystere.ui.common.PhotoCapture
import app.capmystere.ui.common.QrScanner
import app.capmystere.ui.common.ThemedBackground
import app.capmystere.ui.common.cardShape
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun ChallengeScreen(challengeId: String, onBack: () -> Unit, vm: TravelViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snapshot = state.snapshot
    val challenge = remember(snapshot, challengeId) { state.availableChallenges().firstOrNull { it.id == challengeId } }
    val theme = remember(snapshot?.themeId) { vm.themes.get(snapshot?.themeId) }
    val scope = rememberCoroutineScope()
    var outcome by remember { mutableStateOf<AnswerOutcome?>(null) }
    var busy by remember { mutableStateOf(false) }
    var camera by remember { mutableStateOf(false) }
    var photo by remember { mutableStateOf<File?>(null) }

    fun submit(answer: String, file: File? = null, gaveUp: Boolean = false) {
        val c = challenge ?: return
        if (busy) return
        busy = true
        scope.launch { outcome = vm.answer(c, answer, file, gaveUp); busy = false }
    }

    CapTheme(theme, snapshot?.audience ?: Audience.FRIENDS, night = state.night) {
        ThemedBackground(Modifier.fillMaxSize()) {
            if (challenge == null) { CapTopBar("", onBack = onBack); return@ThemedBackground }
            val isPhoto = challenge.kind == ChallengeKind.PHOTO || challenge.kind == ChallengeKind.MISSION
            when {
                camera && challenge.kind == ChallengeKind.QR_SCAN -> Column(Modifier.fillMaxSize()) {
                    CapTopBar(stringResource(R.string.kind_qr), onBack = { camera = false })
                    CameraGate(stringResource(R.string.perm_camera_qr)) { QrScanner({ camera = false; submit(it) }, Modifier.fillMaxSize()) }
                }
                camera && isPhoto -> Column(Modifier.fillMaxSize()) {
                    CapTopBar(stringResource(R.string.kind_photo), onBack = { camera = false })
                    CameraGate(stringResource(R.string.perm_camera_photo)) {
                        val file = remember { vm.newPhotoFile() }
                        PhotoCapture(file, { photo = it; camera = false }, Modifier.fillMaxSize())
                    }
                }
                else -> Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
                    CapTopBar(Cap.tone.word("challenge", stringResource(R.string.word_challenge)), onBack = onBack, overline = stringResource(kindLabel(challenge.kind)))
                    ChallengeContent(
                        challenge = challenge, status = state.statusOf(challenge.id), outcome = outcome, busy = busy, photo = photo,
                        onAnswer = { submit(it) }, onOpenCamera = { camera = true }, onSendPhoto = { photo?.let { submit("", it) } },
                        onGiveUp = { submit("", gaveUp = true) }, onBack = onBack,
                        modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun ChallengeContent(
    challenge: TravelerChallenge, status: ChallengeStatus, outcome: AnswerOutcome?, busy: Boolean, photo: File?,
    onAnswer: (String) -> Unit, onOpenCamera: () -> Unit, onSendPhoto: () -> Unit, onGiveUp: () -> Unit, onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tone = Cap.tone
    var text by remember { mutableStateOf("") }
    var showHint by remember { mutableStateOf(false) }
    val done = status != ChallengeStatus.PENDING || outcome == AnswerOutcome.QUEUED
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Spacer(Modifier.height(4.dp))
        DisplayText(challenge.prompt, style = Cap.type.display.copy(fontSize = Cap.type.title.fontSize * 1.15f, lineHeight = Cap.type.title.lineHeight * 1.15f))

        if (done) {
            // Verdict
            val (title, body, good) = when {
                status == ChallengeStatus.SUCCESS -> Triple(tone.narration("challengeSuccess", stringResource(R.string.challenge_success)), stringResource(if (challenge.hasReward) R.string.challenge_reward else R.string.challenge_no_reward), true)
                status == ChallengeStatus.FAILED -> Triple(tone.narration("challengeFail", stringResource(R.string.challenge_fail)), stringResource(R.string.challenge_fail_sub), false)
                outcome == AnswerOutcome.QUEUED -> Triple(stringResource(R.string.challenge_queued), tone.narration("pendingSync", stringResource(R.string.challenge_queued_sub)), true)
                else -> Triple(stringResource(R.string.challenge_awaiting), stringResource(R.string.challenge_awaiting_sub), true)
            }
            CapCard(Modifier.fillMaxWidth(), highlighted = true) {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                    if (good) Illustration("clue", size = 72.dp) else GlyphIcon(Glyph.CLOSE, size = 56.dp, tint = Cap.colors.danger)
                    Spacer(Modifier.height(12.dp))
                    Text(title, style = Cap.type.title, color = if (good) Cap.colors.text else Cap.colors.danger, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(6.dp))
                    BodyText(body, color = Cap.colors.textMuted, textAlign = TextAlign.Center)
                }
            }
            CapButton(stringResource(R.string.back_to_trip), onBack, Modifier.fillMaxWidth())
        } else {
            when (challenge.kind) {
                ChallengeKind.QUIZ -> challenge.options.forEachIndexed { i, option ->
                    CapCard(Modifier.fillMaxWidth(), onClick = if (busy) null else ({ onAnswer(i.toString()) })) {
                        Text(option, style = Cap.type.bodyLarge, color = Cap.colors.text)
                    }
                }
                ChallengeKind.FREE_TEXT, ChallengeKind.RIDDLE, ChallengeKind.FIND_ON_SITE -> {
                    CapTextField(text, { text = it }, stringResource(R.string.challenge_answer))
                    CapButton(stringResource(R.string.challenge_submit), { onAnswer(text) }, Modifier.fillMaxWidth(), enabled = text.isNotBlank() && !busy)
                }
                ChallengeKind.QR_SCAN -> CapButton(
                    stringResource(R.string.challenge_scan), onOpenCamera, Modifier.fillMaxWidth(), enabled = !busy,
                    leading = { GlyphIcon(Glyph.QR, size = 22.dp, tint = Cap.colors.onPrimary) },
                )
                ChallengeKind.PHOTO, ChallengeKind.MISSION -> {
                    if (photo != null) {
                        AsyncImage(photo, null, Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(cardShape()), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
                        CapButton(stringResource(R.string.challenge_send_photo), onSendPhoto, Modifier.fillMaxWidth(), enabled = !busy)
                        CapButton(stringResource(R.string.challenge_retake), onOpenCamera, Modifier.fillMaxWidth(), kind = ButtonKind.GHOST)
                    } else {
                        if (challenge.photoValidation == PhotoValidation.HOST) BodyText(stringResource(R.string.challenge_host_validation), color = Cap.colors.textMuted)
                        CapButton(
                            stringResource(R.string.challenge_take_photo), onOpenCamera, Modifier.fillMaxWidth(),
                            leading = { GlyphIcon(Glyph.CAMERA, size = 22.dp, tint = Cap.colors.onPrimary) },
                        )
                    }
                }
            }
            AnimatedVisibility(outcome == AnswerOutcome.RETRY) {
                BodyText(stringResource(R.string.challenge_retry), color = Cap.colors.danger, style = Cap.type.bodyLarge)
            }
            if (challenge.hint.isNotBlank()) {
                if (showHint) CapCard(Modifier.fillMaxWidth()) { Label(stringResource(R.string.challenge_hint)); Spacer(Modifier.height(4.dp)); BodyText(challenge.hint) }
                else CapButton(stringResource(R.string.challenge_show_hint), { showHint = true }, Modifier.fillMaxWidth(), kind = ButtonKind.GHOST)
            }
            CapButton(stringResource(R.string.challenge_give_up), onGiveUp, Modifier.fillMaxWidth(), kind = ButtonKind.GHOST, enabled = !busy)
        }
        Spacer(Modifier.height(20.dp))
    }
}

package app.capmystere.ui.host

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.capmystere.R
import app.capmystere.core.model.Challenge
import app.capmystere.core.model.ChallengeKind
import app.capmystere.core.model.ChallengePlacement
import app.capmystere.core.model.ClueFragment
import app.capmystere.core.model.ClueKind
import app.capmystere.core.model.PhotoValidation
import app.capmystere.core.model.Stage
import app.capmystere.theme.Cap
import app.capmystere.theme.CapTheme
import app.capmystere.theme.SuggestionSpec
import app.capmystere.ui.common.BodyText
import app.capmystere.ui.common.ButtonKind
import app.capmystere.ui.common.CapButton
import app.capmystere.ui.common.CapCard
import app.capmystere.ui.common.CapChip
import app.capmystere.ui.common.CapTextField
import app.capmystere.ui.common.CapTopBar
import app.capmystere.ui.common.Glyph
import app.capmystere.ui.common.GlyphIcon
import app.capmystere.ui.common.IconAction
import app.capmystere.ui.common.Label
import app.capmystere.ui.common.QrCode
import app.capmystere.ui.common.ThemedBackground
import app.capmystere.ui.common.cardShape
import app.capmystere.ui.common.hasPermission
import app.capmystere.ui.traveler.AudioButton
import app.capmystere.ui.traveler.MediaImage
import app.capmystere.ui.traveler.kindLabel
import java.util.UUID

@Composable
fun StageEditorScreen(vm: EditorViewModel, stageId: String, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val trip = state.trip ?: return
    val index = trip.stages.indexOfFirst { it.id == stageId }
    val stage = trip.stages.getOrNull(index) ?: return
    val theme = remember(trip.themeId) { vm.themes.get(trip.themeId) }
    var editing by remember { mutableStateOf<Challenge?>(null) }
    val context = LocalContext.current
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> if (uri != null) vm.setStageImage(stageId, uri) }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) vm.toggleRecording(stageId) }

    CapTheme(theme, trip.audience) {
        ThemedBackground(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().navigationBarsPadding().imePadding()) {
                val current = editing
                if (current != null) {
                    CapTopBar(stringResource(kindLabel(current.kind)), onBack = { editing = null }, overline = Cap.tone.word("challenge", stringResource(R.string.word_challenge)))
                    ChallengeEditor(
                        challenge = current, clueWord = trip.clueWord, hasPieces = trip.cluePieces.isNotEmpty(),
                        nextLetter = { vm.nextLetter(current.id) }, nextPiece = { vm.nextPiece(current.id) },
                        onChange = { editing = it },
                        onSave = { vm.upsertChallenge(stageId, current); editing = null },
                        onDelete = { vm.removeChallenge(stageId, current.id); editing = null },
                        modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
                    )
                } else {
                    CapTopBar(stage.title.ifBlank { stringResource(R.string.editor_stage_n, index + 1) }, onBack = onBack, overline = "${Cap.tone.word("stage", stringResource(R.string.word_stage))} ${index + 1}")
                    StageEditorContent(
                        stage = stage, isLast = index == trip.stages.lastIndex, recording = state.recording, suggestions = Cap.tone.suggestions,
                        resolve = vm::resolve,
                        onUpdate = { f -> vm.updateStage(stageId, f) },
                        onPickImage = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                        onClearImage = { vm.setStageImage(stageId, null) },
                        onRecord = { if (state.recording || context.hasPermission(Manifest.permission.RECORD_AUDIO)) vm.toggleRecording(stageId) else micLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        onClearAudio = { vm.clearAudio(stageId) },
                        onEditChallenge = { editing = it },
                        modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
                    )
                }
            }
        }
    }
}

fun newChallenge(kind: ChallengeKind): Challenge = Challenge(
    id = UUID.randomUUID().toString(), kind = kind,
    options = if (kind == ChallengeKind.QUIZ) listOf("", "", "") else emptyList(),
    acceptedAnswers = if (kind == ChallengeKind.QR_SCAN) listOf("CAP-" + UUID.randomUUID().toString().take(8).uppercase()) else emptyList(),
)

fun SuggestionSpec.toChallenge(): Challenge = newChallenge(runCatching { ChallengeKind.valueOf(kind) }.getOrDefault(ChallengeKind.FREE_TEXT)).let {
    it.copy(prompt = prompt, options = options.ifEmpty { it.options }, correctOption = correctOption, acceptedAnswers = acceptedAnswers.ifEmpty { it.acceptedAnswers })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StageEditorContent(
    stage: Stage, isLast: Boolean, recording: Boolean, suggestions: List<SuggestionSpec>,
    resolve: suspend (app.capmystere.core.model.MediaRef) -> java.io.File?,
    onUpdate: ((Stage) -> Stage) -> Unit, onPickImage: () -> Unit, onClearImage: () -> Unit, onRecord: () -> Unit, onClearAudio: () -> Unit,
    onEditChallenge: (Challenge) -> Unit, modifier: Modifier = Modifier,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        if (isLast) CapCard(Modifier.fillMaxWidth(), highlighted = true) { BodyText(stringResource(R.string.stage_is_final)) }
        CapTextField(stage.title, { v -> onUpdate { it.copy(title = v) } }, stringResource(R.string.stage_title_label), keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))

        // Message débloqué à l'arrivée
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Label(stringResource(R.string.stage_message), color = Cap.colors.primary)
            BodyText(stringResource(R.string.stage_message_sub), color = Cap.colors.textMuted)
            CapTextField(
                stage.message.text, { v -> onUpdate { it.copy(message = it.message.copy(text = v)) } }, stringResource(R.string.stage_message_label),
                singleLine = false, minLines = 3, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
            )
            stage.message.image?.let { MediaImage(it, resolve, Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(cardShape())) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                CapButton(stringResource(if (stage.message.image == null) R.string.pick_image else R.string.change_image), onPickImage, kind = ButtonKind.GHOST, leading = { GlyphIcon(Glyph.IMAGE, size = 20.dp) })
                if (stage.message.image != null) CapButton(stringResource(R.string.remove), onClearImage, kind = ButtonKind.GHOST)
                CapButton(
                    stringResource(if (recording) R.string.audio_stop_recording else if (stage.message.audio == null) R.string.audio_record else R.string.audio_rerecord),
                    onRecord, kind = if (recording) ButtonKind.DANGER else ButtonKind.GHOST, leading = { GlyphIcon(if (recording) Glyph.STOP else Glyph.MIC, size = 20.dp) },
                )
                stage.message.audio?.let { if (!recording) { AudioButton(it, resolve); CapButton(stringResource(R.string.remove), onClearAudio, kind = ButtonKind.GHOST) } }
            }
        }

        // Défis
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Label(Cap.tone.word("challenge", stringResource(R.string.word_challenge)), color = Cap.colors.primary)
            BodyText(stringResource(R.string.stage_challenges_sub), color = Cap.colors.textMuted)
            for (c in stage.challenges) CapCard(Modifier.fillMaxWidth(), onClick = { onEditChallenge(c) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Label(stringResource(kindLabel(c.kind)) + " · " + stringResource(if (c.placement == ChallengePlacement.AT_STAGE) R.string.placement_at_stage else R.string.placement_en_route))
                        Spacer(Modifier.height(4.dp))
                        BodyText(c.prompt.ifBlank { stringResource(R.string.challenge_no_prompt) }, style = Cap.type.bodyLarge)
                    }
                    GlyphIcon(Glyph.EDIT, tint = Cap.colors.textMuted)
                }
            }
            Label(stringResource(R.string.stage_add_challenge))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (k in ChallengeKind.entries) CapChip("+ " + stringResource(kindLabel(k)), false, { onEditChallenge(newChallenge(k)) })
            }
            if (suggestions.isNotEmpty()) {
                Label(stringResource(R.string.stage_suggestions))
                for (s in suggestions) CapCard(Modifier.fillMaxWidth(), onClick = { onEditChallenge(s.toChallenge()) }) {
                    Label(stringResource(kindLabel(runCatching { ChallengeKind.valueOf(s.kind) }.getOrDefault(ChallengeKind.FREE_TEXT))), color = Cap.colors.secondary)
                    Spacer(Modifier.height(4.dp))
                    BodyText(s.prompt)
                }
            }
        }
        Spacer(Modifier.height(20.dp))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChallengeEditor(
    challenge: Challenge, clueWord: String, hasPieces: Boolean, nextLetter: () -> ClueFragment?, nextPiece: () -> ClueFragment?,
    onChange: (Challenge) -> Unit, onSave: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier,
) {
    val c = challenge
    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        CapTextField(c.prompt, { onChange(c.copy(prompt = it)) }, stringResource(R.string.ce_prompt), singleLine = false, minLines = 2, keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences))

        when (c.kind) {
            ChallengeKind.QUIZ -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label(stringResource(R.string.ce_options))
                c.options.forEachIndexed { i, o ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CapChip(if (c.correctOption == i) "✓" else "${i + 1}", c.correctOption == i, { onChange(c.copy(correctOption = i)) })
                        CapTextField(o, { v -> onChange(c.copy(options = c.options.toMutableList().apply { set(i, v) })) }, stringResource(R.string.ce_option_n, i + 1), Modifier.weight(1f))
                        if (c.options.size > 2) IconAction(Glyph.TRASH, {
                            onChange(c.copy(options = c.options.filterIndexed { k, _ -> k != i }, correctOption = c.correctOption.coerceAtMost(c.options.size - 2)))
                        }, contentDescription = stringResource(R.string.delete), tint = Cap.colors.textMuted)
                    }
                }
                if (c.options.size < 5) CapChip("+ " + stringResource(R.string.ce_add_option), false, { onChange(c.copy(options = c.options + "")) })
                BodyText(stringResource(R.string.ce_quiz_hint), color = Cap.colors.textMuted)
            }
            ChallengeKind.FREE_TEXT, ChallengeKind.RIDDLE, ChallengeKind.FIND_ON_SITE -> {
                CapTextField(
                    c.acceptedAnswers.joinToString("\n"), { v -> onChange(c.copy(acceptedAnswers = v.split('\n'))) },
                    stringResource(R.string.ce_answers), singleLine = false, minLines = 2,
                )
                BodyText(stringResource(R.string.ce_answers_hint), color = Cap.colors.textMuted)
            }
            ChallengeKind.QR_SCAN -> Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val payload = c.acceptedAnswers.firstOrNull().orEmpty()
                CapTextField(payload, { v -> onChange(c.copy(acceptedAnswers = listOf(v))) }, stringResource(R.string.ce_qr_payload))
                if (payload.isNotBlank()) QrCode(payload, size = 200.dp)
                BodyText(stringResource(R.string.ce_qr_hint), color = Cap.colors.textMuted)
            }
            ChallengeKind.PHOTO, ChallengeKind.MISSION -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Label(stringResource(R.string.ce_photo_validation))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CapChip(stringResource(R.string.ce_photo_auto), c.photoValidation == PhotoValidation.AUTO, { onChange(c.copy(photoValidation = PhotoValidation.AUTO)) })
                    CapChip(stringResource(R.string.ce_photo_host), c.photoValidation == PhotoValidation.HOST, { onChange(c.copy(photoValidation = PhotoValidation.HOST)) })
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label(stringResource(R.string.ce_placement))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CapChip(stringResource(R.string.placement_at_stage), c.placement == ChallengePlacement.AT_STAGE, { onChange(c.copy(placement = ChallengePlacement.AT_STAGE)) })
                CapChip(stringResource(R.string.placement_en_route), c.placement == ChallengePlacement.EN_ROUTE, { onChange(c.copy(placement = ChallengePlacement.EN_ROUTE)) })
            }
            BodyText(stringResource(R.string.ce_placement_hint), color = Cap.colors.textMuted)
        }

        CapTextField(c.hint, { onChange(c.copy(hint = it)) }, stringResource(R.string.ce_hint), singleLine = false)

        // Récompense : un fragment d'indice sur la destination
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label(stringResource(R.string.ce_reward), color = Cap.colors.primary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CapChip(stringResource(R.string.ce_reward_none), c.reward == null, { onChange(c.copy(reward = null)) })
                CapChip(stringResource(R.string.ce_reward_letter), c.reward?.kind == ClueKind.LETTER, { nextLetter()?.let { onChange(c.copy(reward = it)) } })
                CapChip(stringResource(R.string.ce_reward_text), c.reward?.kind == ClueKind.TEXT, { onChange(c.copy(reward = ClueFragment(UUID.randomUUID().toString(), ClueKind.TEXT))) })
                CapChip(stringResource(R.string.ce_reward_piece), c.reward?.kind == ClueKind.IMAGE_PIECE, { nextPiece()?.let { onChange(c.copy(reward = it)) } })
            }
            when (c.reward?.kind) {
                ClueKind.LETTER -> BodyText(stringResource(R.string.ce_reward_letter_value, c.reward!!.text, c.reward!!.slot + 1, clueWord), color = Cap.colors.textMuted)
                ClueKind.TEXT -> CapTextField(c.reward!!.text, { v -> onChange(c.copy(reward = c.reward!!.copy(text = v))) }, stringResource(R.string.ce_reward_text_label), singleLine = false)
                ClueKind.IMAGE_PIECE -> BodyText(stringResource(R.string.ce_reward_piece_value, c.reward!!.slot + 1), color = Cap.colors.textMuted)
                null -> if (clueWord.isBlank() && !hasPieces) BodyText(stringResource(R.string.ce_reward_setup_hint), color = Cap.colors.textMuted)
            }
            CapTextField(
                c.failClue?.text.orEmpty(),
                { v -> onChange(c.copy(failClue = if (v.isBlank()) null else (c.failClue ?: ClueFragment(UUID.randomUUID().toString(), ClueKind.TEXT)).copy(text = v))) },
                stringResource(R.string.ce_fail_clue), singleLine = false,
            )
        }

        CapButton(stringResource(R.string.save), onSave, Modifier.fillMaxWidth(), enabled = c.prompt.isNotBlank())
        CapButton(stringResource(R.string.delete), onDelete, Modifier.fillMaxWidth(), kind = ButtonKind.DANGER)
        Spacer(Modifier.height(20.dp))
    }
}

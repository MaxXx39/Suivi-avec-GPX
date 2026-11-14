package app.capmystere.ui.host

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.capmystere.BuildConfig
import app.capmystere.R
import app.capmystere.core.model.ChallengeStatus
import app.capmystere.core.model.LiveSession
import app.capmystere.core.model.MediaRef
import app.capmystere.core.model.Trip
import app.capmystere.map.HostMap
import app.capmystere.map.StageMarker
import app.capmystere.theme.Cap
import app.capmystere.theme.CapTheme
import app.capmystere.ui.common.BodyText
import app.capmystere.ui.common.ButtonKind
import app.capmystere.ui.common.CapButton
import app.capmystere.ui.common.CapCard
import app.capmystere.ui.common.CapChip
import app.capmystere.ui.common.CapTextField
import app.capmystere.ui.common.CapTopBar
import app.capmystere.ui.common.DisplayText
import app.capmystere.ui.common.Glyph
import app.capmystere.ui.common.GlyphIcon
import app.capmystere.ui.common.Label
import app.capmystere.ui.common.QrCode
import app.capmystere.ui.common.ThemedBackground
import app.capmystere.ui.common.cardShape
import app.capmystere.ui.traveler.ConfirmDialog
import app.capmystere.ui.traveler.MediaImage
import app.capmystere.ui.traveler.kindLabel
import java.io.File

fun shareLink(code: String): String = BuildConfig.SHARE_BASE_URL + code

@Composable
fun ShareScreen(vm: EditorViewModel, onBack: () -> Unit, onLive: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val trip = state.trip ?: return
    val theme = remember(trip.themeId) { vm.themes.get(trip.themeId) }
    val context = LocalContext.current
    CapTheme(theme, trip.audience) {
        ThemedBackground(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().navigationBarsPadding()) {
                CapTopBar(stringResource(R.string.share_title), onBack = onBack, overline = trip.title.ifBlank { null })
                ShareContent(
                    trip = trip, publishing = state.publishing, error = state.publishError, dirty = state.dirty, isRemote = vm.isRemote,
                    onPublish = vm::publish, onLive = onLive,
                    onShareLink = {
                        val text = context.getString(R.string.share_message, trip.code, shareLink(trip.code))
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
                    },
                    modifier = Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
                )
            }
        }
    }
}

@Composable
fun ShareContent(trip: Trip, publishing: Boolean, error: String?, dirty: Boolean, isRemote: Boolean, onPublish: () -> Unit, onLive: () -> Unit, onShareLink: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (!trip.published) {
            Spacer(Modifier.height(8.dp))
            DisplayText(stringResource(R.string.share_ready), textAlign = TextAlign.Center)
            BodyText(stringResource(R.string.share_ready_sub), color = Cap.colors.textMuted, textAlign = TextAlign.Center, style = Cap.type.bodyLarge)
        } else {
            Label(stringResource(R.string.share_code), color = Cap.colors.primary)
            Text(trip.code, style = Cap.type.hero.copy(letterSpacing = 0.12.em), color = Cap.colors.text)
            QrCode(shareLink(trip.code), size = 230.dp)
            BodyText(stringResource(R.string.share_how), color = Cap.colors.textMuted, textAlign = TextAlign.Center)
            CapButton(stringResource(R.string.share_send), onShareLink, Modifier.fillMaxWidth(), leading = { GlyphIcon(Glyph.SHARE, size = 22.dp, tint = Cap.colors.onPrimary) })
            CapButton(stringResource(R.string.share_live), onLive, Modifier.fillMaxWidth(), kind = ButtonKind.GHOST, leading = { GlyphIcon(Glyph.LIVE, size = 22.dp) })
        }
        if (!isRemote) CapCard(Modifier.fillMaxWidth()) { BodyText(stringResource(R.string.share_local_notice), color = Cap.colors.textMuted) }
        if (error != null) BodyText(stringResource(R.string.share_error, error), color = Cap.colors.danger, textAlign = TextAlign.Center)
        if (!trip.published || dirty) CapButton(
            stringResource(if (publishing) R.string.share_publishing else if (trip.published) R.string.share_republish else R.string.share_publish),
            onPublish, Modifier.fillMaxWidth(), enabled = !publishing && trip.stages.isNotEmpty(), kind = if (trip.published) ButtonKind.GHOST else ButtonKind.PRIMARY,
        )
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
fun LiveScreen(vm: EditorViewModel, onBack: () -> Unit) {
    val state by vm.state.collectAsStateWithLifecycle()
    val trip = state.trip ?: return
    val theme = remember(trip.themeId) { vm.themes.get(trip.themeId) }
    var confirmSkip by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { vm.watchLive(); onDispose { vm.stopLive() } }
    CapTheme(theme, trip.audience) {
        Box(Modifier.fillMaxSize().background(Cap.colors.background)) {
            Column(Modifier.fillMaxSize().imePadding()) {
                val live = state.live
                val markers = remember(trip.stages, live?.currentIndex) { trip.stages.mapIndexed { i, st -> StageMarker("${i + 1}", st.position, st.radiusM, i == live?.currentIndex) } }
                Box(Modifier.fillMaxWidth().weight(0.42f)) {
                    HostMap(
                        stages = markers, route = remember(state.legs) { state.legs.flatMap { it.points } }, modifier = Modifier.fillMaxSize(),
                        traveler = live?.lastPosition, trail = live?.trail.orEmpty(), focus = live?.lastPosition, fitKey = trip.id,
                    )
                    Box(Modifier.statusBarsPadding().padding(6.dp).clip(androidx.compose.foundation.shape.CircleShape).background(Cap.colors.surface)) {
                        app.capmystere.ui.common.IconAction(Glyph.BACK, onBack, contentDescription = stringResource(R.string.back))
                    }
                }
                LivePanel(
                    trip = trip, live = live, error = state.liveError, resolve = vm::resolve,
                    onSend = vm::sendMessage, onReview = vm::review, onSkip = { confirmSkip = true },
                    modifier = Modifier.fillMaxWidth().weight(0.58f).background(Cap.colors.surface).navigationBarsPadding().verticalScroll(rememberScrollState()).padding(18.dp),
                )
            }
            if (confirmSkip) ConfirmDialog(
                stringResource(R.string.live_skip_title), stringResource(R.string.live_skip_body), stringResource(R.string.live_skip),
                onConfirm = { vm.skipStage(); confirmSkip = false }, onDismiss = { confirmSkip = false },
            )
        }
    }
}

@Composable
fun LivePanel(
    trip: Trip, live: LiveSession?, error: Boolean, resolve: suspend (MediaRef) -> File?,
    onSend: (String, Boolean) -> Unit, onReview: (String, Boolean) -> Unit, onSkip: () -> Unit, modifier: Modifier = Modifier,
) {
    var message by remember { mutableStateOf("") }
    var asClue by remember { mutableStateOf(false) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if (live == null) {
            DisplayText(stringResource(R.string.live_waiting), style = Cap.type.title)
            BodyText(stringResource(if (error) R.string.live_error else R.string.live_waiting_sub, trip.code), color = Cap.colors.textMuted)
            return@Column
        }
        // État
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Label(stringResource(R.string.live_status), color = Cap.colors.primary)
                Text(
                    if (live.finished) stringResource(R.string.live_finished)
                    else stringResource(R.string.live_heading_to, live.currentIndex + 1, trip.stages.size, trip.stages.getOrNull(live.currentIndex)?.title?.ifBlank { null } ?: "—"),
                    style = Cap.type.title, color = Cap.colors.text,
                )
                val ago = ((System.currentTimeMillis() - live.lastSeenAt) / 1000).coerceAtLeast(0)
                BodyText(
                    if (live.lastSeenAt == 0L) stringResource(R.string.live_no_position) else if (ago < 90) stringResource(R.string.live_seen_now) else stringResource(R.string.live_seen_ago, ago / 60),
                    color = if (ago < 90 && live.lastSeenAt != 0L) Cap.colors.success else Cap.colors.textMuted,
                )
            }
            if (!live.finished) CapButton(stringResource(R.string.live_skip), onSkip, kind = ButtonKind.GHOST, leading = { GlyphIcon(Glyph.SKIP, size = 20.dp) })
        }

        // Message ou indice en direct
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Label(stringResource(R.string.live_send_title), color = Cap.colors.primary)
            CapTextField(message, { message = it }, stringResource(R.string.live_message_label), singleLine = false)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                CapChip(stringResource(R.string.live_as_message), !asClue, { asClue = false })
                CapChip(stringResource(R.string.live_as_clue), asClue, { asClue = true })
                Spacer(Modifier.weight(1f))
                CapButton(stringResource(R.string.send), { onSend(message, asClue); message = "" }, enabled = message.isNotBlank(), leading = { GlyphIcon(Glyph.SEND, size = 20.dp, tint = Cap.colors.onPrimary) })
            }
        }

        // Réponses et photos
        Label(stringResource(R.string.live_submissions), color = Cap.colors.primary)
        if (live.submissions.isEmpty()) BodyText(stringResource(R.string.live_no_submissions), color = Cap.colors.textMuted)
        for (s in live.submissions.asReversed()) CapCard(Modifier.fillMaxWidth(), highlighted = s.status == ChallengeStatus.AWAITING_HOST) {
            Label(
                stringResource(kindLabel(s.kind)) + " · " + stringResource(
                    when (s.status) {
                        ChallengeStatus.SUCCESS -> R.string.status_success
                        ChallengeStatus.FAILED -> R.string.status_failed
                        ChallengeStatus.AWAITING_HOST -> R.string.status_awaiting
                        ChallengeStatus.PENDING -> R.string.status_retry
                    }
                ),
                color = when (s.status) { ChallengeStatus.SUCCESS -> Cap.colors.success; ChallengeStatus.FAILED -> Cap.colors.danger; else -> Cap.colors.secondary },
            )
            Spacer(Modifier.height(4.dp))
            BodyText(s.prompt, color = Cap.colors.textMuted)
            if (s.answer.isNotBlank()) BodyText("« ${s.answer} »", style = Cap.type.bodyLarge)
            s.photo?.let { Spacer(Modifier.height(8.dp)); MediaImage(it, resolve, Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(cardShape())) }
            if (s.status == ChallengeStatus.AWAITING_HOST) {
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CapButton(stringResource(R.string.live_approve), { onReview(s.id, true) }, Modifier.weight(1f))
                    CapButton(stringResource(R.string.live_reject), { onReview(s.id, false) }, Modifier.weight(1f), kind = ButtonKind.DANGER)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
    }
}

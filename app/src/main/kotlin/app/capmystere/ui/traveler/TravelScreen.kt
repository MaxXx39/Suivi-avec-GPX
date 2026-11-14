package app.capmystere.ui.traveler

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.capmystere.R
import app.capmystere.core.guidance.GuidanceConfig
import app.capmystere.core.model.ChallengeStatus
import app.capmystere.core.model.ClueFragment
import app.capmystere.core.model.ClueKind
import app.capmystere.core.model.LiveMessage
import app.capmystere.core.model.MediaRef
import app.capmystere.core.model.TravelerChallenge
import app.capmystere.core.model.UnlockedStage
import app.capmystere.map.LockedMap
import app.capmystere.map.PUCK_Y_FRACTION
import app.capmystere.theme.Cap
import app.capmystere.theme.CapTheme
import app.capmystere.travel.InstructionFormatter
import app.capmystere.travel.InstructionText
import app.capmystere.travel.TravelPhase
import app.capmystere.travel.TravelState
import app.capmystere.ui.common.BodyText
import app.capmystere.ui.common.ButtonKind
import app.capmystere.ui.common.CapButton
import app.capmystere.ui.common.CapCard
import app.capmystere.ui.common.CapChip
import app.capmystere.ui.common.DisplayText
import app.capmystere.ui.common.Glyph
import app.capmystere.ui.common.GlyphIcon
import app.capmystere.ui.common.Hairline
import app.capmystere.ui.common.Illustration
import app.capmystere.ui.common.Label
import app.capmystere.ui.common.ManeuverIcon
import app.capmystere.ui.common.ThemedBackground
import app.capmystere.ui.common.cardShape
import java.io.File

/** Défis actuellement proposés au voyageur : ceux des étapes débloquées et ceux du tronçon en cours. */
fun TravelState.availableChallenges(): List<TravelerChallenge> =
    (snapshot?.enRouteChallenges.orEmpty() + snapshot?.unlocked.orEmpty().flatMap { it.challenges })

fun TravelState.pendingChallenges(): List<TravelerChallenge> = availableChallenges().filter { statusOf(it.id) == ChallengeStatus.PENDING }

@Composable
fun TravelScreen(onExit: () -> Unit, onClues: () -> Unit, onChallenge: (String) -> Unit, vm: TravelViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val snapshot = state.snapshot
    var confirmExit by remember { mutableStateOf(false) }
    var showChallenges by remember { mutableStateOf(false) }

    KeepScreenOn()
    BackHandler { if (showChallenges) showChallenges = false else confirmExit = true }
    // Le contrôleur a été arrêté (processus relancé, session perdue) : on ne reste pas sur un écran vide.
    LaunchedEffect(state.phase) { if (state.phase == TravelPhase.IDLE) onExit() }

    val theme = remember(snapshot?.themeId) { vm.themes.get(snapshot?.themeId) }
    CapTheme(theme, snapshot?.audience ?: app.capmystere.core.model.Audience.FRIENDS, night = state.night) {
        val context = androidx.compose.ui.platform.LocalContext.current
        val informal = Cap.current.profile.informal
        val config = remember(snapshot?.profile) { GuidanceConfig.forProfile(snapshot?.profile ?: app.capmystere.core.model.TravelProfile.CAR) }
        val formatter = remember(informal, config) { InstructionFormatter(context, informal, config.farM) }
        val instruction = state.guidance?.takeIf { state.phase == TravelPhase.GUIDING }?.let { formatter.display(it, config.nowM) }

        Box(Modifier.fillMaxSize().background(Cap.colors.background)) {
            LockedMap(
                position = state.position, bearingDeg = state.bearingDeg, routeAhead = state.routeAhead,
                profile = snapshot?.profile ?: app.capmystere.core.model.TravelProfile.CAR,
                streetNames = snapshot?.showStreetNames == true, modifier = Modifier.fillMaxSize(),
            )
            TravelOverlay(
                state = state, instruction = instruction, voiceOn = vm.voiceOn(settings),
                onToggleVoice = vm::toggleVoice, onClues = onClues, onChallenges = { showChallenges = true },
                onQuit = { confirmExit = true }, onSimSpeed = vm.controller::setSimSpeed,
                onDismissMessage = vm.controller::dismissMessage,
            )

            // Révélation d'étape : le brouillard se referme, puis le message de l'hôte apparaît.
            AnimatedVisibility(state.reveal != null, enter = fadeIn(tween(500)), exit = fadeOut(tween(350))) {
                val stage = remember { mutableStateOf(state.reveal) }.also { if (state.reveal != null) it.value = state.reveal }.value
                if (stage != null) StageReveal(
                    stage = stage, finished = snapshot?.finished == true, resolve = vm::resolve,
                    pending = stage.challenges.count { state.statusOf(it.id) == ChallengeStatus.PENDING },
                    onChallenges = { showChallenges = true },
                    onContinue = vm.controller::dismissReveal,
                )
            }
            if (state.phase == TravelPhase.FINISHED && state.reveal == null) {
                Finale(onClues = onClues, onDone = { vm.exit(); onExit() })
            }
            AnimatedVisibility(showChallenges, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                ChallengeList(state, onPick = { showChallenges = false; onChallenge(it) }, onClose = { showChallenges = false })
            }
            state.newClue?.let { clue -> if (state.reveal == null) ClueReveal(clue, vm::resolve, onDismiss = vm.controller::dismissClue) }
            if (confirmExit) ConfirmDialog(
                title = stringResource(if (state.simulated) R.string.travel_quit_sim_title else R.string.travel_quit_title),
                body = stringResource(if (state.simulated) R.string.travel_quit_sim_body else R.string.travel_quit_body),
                confirm = stringResource(R.string.travel_quit_confirm),
                onConfirm = { confirmExit = false; vm.exit(); onExit() }, onDismiss = { confirmExit = false },
            )
        }
    }
}

@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/** Tout ce qui se superpose à la carte : brouillard, repère, instruction, barre d'actions. */
@Composable
fun TravelOverlay(
    state: TravelState, instruction: InstructionText?, voiceOn: Boolean,
    onToggleVoice: () -> Unit, onClues: () -> Unit, onChallenges: () -> Unit, onQuit: () -> Unit,
    onSimSpeed: (Int) -> Unit, onDismissMessage: () -> Unit,
) {
    val snapshot = state.snapshot
    val tone = Cap.tone
    Box(Modifier.fillMaxSize()) {
        Fog(closed = state.reveal != null || state.position == null)
        if (state.position != null) Puck(offRoute = state.phase == TravelPhase.OFF_ROUTE)

        Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                instruction != null -> InstructionPanel(instruction, flavor = tone.flavor(instruction.icon?.type, (instruction.main + (snapshot?.currentIndex ?: 0)).hashCode()))
                state.phase == TravelPhase.OFF_ROUTE -> StatusPanel(
                    tone.narration(if (state.online) "offRoute" else "offRouteOffline"),
                    stringResource(if (state.online) R.string.travel_rerouting else R.string.travel_uturn), danger = true,
                )
                state.phase == TravelPhase.NO_ROUTE_OFFLINE -> StatusPanel(stringResource(R.string.travel_no_route), stringResource(R.string.travel_no_route_sub), danger = true)
                state.phase == TravelPhase.STAGE_PENDING_SYNC -> StatusPanel(tone.narration("stageArrived"), tone.narration("pendingSync"))
                state.phase == TravelPhase.ROUTING -> StatusPanel(stringResource(R.string.travel_routing), null)
                state.phase == TravelPhase.ERROR -> StatusPanel(
                    stringResource(if (state.error == "permission") R.string.travel_error_permission else R.string.travel_error_offline), null, danger = true,
                )
                state.phase == TravelPhase.FINISHED -> Unit
                else -> StatusPanel(tone.narration("waitingGps", stringResource(R.string.travel_waiting_gps)), null)
            }
            state.liveMessage?.let { LiveMessageCard(it, onDismissMessage) }
        }

        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (state.simulated) Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Label(stringResource(R.string.travel_sim), color = Cap.colors.secondary)
                for (speed in listOf(1, 4, 16)) CapChip("×$speed", state.simSpeed == speed, { onSimSpeed(speed) })
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    if (snapshot != null && !snapshot.finished) {
                        Label(tone.word("stage", stringResource(R.string.word_stage)))
                        Text("${snapshot.currentIndex + 1}", style = Cap.type.hero, color = Cap.colors.text)
                    }
                    if (!state.online) Label(stringResource(R.string.travel_offline), color = Cap.colors.danger)
                    else if (state.pendingActions > 0) Label(stringResource(R.string.travel_syncing), color = Cap.colors.secondary)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    val pending = state.pendingChallenges().size
                    RoundAction(Glyph.BULB, stringResource(R.string.travel_challenges), onChallenges, badge = pending, emphasized = pending > 0 && !state.moving)
                    RoundAction(Glyph.BOOK, tone.word("clueBook", stringResource(R.string.word_clue_book)), onClues, badge = snapshot?.clues?.size ?: 0)
                    RoundAction(if (voiceOn) Glyph.VOICE else Glyph.MUTE, stringResource(R.string.settings_voice), onToggleVoice)
                    RoundAction(Glyph.CLOSE, stringResource(R.string.travel_quit_confirm), onQuit)
                }
            }
        }
    }
}

/** Brouillard : on ne voit qu'un halo autour de soi. Il respire doucement et se referme lors des révélations. */
@Composable
private fun Fog(closed: Boolean) {
    val fog = Cap.colors.background
    val open by animateFloatAsState(if (closed) 0f else 1f, tween(900), label = "fog")
    val breath = if (LocalInspectionMode.current) 0f else {
        val t = rememberInfiniteTransition(label = "fog")
        val v by t.animateFloat(-1f, 1f, infiniteRepeatable(tween(4200, easing = EaseInOutSine), RepeatMode.Reverse), label = "breath")
        v
    }
    Canvas(Modifier.fillMaxSize()) {
        val center = Offset(size.width / 2, size.height * PUCK_Y_FRACTION)
        val radius = size.width * (0.80f + 0.02f * breath) * open + 1f
        drawRect(Brush.radialGradient(0f to Color.Transparent, 0.48f to Color.Transparent, 0.82f to fog.copy(alpha = 0.86f), 1f to fog, center = center, radius = radius))
        // Voile haut et bas pour asseoir l'instruction et la barre d'actions.
        drawRect(Brush.verticalGradient(0f to fog, 0.30f to Color.Transparent, 0.74f to Color.Transparent, 1f to fog))
    }
}

/** Repère du voyageur : une flèche (la carte tourne, pas elle) et une onde. */
@Composable
private fun Puck(offRoute: Boolean) {
    val color = if (offRoute) Cap.colors.danger else Cap.colors.puck
    val accent = Cap.colors.primary
    val pulse = if (LocalInspectionMode.current) 0.4f else {
        val t = rememberInfiniteTransition(label = "puck")
        val v by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200), RepeatMode.Restart), label = "pulse")
        v
    }
    Canvas(Modifier.fillMaxSize()) {
        val c = Offset(size.width / 2, size.height * PUCK_Y_FRACTION)
        val r = 15.dp.toPx()
        drawCircle(accent.copy(alpha = 0.35f * (1 - pulse)), r * (1.2f + 2.4f * pulse), c, style = Stroke(2.dp.toPx()))
        drawCircle(Color.Black.copy(alpha = 0.35f), r * 1.25f, c + Offset(0f, 2.dp.toPx()))
        val arrow = Path().apply {
            moveTo(c.x, c.y - r * 1.25f); lineTo(c.x + r * 0.95f, c.y + r); lineTo(c.x, c.y + r * 0.45f); lineTo(c.x - r * 0.95f, c.y + r); close()
        }
        drawPath(arrow, color)
        drawPath(arrow, accent, style = Stroke(2.dp.toPx()))
    }
}

@Composable
fun InstructionPanel(instruction: InstructionText, flavor: String?) {
    CapCard(Modifier.fillMaxWidth(), padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        AnimatedContent(
            targetState = instruction.main to instruction.icon,
            transitionSpec = { (slideInVertically(tween(320)) { it / 3 } + fadeIn(tween(320))) togetherWith (slideOutVertically(tween(220)) { -it / 3 } + fadeOut(tween(180))) },
            label = "instruction",
        ) { (main, icon) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                ManeuverIcon(icon?.type, exit = icon?.exit ?: 0, size = 84.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    if (instruction.lead.isNotEmpty()) Text(
                        if (Cap.type.instructionUppercase) instruction.lead.uppercase() else instruction.lead,
                        style = Cap.type.distance, color = Cap.colors.primary,
                    )
                    Text(if (Cap.type.instructionUppercase) main.uppercase() else main, style = Cap.type.instruction, color = Cap.colors.text)
                    instruction.then?.let { BodyText(it, color = Cap.colors.textMuted, style = Cap.type.bodyLarge) }
                }
            }
        }
        if (!flavor.isNullOrBlank()) {
            Spacer(Modifier.height(10.dp)); Hairline(); Spacer(Modifier.height(8.dp))
            Text(flavor, style = Cap.type.quote.copy(fontSize = 16.sp, lineHeight = 22.sp), color = Cap.colors.textMuted)
        }
    }
}

@Composable
private fun StatusPanel(title: String, subtitle: String?, danger: Boolean = false) {
    CapCard(Modifier.fillMaxWidth(), highlighted = danger) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Illustration("emblem", size = 56.dp, tint = if (danger) Cap.colors.danger else null)
            Spacer(Modifier.width(14.dp))
            Column {
                Text(title, style = Cap.type.title, color = if (danger) Cap.colors.danger else Cap.colors.text)
                if (subtitle != null) BodyText(subtitle, color = Cap.colors.textMuted, style = Cap.type.bodyLarge)
            }
        }
    }
}

@Composable
private fun LiveMessageCard(message: LiveMessage, onDismiss: () -> Unit) {
    CapCard(Modifier.fillMaxWidth(), onClick = onDismiss, highlighted = true) {
        Label(if (message.isClue) Cap.tone.word("clue", stringResource(R.string.word_clue)) else Cap.tone.narration("liveMessage", stringResource(R.string.travel_live_message)), color = Cap.colors.primary)
        Spacer(Modifier.height(6.dp))
        Text(message.text, style = Cap.type.quote, color = Cap.colors.text)
    }
}

@Composable
private fun RoundAction(glyph: Glyph, description: String, onClick: () -> Unit, badge: Int = 0, emphasized: Boolean = false) {
    val colors = Cap.colors
    Box {
        Box(
            Modifier.size(56.dp).clip(CircleShape).background(if (emphasized) colors.primary else colors.surface)
                .border(1.dp, if (emphasized) colors.primary else colors.outline, CircleShape)
                .clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) { GlyphIcon(glyph, tint = if (emphasized) colors.onPrimary else colors.text) }
        if (badge > 0) Box(
            Modifier.align(Alignment.TopEnd).offset(x = 4.dp, y = (-4).dp).size(22.dp).clip(CircleShape).background(colors.secondary),
            contentAlignment = Alignment.Center,
        ) { Text("$badge", style = Cap.type.label.copy(letterSpacing = 0.sp), color = colors.background) }
    }
}

/** Arrivée à une étape : emblème, annonce, message de l'hôte (texte, image, audio). */
@Composable
fun StageReveal(stage: UnlockedStage, finished: Boolean, resolve: suspend (MediaRef) -> File?, pending: Int, onChallenges: () -> Unit, onContinue: () -> Unit) {
    val tone = Cap.tone
    val inspection = LocalInspectionMode.current
    var shown by remember(stage.index) { mutableStateOf(inspection) }
    LaunchedEffect(stage.index) { shown = true }
    val scale by animateFloatAsState(if (shown) 1f else 0.2f, spring(Spring.DampingRatioMediumBouncy, Spring.StiffnessLow), label = "emblem")
    ThemedBackground(Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(12.dp))
            Illustration(if (finished) "emblem" else "stage", Modifier.graphicsLayer { scaleX = scale; scaleY = scale; rotationZ = (1 - scale) * -40f }, size = 120.dp)
            Spacer(Modifier.height(18.dp))
            Label("${tone.word("stage", stringResource(R.string.word_stage))} ${stage.index + 1}", color = Cap.colors.primary)
            Spacer(Modifier.height(6.dp))
            DisplayText(tone.narration(if (finished) "finale" else "stageArrived", stringResource(R.string.reveal_arrived)), style = Cap.type.hero, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            BodyText(tone.narration(if (finished) "finaleSub" else "stageArrivedSub"), color = Cap.colors.textMuted, textAlign = TextAlign.Center, style = Cap.type.bodyLarge)
            Spacer(Modifier.height(22.dp))
            AnimatedVisibility(shown, enter = fadeIn(tween(700, delayMillis = 350)) + slideInVertically(tween(700, delayMillis = 350)) { it / 5 }) {
                CapCard(Modifier.fillMaxWidth(), highlighted = true) {
                    if (stage.title.isNotBlank()) { DisplayText(stage.title, style = Cap.type.title); Spacer(Modifier.height(10.dp)) }
                    stage.message.image?.let {
                        MediaImage(it, resolve, Modifier.fillMaxWidth().aspectRatio(4f / 3f).clip(cardShape()))
                        Spacer(Modifier.height(12.dp))
                    }
                    if (stage.message.text.isNotBlank()) Text(stage.message.text, style = Cap.type.quote, color = Cap.colors.text)
                    stage.message.audio?.let { Spacer(Modifier.height(12.dp)); AudioButton(it, resolve) }
                }
            }
            Spacer(Modifier.height(22.dp))
            if (pending > 0) {
                CapButton(
                    "${tone.word("challenge", stringResource(R.string.word_challenge))} · $pending", onChallenges, Modifier.fillMaxWidth(),
                    leading = { GlyphIcon(Glyph.BULB, size = 22.dp, tint = Cap.colors.onPrimary) },
                )
                Spacer(Modifier.height(12.dp))
            }
            CapButton(
                if (finished) stringResource(R.string.reveal_finish) else tone.narration("nextLeg", stringResource(R.string.reveal_continue)),
                onContinue, Modifier.fillMaxWidth(), kind = if (pending > 0) ButtonKind.GHOST else ButtonKind.PRIMARY,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun Finale(onClues: () -> Unit, onDone: () -> Unit) {
    ThemedBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Illustration("emblem", size = 140.dp)
            Spacer(Modifier.height(22.dp))
            DisplayText(Cap.tone.narration("finale", stringResource(R.string.finale_title)), style = Cap.type.hero, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            BodyText(Cap.tone.narration("finaleSub"), color = Cap.colors.textMuted, textAlign = TextAlign.Center, style = Cap.type.bodyLarge)
            Spacer(Modifier.height(32.dp))
            CapButton(Cap.tone.word("clueBook", stringResource(R.string.word_clue_book)), onClues, Modifier.fillMaxWidth(), kind = ButtonKind.GHOST)
            Spacer(Modifier.height(12.dp))
            CapButton(stringResource(R.string.finale_done), onDone, Modifier.fillMaxWidth())
        }
    }
}

/** Nouveau fragment d'indice : il se retourne comme une carte. */
@Composable
fun ClueReveal(clue: ClueFragment, resolve: suspend (MediaRef) -> File?, onDismiss: () -> Unit) {
    var shown by remember(clue.id) { mutableStateOf(false) }
    LaunchedEffect(clue.id) { shown = true }
    val flip by animateFloatAsState(if (shown) 0f else 90f, tween(700), label = "flip")
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.72f)).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
        CapCard(Modifier.padding(32.dp).fillMaxWidth().graphicsLayer { rotationY = flip; cameraDistance = 14f * density }, highlighted = true) {
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Illustration("clue", size = 64.dp)
                Spacer(Modifier.height(10.dp))
                Label(Cap.tone.narration("clueUnlocked", stringResource(R.string.clue_new)), color = Cap.colors.primary)
                Spacer(Modifier.height(14.dp))
                when (clue.kind) {
                    ClueKind.LETTER -> Text(clue.text.uppercase(), style = Cap.type.hero.copy(fontSize = 96.sp, lineHeight = 100.sp), color = Cap.colors.text)
                    ClueKind.TEXT -> Text(clue.text, style = Cap.type.quote, color = Cap.colors.text, textAlign = TextAlign.Center)
                    ClueKind.IMAGE_PIECE -> clue.image?.let { MediaImage(it, resolve, Modifier.size(180.dp).clip(cardShape())) }
                }
                Spacer(Modifier.height(18.dp))
                CapButton(stringResource(R.string.clue_keep), onDismiss)
            }
        }
    }
}

@Composable
private fun ChallengeList(state: TravelState, onPick: (String) -> Unit, onClose: () -> Unit) {
    val tone = Cap.tone
    ThemedBackground(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
            app.capmystere.ui.common.CapTopBar(tone.word("challenge", stringResource(R.string.word_challenge)), onBack = onClose)
            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Passager ou non, on évite les interactions longues tant que le véhicule roule.
                if (state.moving) CapCard(Modifier.fillMaxWidth(), highlighted = true) {
                    BodyText(stringResource(R.string.challenge_moving), style = Cap.type.bodyLarge)
                }
                val all = state.availableChallenges()
                if (all.isEmpty()) BodyText(stringResource(R.string.challenge_none), color = Cap.colors.textMuted, style = Cap.type.bodyLarge)
                for (c in all) {
                    val status = state.statusOf(c.id)
                    CapCard(Modifier.fillMaxWidth(), onClick = if (status == ChallengeStatus.PENDING) ({ onPick(c.id) }) else null, highlighted = status == ChallengeStatus.PENDING) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Label(stringResource(kindLabel(c.kind)), color = Cap.colors.primary)
                                Spacer(Modifier.height(4.dp))
                                BodyText(c.prompt, style = Cap.type.bodyLarge)
                            }
                            Spacer(Modifier.width(10.dp))
                            when (status) {
                                ChallengeStatus.SUCCESS -> GlyphIcon(Glyph.CHECK, tint = Cap.colors.success, size = 28.dp)
                                ChallengeStatus.FAILED -> GlyphIcon(Glyph.CLOSE, tint = Cap.colors.danger, size = 28.dp)
                                ChallengeStatus.AWAITING_HOST -> GlyphIcon(Glyph.EYE, tint = Cap.colors.secondary, size = 28.dp)
                                ChallengeStatus.PENDING -> if (c.hasReward) Illustration("clue", size = 30.dp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
fun ConfirmDialog(title: String, body: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.7f)).clickable(onClick = onDismiss), contentAlignment = Alignment.Center) {
        CapCard(Modifier.padding(28.dp).fillMaxWidth().clickable(enabled = false) {}, highlighted = true) {
            DisplayText(title, style = Cap.type.title)
            Spacer(Modifier.height(8.dp))
            BodyText(body, color = Cap.colors.textMuted)
            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CapButton(stringResource(R.string.cancel), onDismiss, Modifier.weight(1f), kind = ButtonKind.GHOST)
                CapButton(confirm, onConfirm, Modifier.weight(1f), kind = ButtonKind.DANGER)
            }
        }
    }
}

fun kindLabel(kind: app.capmystere.core.model.ChallengeKind): Int = when (kind) {
    app.capmystere.core.model.ChallengeKind.QUIZ -> R.string.kind_quiz
    app.capmystere.core.model.ChallengeKind.FREE_TEXT -> R.string.kind_free_text
    app.capmystere.core.model.ChallengeKind.PHOTO -> R.string.kind_photo
    app.capmystere.core.model.ChallengeKind.QR_SCAN -> R.string.kind_qr
    app.capmystere.core.model.ChallengeKind.FIND_ON_SITE -> R.string.kind_find
    app.capmystere.core.model.ChallengeKind.RIDDLE -> R.string.kind_riddle
    app.capmystere.core.model.ChallengeKind.MISSION -> R.string.kind_mission
}

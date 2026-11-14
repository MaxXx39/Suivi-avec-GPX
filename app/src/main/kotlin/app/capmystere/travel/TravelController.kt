package app.capmystere.travel

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import app.capmystere.audio.Narrator
import app.capmystere.audio.Sfx
import app.capmystere.core.challenge.Answers
import app.capmystere.core.geo.Geo
import app.capmystere.core.geo.Sun
import app.capmystere.core.gpx.TrackSimulator
import app.capmystere.core.guidance.CueLevel
import app.capmystere.core.guidance.Fix
import app.capmystere.core.guidance.GuidanceConfig
import app.capmystere.core.guidance.GuidanceEngine
import app.capmystere.core.guidance.GuidanceState
import app.capmystere.core.guidance.GuidanceStatus
import app.capmystere.core.guidance.Route
import app.capmystere.core.model.ChallengeKind
import app.capmystere.core.model.ChallengeStatus
import app.capmystere.core.model.ClueFragment
import app.capmystere.core.model.LatLon
import app.capmystere.core.model.LiveMessage
import app.capmystere.core.model.TravelProfile
import app.capmystere.core.model.TravelerChallenge
import app.capmystere.core.model.TravelerSnapshot
import app.capmystere.core.model.UnlockedStage
import app.capmystere.data.NightMode
import app.capmystere.data.Prefs
import app.capmystere.data.Settings
import app.capmystere.data.backend.BackendRejected
import app.capmystere.data.backend.TripBackend
import app.capmystere.data.db.CacheDao
import app.capmystere.data.db.CacheEntity
import app.capmystere.data.db.OutboxDao
import app.capmystere.data.routing.RoutingProvider
import app.capmystere.location.FusedLocationSource
import app.capmystere.map.OfflineTiles
import app.capmystere.theme.AudienceProfile
import app.capmystere.theme.ThemeRepository
import app.capmystere.theme.Tone
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

enum class TravelPhase { IDLE, LOADING, WAITING_GPS, ROUTING, GUIDING, OFF_ROUTE, NO_ROUTE_OFFLINE, STAGE_PENDING_SYNC, FINISHED, ERROR }

/** Mode simulation : suivre l'itinéraire calculé, ou rejouer une trace GPX. */
sealed interface Simulation {
    data class FollowRoute(val start: LatLon) : Simulation
    data class Gpx(val points: List<LatLon>) : Simulation
}

enum class AnswerOutcome { SUCCESS, RETRY, FAILED, AWAITING_HOST, QUEUED }

data class TravelState(
    val phase: TravelPhase = TravelPhase.IDLE,
    val snapshot: TravelerSnapshot? = null,
    val guidance: GuidanceState? = null,
    val position: LatLon? = null,
    val bearingDeg: Double = 0.0,
    /** Portion d'itinéraire proche, seule transmise à la carte. */
    val routeAhead: List<LatLon> = emptyList(),
    val online: Boolean = true,
    val pendingActions: Int = 0,
    val night: Boolean = true,
    val simulated: Boolean = false,
    val simSpeed: Int = 1,
    val moving: Boolean = false,
    /** Étape qui vient d'être débloquée, à révéler. */
    val reveal: UnlockedStage? = null,
    val newClue: ClueFragment? = null,
    val liveMessage: LiveMessage? = null,
    val error: String? = null,
    /** Statuts de défis décidés localement en attendant la synchronisation. */
    val localStatus: Map<String, ChallengeStatus> = emptyMap(),
) {
    fun statusOf(challengeId: String): ChallengeStatus =
        snapshot?.challengeStatus?.get(challengeId)?.takeIf { it != ChallengeStatus.PENDING } ?: localStatus[challengeId] ?: ChallengeStatus.PENDING
}

/**
 * Chef d'orchestre du mode voyageur : position → moteur de guidage → annonces, arrivée,
 * validation d'étape (en ligne ou en file d'attente), recalcul, envoi des positions.
 */
@Singleton
class TravelController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backend: TripBackend,
    private val routing: RoutingProvider,
    private val sync: SyncEngine,
    private val cache: CacheDao,
    private val outbox: OutboxDao,
    private val prefs: Prefs,
    private val themes: ThemeRepository,
    private val narrator: Narrator,
    private val gps: FusedLocationSource,
    private val offlineTiles: OfflineTiles,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val json = Json { ignoreUnknownKeys = true }

    private val _state = MutableStateFlow(TravelState())
    val state: StateFlow<TravelState> = _state.asStateFlow()

    private var job: Job? = null
    private var sessionId: String? = null
    private var engine: GuidanceEngine? = null
    private var route: Route? = null
    private var routingJob: Job? = null
    private var validating = false
    private var lastRerouteAt = 0L
    private var lastFix: Fix? = null
    private var settings = Settings()
    private var simulation: Simulation? = null
    private var simOffsetM = 0.0
    private var simElapsedS = 0.0
    private var simPosition: LatLon? = null
    private val positionBuffer = ArrayList<LatLon>()
    private var lastPositionSentAt = 0L
    private var shownMessageIds = HashSet<String>()
    private var formatter: InstructionFormatter? = null
    private var tone: Tone? = null
    private var config = GuidanceConfig.forProfile(TravelProfile.CAR)

    val isRunning: Boolean get() = job?.isActive == true
    val activeSessionId: String? get() = sessionId

    fun start(sessionId: String, simulation: Simulation? = null) {
        if (isRunning && this.sessionId == sessionId && this.simulation == simulation) return
        stopInternal()
        this.sessionId = sessionId
        this.simulation = simulation
        simOffsetM = 0.0; simElapsedS = 0.0
        simPosition = (simulation as? Simulation.FollowRoute)?.start
        _state.value = TravelState(phase = TravelPhase.LOADING, simulated = simulation != null)
        job = scope.launch {
            launch { prefs.settings.collect { settings = it; applySettings() } }
            launch { outbox.observePendingActions().collect { n -> _state.update { it.copy(pendingActions = n) } } }
            val cached = sync.cachedSnapshot(sessionId)
            if (cached != null) applySnapshot(cached, announce = false)
            refresh()
            if (_state.value.snapshot == null) {
                _state.update { it.copy(phase = TravelPhase.ERROR, error = "offline") }
                return@launch
            }
            if (simulation == null) prefs.setActiveSession(sessionId)
            if (_state.value.phase == TravelPhase.LOADING) _state.update { it.copy(phase = TravelPhase.WAITING_GPS) }
            launch { collectFixes() }
            launch { backend.travelerEvents(sessionId).collect { refresh() } }
            launch { housekeeping() }
        }
    }

    fun stop() {
        stopInternal()
        _state.value = TravelState()
    }

    /** Quitte définitivement le voyage en cours. */
    fun leave() {
        val id = sessionId
        stop()
        scope.launch { prefs.setActiveSession(null); if (id != null) outbox.clear(id) }
    }

    private fun stopInternal() {
        job?.cancel(); job = null
        routingJob?.cancel(); routingJob = null
        engine = null; route = null; validating = false; lastFix = null
        positionBuffer.clear(); shownMessageIds.clear()
        narrator.stopSpeaking()
    }

    // ------------------------------------------------------------------------------------------
    // Instantané serveur
    // ------------------------------------------------------------------------------------------

    private suspend fun refresh() {
        val id = sessionId ?: return
        val online = isOnline()
        _state.update { it.copy(online = online) }
        if (!online && !isLocalSession()) return
        runCatching {
            val flushed = sync.flush()
            flushed.snapshot ?: backend.snapshot(id)
        }.onSuccess { applySnapshot(it, announce = true); sync.cacheSnapshot(it) }
            .onFailure { if (it is BackendRejected) _state.update { s -> s.copy(phase = TravelPhase.ERROR, error = "session") } }
    }

    private fun applySnapshot(s: TravelerSnapshot, announce: Boolean) {
        val previous = _state.value.snapshot
        val theme = themes.get(s.themeId)
        val profile = AudienceProfile.of(s.audience)
        narrator.prepare(theme)
        tone = Tone(theme.tone, s.audience)
        config = GuidanceConfig.forProfile(s.profile)
        formatter = InstructionFormatter(context, profile.informal, config.farM)
        applySettings(profile)

        var reveal = _state.value.reveal
        var newClue = _state.value.newClue
        var liveMessage = _state.value.liveMessage
        if (previous != null && announce) {
            if (s.unlocked.size > previous.unlocked.size) {
                // Étape débloquée (par nous, par la file hors ligne ou par l'hôte) : on efface le tronçon terminé.
                reveal = s.unlocked.last()
                clearRoute()
                narrator.play(Sfx.ARRIVE); narrator.haptic(Sfx.ARRIVE)
                narrator.speak(tone?.narration("stageArrived").orEmpty())
            }
            s.clues.firstOrNull { c -> previous.clues.none { it.id == c.id } }?.let {
                newClue = it
                if (reveal == null) { narrator.play(Sfx.CLUE); narrator.haptic(Sfx.CLUE) }
            }
        }
        s.messages.lastOrNull()?.let { m ->
            val isNew = previous != null && previous.messages.none { it.id == m.id }
            if (isNew && shownMessageIds.add(m.id)) {
                liveMessage = m
                narrator.play(Sfx.CUE); narrator.haptic(Sfx.CLUE)
                narrator.speak(m.text, interrupt = false)
            }
        }
        if (previous != null && previous.target != s.target) clearRoute()
        _state.update {
            it.copy(
                snapshot = s, reveal = reveal, newClue = newClue, liveMessage = liveMessage,
                phase = when {
                    s.finished -> TravelPhase.FINISHED
                    it.phase == TravelPhase.STAGE_PENDING_SYNC && s.currentIndex > (previous?.currentIndex ?: 0) -> TravelPhase.WAITING_GPS
                    it.phase == TravelPhase.ERROR -> TravelPhase.WAITING_GPS
                    else -> it.phase
                },
                error = null,
                localStatus = it.localStatus.filterKeys { k -> s.challengeStatus[k].let { st -> st == null || st == ChallengeStatus.PENDING } },
            )
        }
        validating = false
    }

    private fun applySettings(profile: AudienceProfile? = _state.value.snapshot?.let { AudienceProfile.of(it.audience) }) {
        narrator.voiceEnabled = settings.voice ?: (profile?.voiceByDefault ?: false)
        narrator.soundsEnabled = settings.sounds
        narrator.hapticsEnabled = settings.haptics
        updateNight()
    }

    private fun updateNight() {
        val night = when (settings.nightMode) {
            NightMode.NIGHT -> true
            NightMode.DAY -> false
            NightMode.AUTO -> lastFix?.let { Sun.isNight(it.position.lat, it.position.lon, System.currentTimeMillis()) }
                ?: java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY).let { it < 7 || it >= 19 }
        }
        if (night != _state.value.night) _state.update { it.copy(night = night) }
    }

    private fun clearRoute() {
        routingJob?.cancel(); routingJob = null
        engine = null; route = null; simOffsetM = 0.0
        _state.update { it.copy(guidance = null, routeAhead = emptyList()) }
    }

    // ------------------------------------------------------------------------------------------
    // Positions
    // ------------------------------------------------------------------------------------------

    private suspend fun collectFixes() {
        val sim = simulation
        if (sim == null) {
            gps.fixes().catch { _state.update { s -> s.copy(phase = TravelPhase.ERROR, error = "permission") } }.collect { onFix(it) }
        } else {
            val track = (sim as? Simulation.Gpx)?.let { TrackSimulator(it.points, simSpeedMps()) }
            while (scope.isActive) {
                val st = _state.value
                // La « voiture » s'arrête pendant les révélations, comme dans la vraie vie.
                val paused = st.reveal != null || st.phase == TravelPhase.FINISHED
                val speed = st.simSpeed
                if (track != null) {
                    if (!paused) simElapsedS += speed
                    onFix(track.fixAt(simElapsedS, System.currentTimeMillis()))
                } else {
                    val r = route
                    if (r != null && !paused) {
                        simOffsetM = (simOffsetM + simSpeedMps() * speed).coerceAtMost(r.lengthM)
                        val (p, brg) = r.pointAt(simOffsetM)
                        simPosition = p
                        onFix(Fix(p, 5f, simSpeedMps().toFloat(), brg.toFloat(), System.currentTimeMillis()))
                    } else {
                        simPosition?.let { onFix(Fix(it, 5f, 0f, null, System.currentTimeMillis())) }
                    }
                }
                delay(1000)
            }
        }
    }

    private fun simSpeedMps(): Double = when (_state.value.snapshot?.profile ?: TravelProfile.CAR) {
        TravelProfile.CAR -> 19.0
        TravelProfile.BIKE -> 5.5
        TravelProfile.WALK -> 1.6
    }

    fun setSimSpeed(multiplier: Int) = _state.update { it.copy(simSpeed = multiplier.coerceIn(1, 32)) }

    private fun onFix(fix: Fix) {
        lastFix = fix
        val snap = _state.value.snapshot ?: return
        bufferPosition(fix, snap)
        val moving = fix.speedMps > MOVING_SPEED_MPS
        if (snap.finished) { _state.update { it.copy(position = fix.position, moving = moving) }; return }
        val target = snap.target ?: return
        if (_state.value.reveal != null || _state.value.phase == TravelPhase.STAGE_PENDING_SYNC) {
            _state.update { it.copy(position = fix.position, bearingDeg = fix.bearingDeg?.toDouble() ?: it.bearingDeg, moving = moving) }
            return
        }

        val e = engine
        if (e == null) {
            // Pas encore d'itinéraire : on vérifie tout de même l'arrivée, puis on lance le calcul.
            _state.update { it.copy(position = fix.position, bearingDeg = fix.bearingDeg?.toDouble() ?: it.bearingDeg, moving = moving) }
            if (Geo.distance(fix.position, target.position) <= target.radiusM) { onArrived(fix, snap); return }
            if (routingJob?.isActive != true) requestRoute(fix, snap, reroute = false)
            return
        }

        val g = e.update(fix)
        val r = e.route
        when (g.status) {
            GuidanceStatus.ARRIVED -> { onArrived(fix, snap); return }
            GuidanceStatus.OFF_ROUTE -> {
                val now = System.currentTimeMillis()
                val wasOff = _state.value.phase == TravelPhase.OFF_ROUTE
                _state.update { it.copy(phase = TravelPhase.OFF_ROUTE, guidance = g, position = g.snapped, bearingDeg = g.bearingDeg, moving = moving) }
                if (!wasOff) {
                    narrator.haptic(Sfx.FAIL)
                    narrator.speak(tone?.narration(if (isOnline()) "offRoute" else "offRouteOffline").orEmpty())
                }
                if (now - lastRerouteAt > REROUTE_MIN_INTERVAL_MS && routingJob?.isActive != true && isOnline()) {
                    lastRerouteAt = now
                    requestRoute(fix, snap, reroute = true)
                }
            }
            GuidanceStatus.ON_ROUTE -> {
                g.cue?.let { cue ->
                    narrator.haptic(Sfx.CUE)
                    if (cue.level != CueLevel.FAR) narrator.play(Sfx.CUE)
                    formatter?.let { narrator.speak(it.spoken(cue.level, cue.maneuver, cue.distanceM, g.then)) }
                }
                _state.update {
                    it.copy(
                        phase = TravelPhase.GUIDING, guidance = g, position = g.snapped, bearingDeg = g.bearingDeg, moving = moving,
                        routeAhead = r.slice(g.progressM - BEHIND_M, g.progressM + AHEAD_M),
                    )
                }
            }
        }
    }

    private fun requestRoute(fix: Fix, snap: TravelerSnapshot, reroute: Boolean) {
        val target = snap.target ?: return
        val key = "route:${snap.sessionId}:${snap.currentIndex}"
        if (!reroute) _state.update { it.copy(phase = TravelPhase.ROUTING) }
        routingJob = scope.launch {
            // 1) Tronçon déjà téléchargé et toujours valable depuis notre position ?
            if (!reroute) {
                val cachedRoute = cache.get(key)?.let { runCatching { json.decodeFromString(Route.serializer(), it.json) }.getOrNull() }
                if (cachedRoute != null && distanceToRoute(fix.position, cachedRoute) < config.offRouteM * 2) { installRoute(cachedRoute, snap); return@launch }
            }
            // 2) Calcul en ligne.
            val result = runCatching { routing.route(listOf(fix.position, target.position), snap.profile, fix.bearingDeg?.toDouble()).first() }
            result.onSuccess { r ->
                cache.put(CacheEntity(key, json.encodeToString(Route.serializer(), r), System.currentTimeMillis()))
                installRoute(r, snap)
                // Pré-téléchargement des tuiles du couloir (inutile en simulation rapide).
                themes.get(snap.themeId).let { theme -> offlineTiles.prefetch(r.points, theme, _state.value.night, key) }
            }.onFailure {
                if (engine == null) _state.update { s -> s.copy(phase = TravelPhase.NO_ROUTE_OFFLINE) }
                delay(ROUTE_RETRY_DELAY_MS) // évite de marteler le serveur à chaque position
            }
        }
    }

    private fun distanceToRoute(p: LatLon, r: Route): Double =
        (0 until r.points.size - 1).minOfOrNull { Geo.projectOnSegment(p, r.points[it], r.points[it + 1]).distanceM } ?: Double.MAX_VALUE

    private fun installRoute(r: Route, snap: TravelerSnapshot) {
        val target = snap.target ?: return
        if (_state.value.snapshot?.currentIndex != snap.currentIndex) return // l'étape a changé entre-temps
        route = r
        simOffsetM = 0.0
        engine = GuidanceEngine(r, target.position, target.radiusM.toDouble(), config)
        _state.update { it.copy(phase = TravelPhase.GUIDING, routeAhead = r.slice(0.0, AHEAD_M)) }
        lastFix?.let { onFix(it) }
    }

    // ------------------------------------------------------------------------------------------
    // Arrivée et validation d'étape
    // ------------------------------------------------------------------------------------------

    private fun onArrived(fix: Fix, snap: TravelerSnapshot) {
        if (validating) return
        validating = true
        val id = snap.sessionId
        val payload = ValidatePayload(snap.currentIndex, fix.position.lat, fix.position.lon, fix.accuracyM, System.currentTimeMillis())
        scope.launch {
            try {
                val next = backend.validateStage(id, payload.index, fix.position, fix.accuracyM, payload.at)
                sync.cacheSnapshot(next)
                applySnapshot(next, announce = true)
            } catch (e: BackendRejected) {
                validating = false // le serveur nous trouve trop loin : on réessaiera aux prochaines positions
                delay(4000)
            } catch (e: Exception) {
                // Hors ligne : la validation est mise en file et sera rejouée au retour du réseau.
                sync.enqueueValidate(id, payload)
                clearRoute()
                narrator.play(Sfx.ARRIVE); narrator.haptic(Sfx.ARRIVE)
                _state.update { it.copy(phase = TravelPhase.STAGE_PENDING_SYNC, online = false) }
            }
        }
    }

    fun dismissReveal() = _state.update { it.copy(reveal = null, phase = if (it.snapshot?.finished == true) TravelPhase.FINISHED else TravelPhase.WAITING_GPS) }
    fun dismissClue() = _state.update { it.copy(newClue = null) }
    fun dismissMessage() = _state.update { it.copy(liveMessage = null) }

    // ------------------------------------------------------------------------------------------
    // Défis
    // ------------------------------------------------------------------------------------------

    suspend fun answer(challenge: TravelerChallenge, answer: String, photo: File?, gaveUp: Boolean = false): AnswerOutcome {
        val id = sessionId ?: return AnswerOutcome.RETRY
        val now = System.currentTimeMillis()
        return try {
            val next = withContext(Dispatchers.IO) { backend.submitAnswer(id, challenge.id, answer, photo, gaveUp, now) }
            sync.cacheSnapshot(next)
            applySnapshot(next, announce = true)
            when (next.challengeStatus[challenge.id]) {
                ChallengeStatus.SUCCESS -> AnswerOutcome.SUCCESS.also { narrator.play(Sfx.CLUE); narrator.haptic(Sfx.CLUE) }
                ChallengeStatus.FAILED -> AnswerOutcome.FAILED.also { narrator.play(Sfx.FAIL); narrator.haptic(Sfx.FAIL) }
                ChallengeStatus.AWAITING_HOST -> AnswerOutcome.AWAITING_HOST
                else -> AnswerOutcome.RETRY.also { narrator.haptic(Sfx.FAIL) }
            }
        } catch (e: BackendRejected) {
            AnswerOutcome.RETRY
        } catch (e: Exception) {
            // Hors ligne : verdict local grâce aux empreintes ; la récompense arrivera à la synchronisation.
            val isPhoto = challenge.kind == ChallengeKind.PHOTO || challenge.kind == ChallengeKind.MISSION
            val correct = !gaveUp && (isPhoto || challenge.answerHashes.isEmpty() || Answers.matches(challenge, answer))
            if (!correct && !gaveUp) return AnswerOutcome.RETRY.also { narrator.haptic(Sfx.FAIL) }
            sync.enqueueAnswer(id, AnswerPayload(challenge.id, answer, photo?.absolutePath, gaveUp, now))
            val local = if (gaveUp) ChallengeStatus.FAILED else ChallengeStatus.AWAITING_HOST
            _state.update { it.copy(localStatus = it.localStatus + (challenge.id to local), online = false) }
            AnswerOutcome.QUEUED
        }
    }

    // ------------------------------------------------------------------------------------------
    // Envoi des positions, tâches de fond
    // ------------------------------------------------------------------------------------------

    private fun bufferPosition(fix: Fix, snap: TravelerSnapshot) {
        val now = System.currentTimeMillis()
        val intervalMs = snap.positionIntervalS.coerceAtLeast(3) * 1000L
        if (now - lastPositionSentAt < intervalMs) return
        lastPositionSentAt = now
        positionBuffer.add(fix.position)
        val points = positionBuffer.toList()
        positionBuffer.clear()
        scope.launch {
            runCatching { backend.pushPositions(snap.sessionId, points, now) }
                .onFailure { if (it !is BackendRejected) sync.enqueuePositions(snap.sessionId, PositionsPayload(points, now)) }
        }
    }

    private suspend fun housekeeping() {
        var tick = 0
        while (scope.isActive) {
            delay(15_000)
            tick++
            updateNight()
            // Rattrapage : file hors ligne, indices programmés, messages (si le temps réel a décroché).
            if (_state.value.pendingActions > 0 || tick % 2 == 0) refresh()
        }
    }

    private fun isOnline(): Boolean {
        if (isLocalSession()) return true
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun isLocalSession(): Boolean = sessionId?.let(app.capmystere.data.backend.BackendRouter::isLocalSession) == true

    /** Session à reprendre au lancement, s'il y en a une. */
    suspend fun resumableSession(): String? = prefs.settings.first().activeSessionId

    companion object {
        const val AHEAD_M = 700.0
        const val BEHIND_M = 60.0
        const val MOVING_SPEED_MPS = 2.5f
        const val REROUTE_MIN_INTERVAL_MS = 8_000L
        const val ROUTE_RETRY_DELAY_MS = 6_000L
    }
}

package app.capmystere.ui.host

import android.content.Context
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.capmystere.core.geo.Geo
import app.capmystere.core.gpx.Gpx
import app.capmystere.core.guidance.Route
import app.capmystere.core.model.Challenge
import app.capmystere.core.model.ClueFragment
import app.capmystere.core.model.ClueKind
import app.capmystere.core.model.LatLon
import app.capmystere.core.model.LiveSession
import app.capmystere.core.model.MediaRef
import app.capmystere.core.model.Stage
import app.capmystere.core.model.Trip
import app.capmystere.data.MediaFiles
import app.capmystere.data.TripRepository
import app.capmystere.data.backend.LocalBackend
import app.capmystere.data.backend.TripBackend
import app.capmystere.data.routing.Geocoder
import app.capmystere.data.routing.Place
import app.capmystere.data.routing.RoutingProvider
import app.capmystere.location.FusedLocationSource
import app.capmystere.theme.ThemeRepository
import app.capmystere.travel.Simulation
import app.capmystere.travel.SyncEngine
import app.capmystere.travel.TravelController
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

data class EditorState(
    val trip: Trip? = null,
    /** Tronçons calculés (départ → étape 1 → étape 2 …). */
    val legs: List<Route> = emptyList(),
    val routing: Boolean = false,
    val routeError: Boolean = false,
    val selected: String? = null,
    val places: List<Place> = emptyList(),
    val searching: Boolean = false,
    val focus: LatLon? = null,
    val fitKey: Int = 0,
    val publishing: Boolean = false,
    val publishError: String? = null,
    /** Modifié depuis la dernière publication. */
    val dirty: Boolean = false,
    val live: LiveSession? = null,
    val liveError: Boolean = false,
    val recording: Boolean = false,
) {
    val distanceM: Double get() = legs.sumOf { it.distanceM }
    val durationS: Double get() = legs.sumOf { it.durationS }
}

@HiltViewModel
class EditorViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedState: SavedStateHandle,
    private val repo: TripRepository,
    private val routing: RoutingProvider,
    private val geocoder: Geocoder,
    private val backend: TripBackend,
    private val local: LocalBackend,
    private val controller: TravelController,
    private val sync: SyncEngine,
    private val media: MediaFiles,
    private val gps: FusedLocationSource,
    val themes: ThemeRepository,
) : ViewModel() {
    private val tripId: String = savedState["tripId"] ?: ""
    private val _state = MutableStateFlow(EditorState())
    val state = _state.asStateFlow()
    val isRemote: Boolean get() = backend.isRemote

    private var routeJob: Job? = null
    private var saveJob: Job? = null
    private var searchJob: Job? = null
    private var liveJob: Job? = null
    private var recorder: MediaRecorder? = null
    private var recordingFile: File? = null

    init {
        viewModelScope.launch {
            val trip = repo.get(tripId) ?: return@launch
            _state.update { it.copy(trip = trip, fitKey = 1) }
            if (trip.start == null && trip.stages.isEmpty()) gps.lastKnown { p -> if (p != null) _state.update { it.copy(focus = p) } }
            computeRoute(immediate = true)
        }
    }

    // ---- Modification du voyage -------------------------------------------------------------

    /** Applique une modification, sauvegarde (avec un léger différé) et recalcule si la géométrie change. */
    fun edit(reroute: Boolean = false, transform: (Trip) -> Trip) {
        val current = _state.value.trip ?: return
        val next = transform(current)
        if (next == current) return
        _state.update { it.copy(trip = next, dirty = true) }
        saveJob?.cancel()
        saveJob = viewModelScope.launch { delay(350); repo.save(next) }
        if (reroute) computeRoute()
    }

    private fun editStage(id: String, reroute: Boolean = false, f: (Stage) -> Stage) =
        edit(reroute) { t -> t.copy(stages = t.stages.map { if (it.id == id) f(it) else it }) }

    /** Premier appui : point de départ. Ensuite : une nouvelle étape, ou déplacement de l'étape sélectionnée. */
    fun onMapTap(p: LatLon, moveSelected: Boolean) {
        val trip = _state.value.trip ?: return
        val selected = _state.value.selected
        when {
            moveSelected && selected == START -> edit(true) { it.copy(start = p) }
            moveSelected && selected != null -> editStage(selected, true) { it.copy(position = p) }
            trip.start == null -> { edit(true) { it.copy(start = p) }; _state.update { it.copy(selected = START) } }
            else -> {
                val stage = Stage(id = UUID.randomUUID().toString(), position = p)
                edit(true) { it.copy(stages = it.stages + stage) }
                _state.update { it.copy(selected = stage.id) }
            }
        }
    }

    fun select(id: String?) = _state.update { it.copy(selected = if (it.selected == id) null else id) }

    fun moveStage(id: String, delta: Int) = edit(true) { t ->
        val i = t.stages.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j !in t.stages.indices) t else t.copy(stages = t.stages.toMutableList().apply { add(j, removeAt(i)) })
    }

    fun removeStage(id: String) {
        if (id == START) edit(true) { it.copy(start = null) } else edit(true) { t -> t.copy(stages = t.stages.filterNot { it.id == id }) }
        _state.update { it.copy(selected = null) }
    }

    fun setRadius(id: String, radiusM: Int) = editStage(id) { it.copy(radiusM = radiusM) }
    fun updateStage(id: String, f: (Stage) -> Stage) = editStage(id, f = f)

    fun upsertChallenge(stageId: String, c: Challenge) = editStage(stageId) { st ->
        if (st.challenges.any { it.id == c.id }) st.copy(challenges = st.challenges.map { if (it.id == c.id) c else it }) else st.copy(challenges = st.challenges + c)
    }

    fun removeChallenge(stageId: String, challengeId: String) = editStage(stageId) { st -> st.copy(challenges = st.challenges.filterNot { it.id == challengeId }) }

    // ---- Récompenses : attribution automatique du prochain fragment libre ------------------------

    private fun usedFragments(trip: Trip): List<ClueFragment> =
        trip.stages.flatMap { it.challenges }.flatMap { listOfNotNull(it.reward, it.failClue) } + trip.timedClues.map { it.fragment }

    /** Prochaine lettre du mot mystère pas encore distribuée, ou `null`. */
    fun nextLetter(excludingChallenge: String? = null): ClueFragment? {
        val trip = _state.value.trip ?: return null
        val word = trip.clueWord.trim()
        val used = trip.stages.flatMap { it.challenges }.filter { it.id != excludingChallenge }.flatMap { listOfNotNull(it.reward, it.failClue) }
            .filter { it.kind == ClueKind.LETTER }.map { it.slot }.toSet()
        val slot = word.indices.firstOrNull { it !in used && word[it].isLetterOrDigit() } ?: return null
        return ClueFragment(UUID.randomUUID().toString(), ClueKind.LETTER, word[slot].uppercase(), slot)
    }

    /** Prochaine tuile de l'image-indice pas encore distribuée, ou `null`. */
    fun nextPiece(excludingChallenge: String? = null): ClueFragment? {
        val trip = _state.value.trip ?: return null
        val used = trip.stages.flatMap { it.challenges }.filter { it.id != excludingChallenge }.flatMap { listOfNotNull(it.reward, it.failClue) }
            .filter { it.kind == ClueKind.IMAGE_PIECE }.map { it.slot }.toSet()
        val slot = trip.cluePieces.indices.firstOrNull { it !in used } ?: return null
        return ClueFragment(UUID.randomUUID().toString(), ClueKind.IMAGE_PIECE, slot = slot, image = trip.cluePieces[slot])
    }

    // ---- Médias -----------------------------------------------------------------------------

    fun setStageImage(stageId: String, uri: Uri?) = viewModelScope.launch {
        val ref = uri?.let { media.importImage(it) }?.let { MediaRef(localPath = it.absolutePath) }
        updateStage(stageId) { it.copy(message = it.message.copy(image = ref)) }
    }

    fun setClueImage(uri: Uri?) = viewModelScope.launch {
        val grid = _state.value.trip?.clueGrid ?: 3
        val pieces = uri?.let { media.importImage(it, 1200) }?.let { media.sliceImage(it, grid) }.orEmpty().map { MediaRef(localPath = it.absolutePath) }
        edit { it.copy(cluePieces = pieces) }
    }

    fun toggleRecording(stageId: String) {
        if (_state.value.recording) {
            runCatching { recorder?.stop() }
            recorder?.release(); recorder = null
            val file = recordingFile
            _state.update { it.copy(recording = false) }
            if (file != null && file.length() > 0) updateStage(stageId) { it.copy(message = it.message.copy(audio = MediaRef(localPath = file.absolutePath))) }
        } else {
            val file = media.newFile("m4a")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
            val ok = runCatching {
                r.setAudioSource(MediaRecorder.AudioSource.MIC)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                r.setAudioEncodingBitRate(64_000)
                r.setOutputFile(file.absolutePath)
                r.prepare(); r.start()
            }.isSuccess
            if (ok) { recorder = r; recordingFile = file; _state.update { it.copy(recording = true) } } else r.release()
        }
    }

    fun clearAudio(stageId: String) = updateStage(stageId) { it.copy(message = it.message.copy(audio = null)) }

    suspend fun resolve(ref: MediaRef): File? = backend.resolve(ref)

    // ---- Itinéraire et recherche ----------------------------------------------------------------

    private fun computeRoute(immediate: Boolean = false) {
        routeJob?.cancel()
        routeJob = viewModelScope.launch {
            if (!immediate) delay(600)
            val trip = _state.value.trip ?: return@launch
            val points = listOfNotNull(trip.start) + trip.stages.map { it.position }
            if (points.size < 2) { _state.update { it.copy(legs = emptyList(), routing = false, routeError = false) }; return@launch }
            _state.update { it.copy(routing = true, routeError = false) }
            runCatching { routing.route(points, trip.profile) }
                .onSuccess { legs -> _state.update { it.copy(legs = legs, routing = false) } }
                .onFailure { _state.update { it.copy(routing = false, routeError = true) } }
        }
    }

    fun retryRoute() = computeRoute(immediate = true)

    fun search(query: String) {
        searchJob?.cancel()
        if (query.isBlank()) { _state.update { it.copy(places = emptyList(), searching = false) }; return }
        searchJob = viewModelScope.launch {
            _state.update { it.copy(searching = true) }
            val near = _state.value.trip?.let { it.stages.lastOrNull()?.position ?: it.start }
            val places = geocoder.search(query, near)
            _state.update { it.copy(places = places, searching = false) }
        }
    }

    fun goTo(place: Place) = _state.update { it.copy(focus = place.position, places = emptyList()) }

    // ---- Aperçu, publication, suivi ---------------------------------------------------------------

    /** Lance l'aperçu « comme le voyageur » en simulation, sur le backend embarqué. */
    fun preview(gpx: List<LatLon>? = null, onStarted: () -> Unit) = viewModelScope.launch {
        val trip = _state.value.trip?.takeIf { it.stages.isNotEmpty() } ?: return@launch
        saveJob?.cancel(); repo.save(trip)
        val snapshot = local.restart(trip.id)
        sync.cacheSnapshot(snapshot)
        val start = trip.start ?: Geo.offset(trip.stages.first().position, 200.0, 1500.0)
        controller.start(snapshot.sessionId, if (gpx != null && gpx.size >= 2) Simulation.Gpx(gpx) else Simulation.FollowRoute(start))
        onStarted()
    }

    suspend fun readGpx(uri: Uri): List<LatLon> = withContext(Dispatchers.IO) {
        runCatching { context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { Gpx.parse(it.readText()) } }.getOrNull().orEmpty()
    }

    fun publish() = viewModelScope.launch {
        val trip = _state.value.trip ?: return@launch
        _state.update { it.copy(publishing = true, publishError = null) }
        runCatching { backend.publish(trip) }
            .onSuccess { stored -> repo.save(stored); _state.update { it.copy(trip = stored, publishing = false, dirty = false) } }
            .onFailure { e -> _state.update { it.copy(publishing = false, publishError = e.message ?: "erreur") } }
    }

    fun watchLive() {
        if (liveJob?.isActive == true) return
        liveJob = viewModelScope.launch {
            launch { backend.hostEvents(tripId).collect { refreshLive() } }
            while (isActive) { refreshLive(); delay(5000) }
        }
    }

    fun stopLive() { liveJob?.cancel(); liveJob = null }

    private suspend fun refreshLive() {
        runCatching { backend.live(tripId) }
            .onSuccess { live -> _state.update { it.copy(live = live, liveError = false) } }
            .onFailure { _state.update { it.copy(liveError = true) } }
    }

    fun sendMessage(text: String, isClue: Boolean) = viewModelScope.launch { runCatching { backend.sendMessage(tripId, text.trim(), isClue) }; refreshLive() }
    fun review(submissionId: String, approved: Boolean) = viewModelScope.launch { runCatching { backend.reviewPhoto(tripId, submissionId, approved) }; refreshLive() }
    fun skipStage() = viewModelScope.launch { runCatching { backend.skipStage(tripId) }; refreshLive() }

    override fun onCleared() {
        runCatching { recorder?.release() }
        _state.value.trip?.let { t -> kotlinx.coroutines.runBlocking { repo.save(t) } }
    }

    companion object {
        /** Identifiant de sélection du point de départ. */
        const val START = "__start__"
    }
}

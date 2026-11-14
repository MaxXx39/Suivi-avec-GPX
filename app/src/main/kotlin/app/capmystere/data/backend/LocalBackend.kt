package app.capmystere.data.backend

import app.capmystere.core.model.LatLon
import app.capmystere.core.model.LiveMessage
import app.capmystere.core.model.LiveSession
import app.capmystere.core.model.MediaRef
import app.capmystere.core.model.TravelerSnapshot
import app.capmystere.core.model.Trip
import app.capmystere.core.model.TripCode
import app.capmystere.core.server.SessionState
import app.capmystere.core.server.TripServer
import app.capmystere.data.db.CacheDao
import app.capmystere.data.db.CacheEntity
import app.capmystere.data.db.TripDao
import app.capmystere.data.db.TripEntity
import app.capmystere.theme.AudienceProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Backend embarqué : le « serveur » tourne sur l'appareil, avec exactement les règles de [TripServer].
 * Sert à l'aperçu « comme le voyageur », à la simulation et à l'usage sans compte serveur
 * (hôte et voyageur sur le même téléphone).
 */
class LocalBackend(private val trips: TripDao, private val cache: CacheDao) : TripBackend {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val changes = MutableSharedFlow<String>(extraBufferCapacity = 16)

    override val isRemote = false

    override suspend fun publish(trip: Trip): Trip {
        var code = trip.code.takeIf { TripCode.isValid(it) } ?: TripCode.generate()
        while (trips.byCode(code)?.let { it.id != trip.id } == true) code = TripCode.generate()
        val stored = trip.copy(code = code, published = true, updatedAt = System.currentTimeMillis())
        trips.upsert(TripEntity(stored.id, stored.code, stored.title, stored.updatedAt, json.encodeToString(Trip.serializer(), stored)))
        return stored
    }

    override suspend fun unpublish(tripId: String) {
        cache.deletePrefix("session:$tripId")
    }

    private suspend fun trip(id: String): Trip =
        trips.get(id)?.let { json.decodeFromString(Trip.serializer(), it.json) } ?: throw BackendRejected("Voyage introuvable")

    private suspend fun state(tripId: String): SessionState? =
        cache.get("session:$tripId")?.let { json.decodeFromString(SessionState.serializer(), it.json) }

    private suspend fun save(s: SessionState) {
        cache.put(CacheEntity("session:${s.tripId}", json.encodeToString(SessionState.serializer(), s), System.currentTimeMillis()))
        changes.tryEmit(s.tripId)
    }

    /** L'identifiant de session locale embarque celui du voyage : "<tripId>|<uuid>". */
    private fun tripIdOf(sessionId: String) = sessionId.substringBefore('|')

    private suspend fun <T> mutate(tripId: String, block: suspend (Trip, SessionState) -> Pair<SessionState, T>): T = mutex.withLock {
        val t = trip(tripId)
        val s = state(tripId) ?: throw BackendRejected("Aucune session en cours")
        val (next, result) = block(t, s)
        if (next != s) save(next)
        result
    }

    override suspend fun live(tripId: String): LiveSession? = state(tripId)?.let(TripServer::live)

    override suspend fun sendMessage(tripId: String, text: String, isClue: Boolean) = mutate(tripId) { _, s ->
        TripServer.addMessage(s, LiveMessage(UUID.randomUUID().toString(), text, isClue, System.currentTimeMillis())) to Unit
    }

    override suspend fun reviewPhoto(tripId: String, submissionId: String, approved: Boolean) = mutate(tripId) { t, s ->
        TripServer.review(t, s, submissionId, approved) to Unit
    }

    override suspend fun skipStage(tripId: String) = mutate(tripId) { t, s -> TripServer.skipStage(t, s) to Unit }

    override fun hostEvents(tripId: String): Flow<Unit> = changes.filter { it == tripId }.map { }

    override suspend fun join(code: String): TravelerSnapshot = mutex.withLock {
        val entity = trips.byCode(code) ?: throw BackendRejected("Code inconnu")
        val t = json.decodeFromString(Trip.serializer(), entity.json)
        if (!t.published || t.stages.isEmpty()) throw BackendRejected("Ce voyage n'est pas encore prêt")
        // Rejoindre à nouveau reprend la session existante.
        val now = System.currentTimeMillis()
        val s = state(t.id) ?: SessionState("${t.id}|${UUID.randomUUID()}", t.id, startedAt = now).also { save(it) }
        TripServer.snapshot(t, s, now)
    }

    /** Repart de zéro (aperçu et simulation côté hôte). */
    suspend fun restart(tripId: String): TravelerSnapshot = mutex.withLock {
        val t = trip(tripId)
        val now = System.currentTimeMillis()
        val s = SessionState("${t.id}|${UUID.randomUUID()}", t.id, startedAt = now)
        save(s)
        TripServer.snapshot(t, s, now)
    }

    override suspend fun snapshot(sessionId: String): TravelerSnapshot {
        val t = trip(tripIdOf(sessionId))
        val s = state(t.id)?.takeIf { it.sessionId == sessionId } ?: throw BackendRejected("Session expirée")
        return TripServer.snapshot(t, s, System.currentTimeMillis())
    }

    override suspend fun validateStage(sessionId: String, index: Int, position: LatLon, accuracyM: Float, at: Long): TravelerSnapshot =
        mutate(tripIdOf(sessionId)) { t, s ->
            val next = TripServer.validateStage(t, s, index, position, accuracyM.toDouble()) ?: throw BackendRejected("Trop loin de l'étape")
            next to TripServer.snapshot(t, next, System.currentTimeMillis())
        }

    override suspend fun submitAnswer(sessionId: String, challengeId: String, answer: String, photo: File?, gaveUp: Boolean, at: Long): TravelerSnapshot =
        mutate(tripIdOf(sessionId)) { t, s ->
            val next = TripServer.submit(
                t, s, challengeId, answer, photo?.let { MediaRef(localPath = it.absolutePath) }, gaveUp, at,
                maxAttempts = AudienceProfile.of(t.audience).maxAttempts, submissionId = UUID.randomUUID().toString(),
            )
            next to TripServer.snapshot(t, next, System.currentTimeMillis())
        }

    override suspend fun pushPositions(sessionId: String, points: List<LatLon>, at: Long) =
        mutate(tripIdOf(sessionId)) { _, s -> TripServer.addPositions(s, points, at) to Unit }

    override fun travelerEvents(sessionId: String): Flow<Unit> = hostEvents(tripIdOf(sessionId))

    override suspend fun resolve(ref: MediaRef): File? = ref.localPath?.let(::File)?.takeIf { it.exists() }
}

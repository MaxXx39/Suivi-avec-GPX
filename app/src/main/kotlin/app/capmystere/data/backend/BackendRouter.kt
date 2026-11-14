package app.capmystere.data.backend

import app.capmystere.core.model.LatLon
import app.capmystere.core.model.LiveSession
import app.capmystere.core.model.MediaRef
import app.capmystere.core.model.TravelerSnapshot
import app.capmystere.core.model.Trip
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Aiguille chaque appel vers le bon backend : les sessions locales (aperçu, simulation, usage sans
 * serveur) ont un identifiant contenant '|' et restent sur l'appareil ; tout le reste part vers
 * le backend distant s'il est configuré.
 */
class BackendRouter(private val local: LocalBackend, private val remote: TripBackend?) : TripBackend {
    override val isRemote: Boolean get() = remote != null

    private val main: TripBackend get() = remote ?: local
    private fun forSession(sessionId: String): TripBackend = if (isLocalSession(sessionId)) local else main

    override suspend fun publish(trip: Trip): Trip = main.publish(trip)
    override suspend fun unpublish(tripId: String) = main.unpublish(tripId)
    override suspend fun live(tripId: String): LiveSession? = main.live(tripId)
    override suspend fun sendMessage(tripId: String, text: String, isClue: Boolean) = main.sendMessage(tripId, text, isClue)
    override suspend fun reviewPhoto(tripId: String, submissionId: String, approved: Boolean) = main.reviewPhoto(tripId, submissionId, approved)
    override suspend fun skipStage(tripId: String) = main.skipStage(tripId)
    override fun hostEvents(tripId: String): Flow<Unit> = main.hostEvents(tripId)

    override suspend fun join(code: String): TravelerSnapshot = main.join(code)
    override suspend fun snapshot(sessionId: String) = forSession(sessionId).snapshot(sessionId)
    override suspend fun validateStage(sessionId: String, index: Int, position: LatLon, accuracyM: Float, at: Long) =
        forSession(sessionId).validateStage(sessionId, index, position, accuracyM, at)
    override suspend fun submitAnswer(sessionId: String, challengeId: String, answer: String, photo: File?, gaveUp: Boolean, at: Long) =
        forSession(sessionId).submitAnswer(sessionId, challengeId, answer, photo, gaveUp, at)
    override suspend fun pushPositions(sessionId: String, points: List<LatLon>, at: Long) = forSession(sessionId).pushPositions(sessionId, points, at)
    override fun travelerEvents(sessionId: String): Flow<Unit> = forSession(sessionId).travelerEvents(sessionId)

    override suspend fun resolve(ref: MediaRef): File? = local.resolve(ref) ?: remote?.resolve(ref)

    companion object {
        fun isLocalSession(sessionId: String): Boolean = sessionId.contains('|')
    }
}

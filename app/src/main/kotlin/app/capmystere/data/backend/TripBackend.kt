package app.capmystere.data.backend

import app.capmystere.core.model.LatLon
import app.capmystere.core.model.LiveSession
import app.capmystere.core.model.MediaRef
import app.capmystere.core.model.TravelerSnapshot
import app.capmystere.core.model.Trip
import kotlinx.coroutines.flow.Flow
import java.io.File
import java.io.IOException

/** Erreur réseau ou serveur injoignable : l'action peut être rejouée plus tard. */
class BackendUnavailable(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Refus métier (code inconnu, trop loin de l'étape…) : inutile de rejouer. */
class BackendRejected(message: String) : Exception(message)

/**
 * Frontière entre l'application et le serveur. Deux implémentations :
 * [LocalBackend] (tout sur l'appareil : démo, aperçu, simulation) et [SupabaseBackend].
 *
 * Contrat de confidentialité : les méthodes « voyageur » ne renvoient qu'un [TravelerSnapshot],
 * qui ne contient jamais ni la destination finale ni les étapes à venir.
 */
interface TripBackend {
    /** `true` si les voyages sont partagés entre appareils. */
    val isRemote: Boolean

    // ---- Hôte ----
    /** Publie (ou republie) le voyage ; renvoie la version stockée, avec son code de partage. */
    suspend fun publish(trip: Trip): Trip
    suspend fun unpublish(tripId: String)
    /** Session la plus récente du voyage, ou `null` si personne n'a encore rejoint. */
    suspend fun live(tripId: String): LiveSession?
    suspend fun sendMessage(tripId: String, text: String, isClue: Boolean)
    suspend fun reviewPhoto(tripId: String, submissionId: String, approved: Boolean)
    suspend fun skipStage(tripId: String)
    /** Signaux « quelque chose a changé » (temps réel) ; l'appelant recharge ensuite via [live]. */
    fun hostEvents(tripId: String): Flow<Unit>

    // ---- Voyageur ----
    suspend fun join(code: String): TravelerSnapshot
    suspend fun snapshot(sessionId: String): TravelerSnapshot
    suspend fun validateStage(sessionId: String, index: Int, position: LatLon, accuracyM: Float, at: Long): TravelerSnapshot
    suspend fun submitAnswer(sessionId: String, challengeId: String, answer: String, photo: File?, gaveUp: Boolean, at: Long): TravelerSnapshot
    suspend fun pushPositions(sessionId: String, points: List<LatLon>, at: Long)
    fun travelerEvents(sessionId: String): Flow<Unit>

    // ---- Médias ----
    /** Rend un média lisible localement (télécharge si nécessaire). `null` si indisponible. */
    suspend fun resolve(ref: MediaRef): File?
}

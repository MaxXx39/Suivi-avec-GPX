package app.capmystere.data

import app.capmystere.core.model.Trip
import app.capmystere.data.db.TripDao
import app.capmystere.data.db.TripEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Voyages de l'hôte, stockés sur l'appareil (brouillons et voyages publiés). */
@Singleton
class TripRepository @Inject constructor(private val dao: TripDao) {
    private val json = Json { ignoreUnknownKeys = true }

    fun observeAll(): Flow<List<Trip>> = dao.observeAll().map { list -> list.mapNotNull { decode(it.json) } }

    suspend fun get(id: String): Trip? = dao.get(id)?.let { decode(it.json) }

    suspend fun save(trip: Trip) {
        val t = trip.copy(updatedAt = System.currentTimeMillis())
        dao.upsert(TripEntity(t.id, t.code, t.title, t.updatedAt, json.encodeToString(Trip.serializer(), t)))
    }

    suspend fun delete(id: String) = dao.delete(id)

    suspend fun create(themeId: String): Trip = Trip(id = UUID.randomUUID().toString(), themeId = themeId).also { save(it) }

    private fun decode(s: String): Trip? = runCatching { json.decodeFromString(Trip.serializer(), s) }.getOrNull()
}

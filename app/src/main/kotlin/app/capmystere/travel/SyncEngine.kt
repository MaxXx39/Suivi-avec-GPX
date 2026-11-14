package app.capmystere.travel

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.capmystere.core.model.LatLon
import app.capmystere.core.model.TravelerSnapshot
import app.capmystere.data.backend.BackendRejected
import app.capmystere.data.backend.TripBackend
import app.capmystere.data.db.CacheDao
import app.capmystere.data.db.CacheEntity
import app.capmystere.data.db.OutboxDao
import app.capmystere.data.db.OutboxEntity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable data class ValidatePayload(val index: Int, val lat: Double, val lon: Double, val accuracyM: Float, val at: Long)
@Serializable data class AnswerPayload(val challengeId: String, val answer: String, val photoPath: String? = null, val gaveUp: Boolean = false, val at: Long)
@Serializable data class PositionsPayload(val points: List<LatLon>, val at: Long)

/**
 * File d'attente hors ligne : les actions du voyageur sont enregistrées localement puis rejouées
 * dans l'ordre dès que le serveur répond. Un refus métier est abandonné ; une panne réseau arrête
 * le rejeu (on réessaiera plus tard).
 */
@Singleton
class SyncEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val backend: TripBackend,
    private val outbox: OutboxDao,
    private val cache: CacheDao,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    suspend fun enqueueValidate(sessionId: String, p: ValidatePayload) = enqueue(sessionId, KIND_VALIDATE, json.encodeToString(ValidatePayload.serializer(), p))
    suspend fun enqueueAnswer(sessionId: String, p: AnswerPayload) = enqueue(sessionId, KIND_ANSWER, json.encodeToString(AnswerPayload.serializer(), p))
    suspend fun enqueuePositions(sessionId: String, p: PositionsPayload) = enqueue(sessionId, KIND_POSITIONS, json.encodeToString(PositionsPayload.serializer(), p))

    private suspend fun enqueue(sessionId: String, kind: String, payload: String) {
        outbox.add(OutboxEntity(sessionId = sessionId, kind = kind, payload = payload, createdAt = System.currentTimeMillis()))
        // Filet de sécurité si l'application est tuée : WorkManager rejouera au retour du réseau.
        WorkManager.getInstance(context).enqueueUniqueWork(
            "cap-sync", ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build(),
        )
    }

    suspend fun cacheSnapshot(s: TravelerSnapshot) =
        cache.put(CacheEntity("snapshot:${s.sessionId}", json.encodeToString(TravelerSnapshot.serializer(), s), System.currentTimeMillis()))

    suspend fun cachedSnapshot(sessionId: String): TravelerSnapshot? =
        cache.get("snapshot:$sessionId")?.let { runCatching { json.decodeFromString(TravelerSnapshot.serializer(), it.json) }.getOrNull() }

    /** Rejoue la file. Renvoie le dernier instantané obtenu, ou `null` si rien n'a changé. `complete` = file vidée. */
    suspend fun flush(): FlushResult = mutex.withLock {
        var latest: TravelerSnapshot? = null
        for (item in outbox.all()) {
            try {
                when (item.kind) {
                    KIND_VALIDATE -> json.decodeFromString(ValidatePayload.serializer(), item.payload).let {
                        latest = backend.validateStage(item.sessionId, it.index, LatLon(it.lat, it.lon), it.accuracyM, it.at)
                    }
                    KIND_ANSWER -> json.decodeFromString(AnswerPayload.serializer(), item.payload).let {
                        latest = backend.submitAnswer(item.sessionId, it.challengeId, it.answer, it.photoPath?.let(::File)?.takeIf { f -> f.exists() }, it.gaveUp, it.at)
                    }
                    KIND_POSITIONS -> json.decodeFromString(PositionsPayload.serializer(), item.payload).let {
                        backend.pushPositions(item.sessionId, it.points, it.at)
                    }
                }
                outbox.delete(item.id)
            } catch (e: BackendRejected) {
                outbox.delete(item.id) // le serveur a tranché : inutile d'insister
            } catch (e: Exception) {
                outbox.bump(item.id)
                return@withLock FlushResult(latest, complete = false)
            }
        }
        latest?.let { cacheSnapshot(it) }
        FlushResult(latest, complete = true)
    }

    data class FlushResult(val snapshot: TravelerSnapshot?, val complete: Boolean)

    companion object {
        const val KIND_VALIDATE = "validate"
        const val KIND_ANSWER = "answer"
        const val KIND_POSITIONS = "positions"
    }
}

@HiltWorker
class SyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val sync: SyncEngine,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = if (sync.flush().complete) Result.success() else Result.retry()
}

package app.capmystere.data.backend

import app.capmystere.core.challenge.Answers
import app.capmystere.core.model.ChallengeKind
import app.capmystere.core.model.LatLon
import app.capmystere.core.model.LiveSession
import app.capmystere.core.model.MediaRef
import app.capmystere.core.model.TravelerSnapshot
import app.capmystere.core.model.Trip
import app.capmystere.core.model.TripCode
import app.capmystere.core.server.ServerDoc
import app.capmystere.core.server.mapMedia
import app.capmystere.data.MediaFiles
import app.capmystere.data.Prefs
import app.capmystere.theme.AudienceProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.File
import java.io.IOException

/**
 * Backend Supabase, sans SDK : PostgREST (fonctions RPC), GoTrue (authentification anonyme),
 * Storage (médias) et Realtime (websocket Phoenix). Toute la logique métier vit dans les fonctions
 * SQL de `supabase/schema.sql` ; ce client ne fait que les appeler.
 */
class SupabaseBackend(
    private val client: OkHttpClient,
    baseUrl: String,
    private val anonKey: String,
    private val prefs: Prefs,
    private val media: MediaFiles,
) : TripBackend {
    private val base = baseUrl.trimEnd('/')
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false; coerceInputValues = true }
    private val jsonType = "application/json".toMediaType()
    private val authMutex = Mutex()
    private var auth: AuthState? = null

    override val isRemote = true

    @Serializable
    private data class AuthState(val accessToken: String, val refreshToken: String, val expiresAt: Long)

    // ---- Authentification anonyme ---------------------------------------------------------

    private suspend fun token(forceRefresh: Boolean = false): String = authMutex.withLock {
        val current = auth ?: prefs.authJson()?.let { runCatching { json.decodeFromString(AuthState.serializer(), it) }.getOrNull() }
        val now = System.currentTimeMillis() / 1000
        val fresh = when {
            current == null -> signUp()
            forceRefresh || current.expiresAt - now < 120 -> runCatching { refresh(current.refreshToken) }.getOrElse { if (it is BackendUnavailable) throw it else signUp() }
            else -> current
        }
        if (fresh != auth) { auth = fresh; prefs.setAuthJson(json.encodeToString(AuthState.serializer(), fresh)) }
        fresh.accessToken
    }

    private suspend fun signUp(): AuthState = parseAuth(http(Request.Builder().url("$base/auth/v1/signup").post("{}".toRequestBody(jsonType))))

    private suspend fun refresh(refreshToken: String): AuthState = parseAuth(
        http(Request.Builder().url("$base/auth/v1/token?grant_type=refresh_token").post(buildJsonObject { put("refresh_token", refreshToken) }.toString().toRequestBody(jsonType)))
    )

    private fun parseAuth(body: String): AuthState {
        val o = json.parseToJsonElement(body).jsonObject
        val access = o["access_token"]?.jsonPrimitive?.content ?: throw BackendRejected("Authentification anonyme refusée (activez « Anonymous sign-ins » dans Supabase)")
        val expiresIn = o["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600
        return AuthState(access, o["refresh_token"]?.jsonPrimitive?.content.orEmpty(), System.currentTimeMillis() / 1000 + expiresIn)
    }

    // ---- HTTP ------------------------------------------------------------------------------

    /** Exécute une requête ; 4xx => [BackendRejected], réseau/5xx => [BackendUnavailable]. */
    private suspend fun http(builder: Request.Builder, bearer: String? = null): String = withContext(Dispatchers.IO) {
        builder.header("apikey", anonKey).header("Authorization", "Bearer ${bearer ?: anonKey}")
        val response: Response = try {
            client.newCall(builder.build()).execute()
        } catch (e: IOException) {
            throw BackendUnavailable("Serveur injoignable", e)
        }
        response.use { r ->
            val body = r.body?.string().orEmpty()
            when {
                r.isSuccessful -> body
                r.code == 401 -> throw Unauthorized()
                r.code in 400..499 -> throw BackendRejected(errorMessage(body))
                else -> throw BackendUnavailable("Erreur serveur ${r.code}")
            }
        }
    }

    private class Unauthorized : Exception()

    private fun errorMessage(body: String): String =
        runCatching { json.parseToJsonElement(body).jsonObject.let { it["message"] ?: it["msg"] ?: it["error"] }?.jsonPrimitive?.content }.getOrNull() ?: "Requête refusée"

    private suspend fun <T> authed(block: suspend (String) -> T): T = try {
        block(token())
    } catch (e: Unauthorized) {
        try { block(token(forceRefresh = true)) } catch (e2: Unauthorized) { throw BackendRejected("Session expirée") }
    }

    private suspend fun rpc(function: String, args: JsonObject): JsonElement = authed { jwt ->
        val body = http(Request.Builder().url("$base/rest/v1/rpc/$function").post(args.toString().toRequestBody(jsonType)), jwt)
        if (body.isBlank()) JsonNull else json.parseToJsonElement(body)
    }

    private fun snapshotOf(e: JsonElement): TravelerSnapshot = json.decodeFromJsonElement(TravelerSnapshot.serializer(), e)

    // ---- Médias ----------------------------------------------------------------------------

    private suspend fun upload(file: File, path: String): String = authed { jwt ->
        val type = when (file.extension.lowercase()) { "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "m4a", "mp4" -> "audio/mp4"; else -> "application/octet-stream" }
        http(Request.Builder().url("$base/storage/v1/object/$BUCKET/$path").header("x-upsert", "true").post(file.asRequestBody(type.toMediaType())), jwt)
        path
    }

    override suspend fun resolve(ref: MediaRef): File? {
        ref.localPath?.let(::File)?.takeIf { it.exists() }?.let { return it }
        val path = ref.remotePath ?: return null
        val target = media.cacheFile(path)
        if (target.exists() && target.length() > 0) return target
        return runCatching {
            authed { jwt ->
                withContext(Dispatchers.IO) {
                    val request = Request.Builder().url("$base/storage/v1/object/authenticated/$BUCKET/$path")
                        .header("apikey", anonKey).header("Authorization", "Bearer $jwt").build()
                    client.newCall(request).execute().use { r ->
                        if (r.code == 401) throw Unauthorized()
                        if (!r.isSuccessful) return@use null
                        val tmp = File(target.path + ".part")
                        r.body?.byteStream()?.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                        tmp.renameTo(target)
                        target
                    }
                }
            }
        }.getOrNull()
    }

    // ---- Hôte ------------------------------------------------------------------------------

    override suspend fun publish(trip: Trip): Trip {
        // 1) Envoi des médias pas encore présents sur le serveur.
        val pending = ArrayList<MediaRef>()
        trip.mapMedia { if (it.remotePath == null && it.localPath != null) pending.add(it); it }
        val uploaded = HashMap<String, String>()
        for (ref in pending.distinctBy { it.localPath }) {
            val file = File(ref.localPath!!)
            if (file.exists()) uploaded[ref.localPath!!] = upload(file, "trips/${trip.id}/${file.name}")
        }
        var stored = trip.mapMedia { r -> if (r.remotePath == null) r.copy(remotePath = uploaded[r.localPath]) else r }
            .copy(published = true, updatedAt = System.currentTimeMillis())
        // 2) Publication ; en cas de collision de code, on en tire un autre.
        var code = stored.code.takeIf { TripCode.isValid(it) } ?: TripCode.generate()
        repeat(5) {
            stored = stored.copy(code = code)
            val result = runCatching {
                rpc("publish_trip", buildJsonObject {
                    put("p_id", stored.id)
                    put("p_code", code)
                    put("p_doc", ServerDoc.build(stored, AudienceProfile.of(stored.audience).maxAttempts))
                })
            }
            val error = result.exceptionOrNull() ?: return stored
            if (error is BackendRejected && error.message?.contains("CODE_TAKEN") == true) code = TripCode.generate() else throw error
        }
        throw BackendRejected("Impossible d'attribuer un code de partage")
    }

    override suspend fun unpublish(tripId: String) { rpc("unpublish_trip", buildJsonObject { put("p_trip", tripId) }) }

    override suspend fun live(tripId: String): LiveSession? {
        val e = rpc("host_live", buildJsonObject { put("p_trip", tripId) })
        return if (e is JsonNull) null else json.decodeFromJsonElement(LiveSession.serializer(), e)
    }

    override suspend fun sendMessage(tripId: String, text: String, isClue: Boolean) {
        rpc("host_send_message", buildJsonObject { put("p_trip", tripId); put("p_text", text); put("p_is_clue", isClue) })
    }

    override suspend fun reviewPhoto(tripId: String, submissionId: String, approved: Boolean) {
        rpc("host_review", buildJsonObject { put("p_submission", submissionId); put("p_approved", approved) })
    }

    override suspend fun skipStage(tripId: String) { rpc("host_skip_stage", buildJsonObject { put("p_trip", tripId) }) }

    override fun hostEvents(tripId: String): Flow<Unit> = realtime(
        "host-$tripId", listOf(Change("sessions", "trip_id=eq.$tripId"), Change("submissions", null), Change("messages", null)),
    )

    // ---- Voyageur --------------------------------------------------------------------------

    override suspend fun join(code: String): TravelerSnapshot = snapshotOf(rpc("join_trip", buildJsonObject { put("p_code", code) }))

    override suspend fun snapshot(sessionId: String): TravelerSnapshot = snapshotOf(rpc("traveler_snapshot", buildJsonObject { put("p_session", sessionId) }))

    override suspend fun validateStage(sessionId: String, index: Int, position: LatLon, accuracyM: Float, at: Long): TravelerSnapshot =
        snapshotOf(rpc("validate_stage", buildJsonObject {
            put("p_session", sessionId); put("p_index", index); put("p_lat", position.lat); put("p_lon", position.lon); put("p_accuracy", accuracyM)
        }))

    override suspend fun submitAnswer(sessionId: String, challengeId: String, answer: String, photo: File?, gaveUp: Boolean, at: Long): TravelerSnapshot {
        val photoPath = photo?.let { upload(media.compress(it), "sessions/$sessionId/${it.name}") }
        // Le type du défi n'est pas connu ici : on envoie les deux formes canoniques possibles,
        // le serveur compare leurs empreintes à celles du défi.
        return snapshotOf(rpc("submit_answer", buildJsonObject {
            put("p_session", sessionId); put("p_challenge", challengeId); put("p_answer", answer)
            put("p_canonicals", buildJsonArray {
                add(kotlinx.serialization.json.JsonPrimitive(Answers.canonical(ChallengeKind.QUIZ, answer)))
                add(kotlinx.serialization.json.JsonPrimitive(Answers.canonical(ChallengeKind.FREE_TEXT, answer)))
            })
            put("p_photo_path", photoPath); put("p_gave_up", gaveUp)
        }))
    }

    override suspend fun pushPositions(sessionId: String, points: List<LatLon>, at: Long) {
        rpc("push_positions", buildJsonObject {
            put("p_session", sessionId)
            put("p_points", json.encodeToJsonElement(ListSerializer(LatLon.serializer()), points))
        })
    }

    override fun travelerEvents(sessionId: String): Flow<Unit> = realtime(
        "traveler-$sessionId", listOf(Change("sessions", "id=eq.$sessionId"), Change("messages", "session_id=eq.$sessionId"), Change("submissions", "session_id=eq.$sessionId")),
    )

    // ---- Temps réel (protocole Phoenix) ------------------------------------------------------

    private data class Change(val table: String, val filter: String?)

    /**
     * Émet un signal à chaque changement Postgres visible par l'utilisateur (RLS appliquée).
     * Se reconnecte seul ; l'application garde de toute façon un rafraîchissement périodique.
     */
    private fun realtime(topic: String, changes: List<Change>): Flow<Unit> = callbackFlow {
        val jwt = token()
        val url = base.replaceFirst("http", "ws") + "/realtime/v1/websocket?apikey=$anonKey&vsn=1.0.0"
        var ref = 0
        val socket = client.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(buildJsonObject {
                    put("topic", "realtime:$topic"); put("event", "phx_join"); put("ref", (++ref).toString())
                    put("payload", buildJsonObject {
                        put("access_token", jwt)
                        put("config", buildJsonObject {
                            put("postgres_changes", buildJsonArray {
                                for (c in changes) add(buildJsonObject {
                                    put("event", "*"); put("schema", "public"); put("table", c.table)
                                    if (c.filter != null) put("filter", c.filter)
                                })
                            })
                        })
                    })
                }.toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("\"postgres_changes\"") && !text.contains("\"phx_reply\"")) trySend(Unit)
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { close(IOException(t)) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { close(IOException("fermé")) }
        })
        val heartbeat = launch {
            while (isActive) {
                delay(25_000)
                socket.send(buildJsonObject { put("topic", "phoenix"); put("event", "heartbeat"); put("payload", buildJsonObject { }); put("ref", (++ref).toString()) }.toString())
            }
        }
        awaitClose { heartbeat.cancel(); socket.close(1000, null) }
    }.retryWhen { _, attempt -> delay(minOf(60_000L, 3_000L * (attempt + 1))); true }

    companion object {
        const val BUCKET = "media"
    }
}

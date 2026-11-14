package app.capmystere.data.routing

import app.capmystere.core.guidance.Route
import app.capmystere.core.model.LatLon
import app.capmystere.core.model.TravelProfile
import app.capmystere.core.routing.Valhalla
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder

/** Abstraction du calcul d'itinéraire : remplaçable par OpenRouteService, GraphHopper, un Valhalla auto-hébergé… */
interface RoutingProvider {
    /**
     * Itinéraire passant par [points] dans l'ordre : un [Route] par tronçon.
     * [headingDeg] : cap actuel du véhicule, pour éviter un demi-tour immédiat lors d'un recalcul.
     */
    suspend fun route(points: List<LatLon>, profile: TravelProfile, headingDeg: Double? = null): List<Route>
}

class RoutingException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Implémentation par défaut : serveur Valhalla (instance publique FOSSGIS, sans clé, usage raisonnable). */
class ValhallaRoutingProvider(private val client: OkHttpClient, private val baseUrl: String) : RoutingProvider {
    override suspend fun route(points: List<LatLon>, profile: TravelProfile, headingDeg: Double?): List<Route> = withContext(Dispatchers.IO) {
        require(points.size >= 2) { "Il faut au moins deux points" }
        val request = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/route")
            .header("User-Agent", USER_AGENT)
            .post(Valhalla.requestBody(points, profile, headingDeg).toRequestBody("application/json".toMediaType()))
            .build()
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw RoutingException("Valhalla HTTP ${response.code}")
                Valhalla.parse(body)
            }
        } catch (e: RoutingException) {
            throw e
        } catch (e: Exception) {
            throw RoutingException("Calcul d'itinéraire impossible", e)
        }
    }

    companion object {
        const val USER_AGENT = "CapMystere-Android"
    }
}

data class Place(val name: String, val detail: String, val position: LatLon)

/** Recherche de lieux pour l'éditeur de l'hôte. */
interface Geocoder {
    suspend fun search(query: String, near: LatLon? = null): List<Place>
}

/** Photon (komoot) : géocodeur libre basé sur OpenStreetMap, sans clé. */
class PhotonGeocoder(private val client: OkHttpClient, private val baseUrl: String = "https://photon.komoot.io") : Geocoder {
    override suspend fun search(query: String, near: LatLon?): List<Place> = withContext(Dispatchers.IO) {
        val q = URLEncoder.encode(query.trim(), "UTF-8")
        val bias = near?.let { "&lat=${it.lat}&lon=${it.lon}" }.orEmpty()
        val request = Request.Builder().url("$baseUrl/api/?q=$q&limit=6&lang=fr$bias").header("User-Agent", ValhallaRoutingProvider.USER_AGENT).build()
        runCatching {
            client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) return@use emptyList()
                Json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject["features"]!!.jsonArray.mapNotNull { f ->
                    val o = f.jsonObject
                    val c = o["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
                    val p = o["properties"]!!.jsonObject
                    fun s(k: String) = p[k]?.jsonPrimitive?.content
                    val name = s("name") ?: listOfNotNull(s("housenumber"), s("street")).joinToString(" ").ifEmpty { return@mapNotNull null }
                    Place(name, listOfNotNull(s("city") ?: s("county"), s("state"), s("country")).distinct().joinToString(", "), LatLon(c[1].jsonPrimitive.double, c[0].jsonPrimitive.double))
                }
            }
        }.getOrDefault(emptyList())
    }
}

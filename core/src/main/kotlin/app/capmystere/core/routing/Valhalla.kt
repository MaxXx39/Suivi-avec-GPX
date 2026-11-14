package app.capmystere.core.routing

import app.capmystere.core.geo.Geo
import app.capmystere.core.guidance.Maneuver
import app.capmystere.core.guidance.ManeuverType
import app.capmystere.core.guidance.Route
import app.capmystere.core.model.LatLon
import app.capmystere.core.model.TravelProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Construction de requête et lecture de réponse Valhalla (/route), sans E/S : testable sur JVM. */
object Valhalla {
    private val json = Json { ignoreUnknownKeys = true }

    fun costing(profile: TravelProfile): String = when (profile) {
        TravelProfile.CAR -> "auto"
        TravelProfile.BIKE -> "bicycle"
        TravelProfile.WALK -> "pedestrian"
    }

    fun requestBody(points: List<LatLon>, profile: TravelProfile, headingDeg: Double? = null): String =
        buildJsonObject {
            put("locations", buildJsonArray {
                points.forEachIndexed { i, p ->
                    add(buildJsonObject {
                        put("lat", p.lat)
                        put("lon", p.lon)
                        put("type", "break")
                        // Le cap évite qu'un recalcul propose un demi-tour immédiat.
                        if (i == 0 && headingDeg != null) {
                            put("heading", headingDeg.toInt())
                            put("heading_tolerance", 60)
                        }
                    })
                }
            })
            put("costing", costing(profile))
            put("units", "kilometers")
            // On n'utilise pas les textes de Valhalla (ils citent des villes et des panneaux) :
            // les instructions sont reconstruites localement à partir du type de manœuvre.
            put("directions_type", "maneuvers")
        }.toString()

    /** Un [Route] par tronçon entre deux points d'arrêt. */
    fun parse(body: String): List<Route> {
        val trip = json.parseToJsonElement(body).jsonObject["trip"]?.jsonObject
            ?: throw IllegalArgumentException("Réponse Valhalla sans 'trip'")
        return trip["legs"]!!.jsonArray.map { parseLeg(it.jsonObject) }
    }

    private fun parseLeg(leg: JsonObject): Route {
        val points = Geo.decodePolyline(leg["shape"]!!.jsonPrimitive.content, 6)
        val maneuvers = ArrayList<Maneuver>()
        for (el in leg["maneuvers"]!!.jsonArray) {
            val m = el.jsonObject
            val type = mapType(m["type"]!!.jsonPrimitive.int) ?: continue
            maneuvers.add(
                Maneuver(
                    type = type,
                    shapeIndex = m["begin_shape_index"]!!.jsonPrimitive.int.coerceIn(0, points.lastIndex.coerceAtLeast(0)),
                    exit = m["roundabout_exit_count"]?.jsonPrimitive?.int ?: 0,
                )
            )
        }
        val summary = leg["summary"]?.jsonObject
        return Route(
            points = points,
            maneuvers = maneuvers,
            distanceM = (summary?.get("length")?.jsonPrimitive?.double ?: 0.0) * 1000.0,
            durationS = summary?.get("time")?.jsonPrimitive?.double ?: 0.0,
        )
    }

    /** Types Valhalla -> types internes. `null` = non-événement (changement de nom, sortie de rond-point…). */
    fun mapType(t: Int): ManeuverType? = when (t) {
        1, 2, 3 -> ManeuverType.DEPART
        4, 5, 6 -> ManeuverType.ARRIVE
        9 -> ManeuverType.SLIGHT_RIGHT
        10 -> ManeuverType.RIGHT
        11 -> ManeuverType.SHARP_RIGHT
        12, 13 -> ManeuverType.UTURN
        14 -> ManeuverType.SHARP_LEFT
        15 -> ManeuverType.LEFT
        16 -> ManeuverType.SLIGHT_LEFT
        18, 20 -> ManeuverType.EXIT_RIGHT
        19, 21 -> ManeuverType.EXIT_LEFT
        23 -> ManeuverType.KEEP_RIGHT
        24 -> ManeuverType.KEEP_LEFT
        25, 37, 38 -> ManeuverType.MERGE
        26 -> ManeuverType.ROUNDABOUT
        28 -> ManeuverType.FERRY
        else -> null // 0, 7 (devient), 8 (continuer), 17, 22, 27 (sortie de rond-point), 29…
    }
}

package app.capmystere.core.server

import app.capmystere.core.challenge.Answers
import app.capmystere.core.model.Challenge
import app.capmystere.core.model.ChallengeKind
import app.capmystere.core.model.ChallengePlacement
import app.capmystere.core.model.ClueFragment
import app.capmystere.core.model.LatLon
import app.capmystere.core.model.MediaRef
import app.capmystere.core.model.PhotoValidation
import app.capmystere.core.model.StageMessage
import app.capmystere.core.model.TimedClue
import app.capmystere.core.model.TravelerChallenge
import app.capmystere.core.model.Trip
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Document stocké côté serveur (colonne `trips.doc`). Trois parties :
 * - `trip`    : le voyage complet, pour que l'hôte puisse le retrouver ;
 * - `pub`     : la projection « voyageur » pré-calculée (sans réponses, avec empreintes), que les
 *               fonctions SQL découpent selon la progression ;
 * - `rewards` : par défi, la récompense et l'indice de consolation, livrés seulement au verdict.
 *
 * La normalisation des réponses est faite ici, par l'hôte, une fois pour toutes : le serveur ne
 * compare que des empreintes, ce qui évite d'avoir deux implémentations de la normalisation.
 */
object ServerDoc {
    private val json = Json { encodeDefaults = true }

    fun build(trip: Trip, maxAttempts: Int): JsonObject {
        val clean = trip.mapMedia { it.copy(localPath = null) } // les chemins locaux de l'hôte ne sortent pas de son appareil
        return buildJsonObject {
            put("trip", json.encodeToJsonElement(Trip.serializer(), clean))
            put("pub", buildJsonObject {
                put("title", clean.title)
                put("themeId", clean.themeId)
                put("audience", clean.audience.name)
                put("profile", clean.profile.name)
                put("clueWordLength", clean.clueWord.trim().length)
                put("clueGrid", clean.clueGrid)
                put("positionIntervalS", clean.positionIntervalS)
                put("showStreetNames", clean.showStreetNames)
                put("maxAttempts", maxAttempts)
                put("stages", buildJsonArray {
                    for (st in clean.stages) add(buildJsonObject {
                        put("title", st.title)
                        put("position", json.encodeToJsonElement(LatLon.serializer(), st.position))
                        put("radiusM", st.radiusM)
                        put("message", json.encodeToJsonElement(StageMessage.serializer(), st.message))
                        put("atStage", challenges(clean, st.challenges, ChallengePlacement.AT_STAGE))
                        put("enRoute", challenges(clean, st.challenges, ChallengePlacement.EN_ROUTE))
                    })
                })
                put("timedClues", json.encodeToJsonElement(ListSerializer(TimedClue.serializer()), clean.timedClues))
            })
            put("rewards", buildJsonObject {
                for (st in clean.stages) for (c in st.challenges) put(c.id, buildJsonObject {
                    put("kind", c.kind.name)
                    put("prompt", c.prompt)
                    put("isPhoto", c.kind == ChallengeKind.PHOTO || c.kind == ChallengeKind.MISSION)
                    put("hostValidation", c.photoValidation == PhotoValidation.HOST)
                    put("reward", c.reward?.let { json.encodeToJsonElement(ClueFragment.serializer(), it) } ?: JsonNull)
                    put("failClue", c.failClue?.let { json.encodeToJsonElement(ClueFragment.serializer(), it) } ?: JsonNull)
                })
            })
        }
    }

    private fun challenges(trip: Trip, list: List<Challenge>, placement: ChallengePlacement) =
        json.encodeToJsonElement(
            ListSerializer(TravelerChallenge.serializer()),
            list.filter { it.placement == placement }.map { Answers.toTraveler(it, TripServer.salt(trip, it)) },
        )
}

/** Applique [f] à toutes les références de médias d'un voyage. */
fun Trip.mapMedia(f: (MediaRef) -> MediaRef): Trip {
    fun frag(c: ClueFragment?): ClueFragment? = c?.let { it.copy(image = it.image?.let(f)) }
    return copy(
        stages = stages.map { st ->
            st.copy(
                message = st.message.copy(image = st.message.image?.let(f), audio = st.message.audio?.let(f)),
                challenges = st.challenges.map { it.copy(reward = frag(it.reward), failClue = frag(it.failClue)) },
            )
        },
        timedClues = timedClues.map { it.copy(fragment = frag(it.fragment)!!) },
        cluePieces = cluePieces.map(f),
    )
}

package app.capmystere.core.model

import kotlinx.serialization.Serializable

/** Coordonnée WGS84. */
@Serializable
data class LatLon(val lat: Double, val lon: Double)

enum class TravelProfile { CAR, BIKE, WALK }

enum class Audience { KIDS, FRIENDS, COUPLE, ADULTS }

/** Référence à un média : chemin local (appareil) et/ou chemin distant (stockage serveur). */
@Serializable
data class MediaRef(val localPath: String? = null, val remotePath: String? = null)

@Serializable
data class StageMessage(
    val text: String = "",
    val image: MediaRef? = null,
    val audio: MediaRef? = null,
)

enum class ChallengeKind { QUIZ, FREE_TEXT, PHOTO, QR_SCAN, FIND_ON_SITE, RIDDLE, MISSION }

/** À l'étape (à l'arrêt) ou le long du trajet (proposé, mais conseillé à l'arrêt). */
enum class ChallengePlacement { AT_STAGE, EN_ROUTE }

enum class PhotoValidation { AUTO, HOST }

enum class ClueKind { LETTER, TEXT, IMAGE_PIECE }

/**
 * Fragment d'indice sur la destination finale.
 * - LETTER : [text] = la lettre, [slot] = sa position dans le mot mystère.
 * - TEXT : [text] = l'indice.
 * - IMAGE_PIECE : [slot] = index de la tuile dans la grille, [image] = la tuile (pré-découpée côté hôte).
 */
@Serializable
data class ClueFragment(
    val id: String,
    val kind: ClueKind,
    val text: String = "",
    val slot: Int = 0,
    val image: MediaRef? = null,
)

/** Défi tel que l'hôte l'édite (contient les réponses en clair : ne quitte jamais l'hôte ni le serveur). */
@Serializable
data class Challenge(
    val id: String,
    val kind: ChallengeKind,
    val placement: ChallengePlacement = ChallengePlacement.AT_STAGE,
    val prompt: String = "",
    val options: List<String> = emptyList(),
    val correctOption: Int = 0,
    val acceptedAnswers: List<String> = emptyList(),
    val photoValidation: PhotoValidation = PhotoValidation.AUTO,
    val hint: String = "",
    val reward: ClueFragment? = null,
    /** Indice de consolation débloqué si le défi est raté. */
    val failClue: ClueFragment? = null,
)

@Serializable
data class Stage(
    val id: String,
    val title: String = "",
    val position: LatLon,
    val radiusM: Int = 80,
    val message: StageMessage = StageMessage(),
    val challenges: List<Challenge> = emptyList(),
)

/** Indice programmé : débloqué [afterMinutes] minutes après le départ. */
@Serializable
data class TimedClue(val id: String, val afterMinutes: Int, val fragment: ClueFragment)

/** Voyage complet, vue hôte. */
@Serializable
data class Trip(
    val id: String,
    val code: String = "",
    val title: String = "",
    val themeId: String = "pirates",
    val audience: Audience = Audience.FRIENDS,
    val profile: TravelProfile = TravelProfile.CAR,
    /** Point de départ prévu : sert aux estimations de l'hôte et à la simulation, jamais transmis au voyageur. */
    val start: LatLon? = null,
    val stages: List<Stage> = emptyList(),
    val timedClues: List<TimedClue> = emptyList(),
    /** Mot mystère dont les lettres sont distribuées en fragments (facultatif). */
    val clueWord: String = "",
    /** Côté de la grille de l'image-indice (3 => 9 tuiles). */
    val clueGrid: Int = 3,
    /** Tuiles de l'image-indice, pré-découpées par l'hôte (index = position dans la grille). */
    val cluePieces: List<MediaRef> = emptyList(),
    val positionIntervalS: Int = 10,
    /** Les noms de rues peuvent trahir la destination (« Route de … ») : masqués par défaut. */
    val showStreetNames: Boolean = false,
    val updatedAt: Long = 0,
    val published: Boolean = false,
)

// ---------------------------------------------------------------------------------------------
// Vue voyageur : uniquement ce qui est déjà débloqué + la cible du tronçon courant.
// ---------------------------------------------------------------------------------------------

/** Défi tel que le voyageur le reçoit : jamais de réponse en clair, seulement des empreintes salées. */
@Serializable
data class TravelerChallenge(
    val id: String,
    val kind: ChallengeKind,
    val placement: ChallengePlacement,
    val prompt: String,
    val options: List<String> = emptyList(),
    val photoValidation: PhotoValidation = PhotoValidation.AUTO,
    val hint: String = "",
    val salt: String = "",
    val answerHashes: List<String> = emptyList(),
    val hasReward: Boolean = false,
)

enum class ChallengeStatus { PENDING, SUCCESS, FAILED, AWAITING_HOST }

@Serializable
data class UnlockedStage(
    val index: Int,
    val title: String,
    val message: StageMessage,
    val challenges: List<TravelerChallenge> = emptyList(),
)

@Serializable
data class Target(val position: LatLon, val radiusM: Int)

@Serializable
data class LiveMessage(val id: String, val text: String, val isClue: Boolean = false, val at: Long = 0)

@Serializable
data class TravelerSnapshot(
    val tripId: String,
    val sessionId: String,
    val title: String,
    val themeId: String,
    val audience: Audience,
    val profile: TravelProfile,
    /** Index de l'étape visée (0 = première). Le nombre total d'étapes n'est jamais transmis. */
    val currentIndex: Int = 0,
    val finished: Boolean = false,
    /** Cible du tronçon courant uniquement. */
    val target: Target? = null,
    val unlocked: List<UnlockedStage> = emptyList(),
    val enRouteChallenges: List<TravelerChallenge> = emptyList(),
    val challengeStatus: Map<String, ChallengeStatus> = emptyMap(),
    val clues: List<ClueFragment> = emptyList(),
    val clueWordLength: Int = 0,
    val clueGrid: Int = 3,
    val messages: List<LiveMessage> = emptyList(),
    val positionIntervalS: Int = 10,
    val showStreetNames: Boolean = false,
    val startedAt: Long = 0,
)

// ---------------------------------------------------------------------------------------------
// Suivi en direct (vue hôte)
// ---------------------------------------------------------------------------------------------

@Serializable
data class Submission(
    val id: String,
    val challengeId: String,
    val prompt: String = "",
    val kind: ChallengeKind,
    val answer: String = "",
    val photo: MediaRef? = null,
    val status: ChallengeStatus,
    val at: Long = 0,
)

@Serializable
data class LiveSession(
    val sessionId: String,
    val currentIndex: Int = 0,
    val finished: Boolean = false,
    val lastPosition: LatLon? = null,
    val lastSeenAt: Long = 0,
    val startedAt: Long = 0,
    val trail: List<LatLon> = emptyList(),
    val submissions: List<Submission> = emptyList(),
    val messages: List<LiveMessage> = emptyList(),
)

object TripCode {
    /** Alphabet sans caractères ambigus (0/O, 1/I/L). */
    const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val LENGTH = 6

    fun generate(random: kotlin.random.Random = kotlin.random.Random.Default): String =
        buildString { repeat(LENGTH) { append(ALPHABET[random.nextInt(ALPHABET.length)]) } }

    fun normalize(input: String): String = input.uppercase().filter { it.isLetterOrDigit() }

    fun isValid(code: String): Boolean = code.length == LENGTH && code.all { it in ALPHABET }

    /** Extrait un code d'un lien de partage (…/join/ABC234) ou d'une saisie brute. */
    fun fromLinkOrCode(text: String): String? {
        val candidate = text.trim().substringAfterLast('/').substringAfterLast('=')
        val n = normalize(candidate)
        return n.takeIf { isValid(it) }
    }
}

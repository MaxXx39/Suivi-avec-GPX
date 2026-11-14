package app.capmystere.core.server

import app.capmystere.core.challenge.Answers
import app.capmystere.core.geo.Geo
import app.capmystere.core.model.Challenge
import app.capmystere.core.model.ChallengeKind
import app.capmystere.core.model.ChallengePlacement
import app.capmystere.core.model.ChallengeStatus
import app.capmystere.core.model.ClueFragment
import app.capmystere.core.model.LatLon
import app.capmystere.core.model.LiveMessage
import app.capmystere.core.model.LiveSession
import app.capmystere.core.model.MediaRef
import app.capmystere.core.model.PhotoValidation
import app.capmystere.core.model.Submission
import app.capmystere.core.model.Target
import app.capmystere.core.model.TravelerSnapshot
import app.capmystere.core.model.Trip
import app.capmystere.core.model.UnlockedStage
import kotlinx.serialization.Serializable

/** État d'une session de voyage, tel que le « serveur » le conserve. */
@Serializable
data class SessionState(
    val sessionId: String,
    val tripId: String,
    val currentIndex: Int = 0,
    val finished: Boolean = false,
    val startedAt: Long = 0,
    val status: Map<String, ChallengeStatus> = emptyMap(),
    val attempts: Map<String, Int> = emptyMap(),
    val clues: List<ClueFragment> = emptyList(),
    val submissions: List<Submission> = emptyList(),
    val messages: List<LiveMessage> = emptyList(),
    val trail: List<LatLon> = emptyList(),
    val lastSeenAt: Long = 0,
)

/**
 * Règles du jeu côté serveur, sous forme de fonctions pures.
 *
 * C'est la référence du comportement : le backend local l'exécute tel quel, et les fonctions SQL
 * de `supabase/schema.sql` en sont la transcription. Le principe central : [snapshot] ne projette
 * que ce qui est déjà débloqué, plus la cible du tronçon courant. Rien de la suite ne sort d'ici.
 */
object TripServer {
    /** Marge ajoutée au rayon de validation pour absorber l'imprécision du GPS. */
    const val VALIDATION_TOLERANCE_M = 60.0
    const val MAX_TRAIL = 2000

    fun salt(trip: Trip, challenge: Challenge): String = "${trip.id}:${challenge.id}"

    fun snapshot(trip: Trip, s: SessionState, now: Long): TravelerSnapshot {
        val unlockedCount = if (s.finished) trip.stages.size else s.currentIndex.coerceIn(0, trip.stages.size)
        val unlocked = trip.stages.take(unlockedCount).mapIndexed { i, st ->
            UnlockedStage(
                index = i,
                title = st.title,
                message = st.message,
                challenges = st.challenges.filter { it.placement == ChallengePlacement.AT_STAGE }.map { Answers.toTraveler(it, salt(trip, it)) },
            )
        }
        val current = if (s.finished) null else trip.stages.getOrNull(s.currentIndex)
        val elapsedMin = if (s.startedAt > 0) (now - s.startedAt) / 60_000 else 0
        val timed = trip.timedClues.filter { it.afterMinutes <= elapsedMin }.map { it.fragment }
        return TravelerSnapshot(
            tripId = trip.id,
            sessionId = s.sessionId,
            title = trip.title,
            themeId = trip.themeId,
            audience = trip.audience,
            profile = trip.profile,
            currentIndex = s.currentIndex,
            finished = s.finished,
            target = current?.let { Target(it.position, it.radiusM) },
            unlocked = unlocked,
            enRouteChallenges = current?.challenges.orEmpty()
                .filter { it.placement == ChallengePlacement.EN_ROUTE }.map { Answers.toTraveler(it, salt(trip, it)) },
            challengeStatus = s.status,
            clues = (s.clues + timed).distinctBy { it.id },
            clueWordLength = trip.clueWord.trim().length,
            clueGrid = trip.clueGrid,
            messages = s.messages,
            positionIntervalS = trip.positionIntervalS,
            showStreetNames = trip.showStreetNames,
            startedAt = s.startedAt,
        )
    }

    /**
     * Valide l'étape [index] si [position] est dans le rayon (plus tolérance).
     * Idempotent : revalider une étape déjà passée ne change rien. Renvoie `null` si refusé.
     */
    fun validateStage(trip: Trip, s: SessionState, index: Int, position: LatLon, accuracyM: Double = 0.0): SessionState? {
        if (index < s.currentIndex || s.finished) return s
        if (index != s.currentIndex) return null
        val stage = trip.stages.getOrNull(index) ?: return null
        val allowed = stage.radiusM + VALIDATION_TOLERANCE_M + accuracyM.coerceIn(0.0, 100.0)
        if (Geo.distance(position, stage.position) > allowed) return null
        return advance(trip, s)
    }

    /** L'hôte fait passer l'étape courante (voyageur bloqué, GPS capricieux…). */
    fun skipStage(trip: Trip, s: SessionState): SessionState = if (s.finished) s else advance(trip, s)

    private fun advance(trip: Trip, s: SessionState): SessionState {
        val next = s.currentIndex + 1
        return s.copy(currentIndex = next, finished = next >= trip.stages.size)
    }

    private fun findChallenge(trip: Trip, id: String): Pair<Int, Challenge>? {
        trip.stages.forEachIndexed { i, st -> st.challenges.firstOrNull { it.id == id }?.let { return i to it } }
        return null
    }

    /** Un défi n'est accessible que s'il appartient à une étape débloquée ou au tronçon courant. */
    private fun isReachable(s: SessionState, stageIndex: Int, c: Challenge): Boolean = when (c.placement) {
        ChallengePlacement.AT_STAGE -> stageIndex < s.currentIndex || s.finished
        ChallengePlacement.EN_ROUTE -> stageIndex <= s.currentIndex
    }

    fun submit(
        trip: Trip, s: SessionState, challengeId: String, answer: String, photo: MediaRef?,
        gaveUp: Boolean, now: Long, maxAttempts: Int = 3, submissionId: String = "${challengeId}-${now}",
    ): SessionState {
        val (stageIndex, c) = findChallenge(trip, challengeId) ?: return s
        if (!isReachable(s, stageIndex, c)) return s
        val previous = s.status[challengeId]
        if (previous == ChallengeStatus.SUCCESS || previous == ChallengeStatus.FAILED) return s

        val attempts = (s.attempts[challengeId] ?: 0) + 1
        val outcome: ChallengeStatus = when {
            gaveUp -> ChallengeStatus.FAILED
            c.kind == ChallengeKind.PHOTO || c.kind == ChallengeKind.MISSION ->
                if (photo == null) return s
                else if (c.photoValidation == PhotoValidation.HOST) ChallengeStatus.AWAITING_HOST else ChallengeStatus.SUCCESS
            Answers.acceptedNormalized(c).isEmpty() -> ChallengeStatus.SUCCESS // défi sans bonne réponse définie : participation = réussite
            Answers.canonical(c.kind, answer) in Answers.acceptedNormalized(c) -> ChallengeStatus.SUCCESS
            attempts >= maxAttempts -> ChallengeStatus.FAILED
            else -> ChallengeStatus.PENDING
        }
        val sub = Submission(submissionId, challengeId, c.prompt, c.kind, answer, photo, outcome, now)
        return s.copy(
            attempts = s.attempts + (challengeId to attempts),
            status = s.status + (challengeId to outcome),
            submissions = s.submissions.filterNot { it.challengeId == challengeId && it.status == ChallengeStatus.AWAITING_HOST } + sub,
            clues = s.clues + reward(c, outcome),
        )
    }

    /** L'hôte valide ou refuse une photo en attente. */
    fun review(trip: Trip, s: SessionState, submissionId: String, approved: Boolean): SessionState {
        val sub = s.submissions.firstOrNull { it.id == submissionId && it.status == ChallengeStatus.AWAITING_HOST } ?: return s
        val (_, c) = findChallenge(trip, sub.challengeId) ?: return s
        val outcome = if (approved) ChallengeStatus.SUCCESS else ChallengeStatus.FAILED
        return s.copy(
            status = s.status + (sub.challengeId to outcome),
            submissions = s.submissions.map { if (it.id == submissionId) it.copy(status = outcome) else it },
            clues = s.clues + reward(c, outcome),
        )
    }

    private fun reward(c: Challenge, outcome: ChallengeStatus): List<ClueFragment> = when (outcome) {
        ChallengeStatus.SUCCESS -> listOfNotNull(c.reward)
        ChallengeStatus.FAILED -> listOfNotNull(c.failClue)
        else -> emptyList()
    }

    fun addMessage(s: SessionState, message: LiveMessage): SessionState = s.copy(messages = s.messages + message)

    fun addPositions(s: SessionState, points: List<LatLon>, now: Long): SessionState =
        if (points.isEmpty()) s.copy(lastSeenAt = now) else s.copy(trail = (s.trail + points).takeLast(MAX_TRAIL), lastSeenAt = now)

    fun live(s: SessionState): LiveSession = LiveSession(
        sessionId = s.sessionId, currentIndex = s.currentIndex, finished = s.finished,
        lastPosition = s.trail.lastOrNull(), lastSeenAt = s.lastSeenAt, startedAt = s.startedAt,
        trail = s.trail, submissions = s.submissions, messages = s.messages,
    )
}

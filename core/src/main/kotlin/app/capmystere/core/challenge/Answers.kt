package app.capmystere.core.challenge

import app.capmystere.core.model.Challenge
import app.capmystere.core.model.ChallengeKind
import app.capmystere.core.model.TravelerChallenge
import java.security.MessageDigest
import java.text.Normalizer

object Answers {
    private val DIACRITICS = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val NON_ALNUM = Regex("[^a-z0-9]+")
    private val ARTICLES = Regex("^(le|la|les|l|un|une|des|du|de|d)\\s+")

    /** Normalisation tolérante : casse, accents, ponctuation, espaces, article initial. */
    fun normalize(input: String): String {
        val base = Normalizer.normalize(input.lowercase(), Normalizer.Form.NFD)
            .replace(DIACRITICS, "")
            .replace("œ", "oe").replace("æ", "ae")
            .replace(NON_ALNUM, " ")
            .trim()
        return base.replace(ARTICLES, "").trim().ifEmpty { base }
    }

    fun hash(salt: String, normalized: String): String =
        MessageDigest.getInstance("SHA-256").digest("$salt:$normalized".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    /** Réponses acceptées d'un défi, déjà normalisées (QCM : index de l'option). */
    fun acceptedNormalized(c: Challenge): List<String> = when (c.kind) {
        ChallengeKind.QUIZ -> listOf(c.correctOption.toString())
        ChallengeKind.QR_SCAN -> c.acceptedAnswers.map { it.trim() }.filter { it.isNotEmpty() }
        ChallengeKind.FREE_TEXT, ChallengeKind.FIND_ON_SITE, ChallengeKind.RIDDLE ->
            c.acceptedAnswers.map(::normalize).filter { it.isNotEmpty() }
        ChallengeKind.PHOTO, ChallengeKind.MISSION -> emptyList()
    }

    /** Forme canonique de la saisie du voyageur selon le type de défi. */
    fun canonical(kind: ChallengeKind, answer: String): String = when (kind) {
        ChallengeKind.QUIZ, ChallengeKind.QR_SCAN -> answer.trim()
        else -> normalize(answer)
    }

    /** Vérification locale (hors ligne) à partir des empreintes : aucune réponse en clair côté voyageur. */
    fun matches(challenge: TravelerChallenge, answer: String): Boolean {
        val h = hash(challenge.salt, canonical(challenge.kind, answer))
        return challenge.answerHashes.any { it == h }
    }

    /** Projection hôte -> voyageur : retire les réponses, ne garde que des empreintes salées. */
    fun toTraveler(c: Challenge, salt: String): TravelerChallenge = TravelerChallenge(
        id = c.id,
        kind = c.kind,
        placement = c.placement,
        prompt = c.prompt,
        options = c.options,
        photoValidation = c.photoValidation,
        hint = c.hint,
        salt = salt,
        answerHashes = acceptedNormalized(c).map { hash(salt, it) },
        hasReward = c.reward != null,
    )
}

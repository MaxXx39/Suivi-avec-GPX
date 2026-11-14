package app.capmystere.theme

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Charge tous les thèmes présents dans `assets/themes/`. */
@Singleton
class ThemeRepository @Inject constructor(@ApplicationContext private val context: Context) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    val themes: List<ThemeSpec> by lazy { load(context) }

    fun get(id: String?): ThemeSpec = themes.firstOrNull { it.id == id } ?: themes.first()

    private fun load(context: Context): List<ThemeSpec> {
        // Selon la plateforme, list() renvoie les dossiers ou des chemins complets : on ne garde que le premier segment.
        val ids = context.assets.list("themes").orEmpty().map { it.substringBefore('/') }.distinct().sorted()
        val errors = ArrayList<String>()
        val loaded = ids.mapNotNull { id ->
            runCatching {
                context.assets.open("themes/$id/theme.json").bufferedReader().use { it.readText() }
                    .let { json.decodeFromString<ThemeSpec>(it) }
            }.onFailure { errors += "$id: ${it.message}" }.getOrNull()
        }
        check(loaded.isNotEmpty()) { "Aucun thème valide dans assets/themes ($ids) : $errors" }
        // Le thème par défaut de l'application passe en premier.
        return loaded.sortedBy { if (it.id == DEFAULT_THEME) 0 else 1 }
    }

    companion object {
        const val DEFAULT_THEME = "pirates"
    }
}

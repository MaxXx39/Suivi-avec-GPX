package app.capmystere.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.store by preferencesDataStore("cap_prefs")

enum class NightMode { AUTO, NIGHT, DAY }

data class Settings(
    /** `null` = suit le réglage par défaut du public (voix activée pour les enfants). */
    val voice: Boolean? = null,
    val haptics: Boolean = true,
    val sounds: Boolean = true,
    val nightMode: NightMode = NightMode.AUTO,
    val onboarded: Boolean = false,
    /** Session voyageur en cours (pour reprendre après fermeture de l'application). */
    val activeSessionId: String? = null,
)

@Singleton
class Prefs @Inject constructor(@ApplicationContext private val context: Context) {
    private val voice = stringPreferencesKey("voice")
    private val haptics = booleanPreferencesKey("haptics")
    private val sounds = booleanPreferencesKey("sounds")
    private val night = stringPreferencesKey("night")
    private val onboarded = booleanPreferencesKey("onboarded")
    private val session = stringPreferencesKey("session")
    private val device = stringPreferencesKey("device")
    private val auth = stringPreferencesKey("auth")

    val settings: Flow<Settings> = context.store.data.map { p ->
        Settings(
            voice = p[voice]?.toBooleanStrictOrNull(),
            haptics = p[haptics] ?: true,
            sounds = p[sounds] ?: true,
            nightMode = runCatching { NightMode.valueOf(p[night] ?: "AUTO") }.getOrDefault(NightMode.AUTO),
            onboarded = p[onboarded] ?: false,
            activeSessionId = p[session]?.takeIf { it.isNotEmpty() },
        )
    }

    suspend fun setVoice(v: Boolean) = context.store.edit { it[voice] = v.toString() }
    suspend fun setHaptics(v: Boolean) = context.store.edit { it[haptics] = v }
    suspend fun setSounds(v: Boolean) = context.store.edit { it[sounds] = v }
    suspend fun setNightMode(v: NightMode) = context.store.edit { it[night] = v.name }
    suspend fun setOnboarded() = context.store.edit { it[onboarded] = true }
    suspend fun setActiveSession(id: String?) = context.store.edit { it[session] = id.orEmpty() }

    /** Identifiant stable de l'appareil (sert d'identité au backend local). */
    suspend fun deviceId(): String {
        context.store.data.first()[device]?.let { return it }
        val id = UUID.randomUUID().toString()
        context.store.edit { it[device] = id }
        return id
    }

    /** Jeton d'authentification anonyme persistant (JSON), pour le backend distant. */
    suspend fun authJson(): String? = context.store.data.first()[auth]
    suspend fun setAuthJson(json: String?) = context.store.edit { if (json == null) it.remove(auth) else it[auth] = json }
}

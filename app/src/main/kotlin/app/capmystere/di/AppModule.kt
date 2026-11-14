package app.capmystere.di

import android.content.Context
import androidx.room.Room
import app.capmystere.BuildConfig
import app.capmystere.data.MediaFiles
import app.capmystere.data.Prefs
import app.capmystere.data.backend.BackendRouter
import app.capmystere.data.backend.LocalBackend
import app.capmystere.data.backend.SupabaseBackend
import app.capmystere.data.backend.TripBackend
import app.capmystere.data.db.CacheDao
import app.capmystere.data.db.CapDatabase
import app.capmystere.data.db.OutboxDao
import app.capmystere.data.db.TripDao
import app.capmystere.data.routing.Geocoder
import app.capmystere.data.routing.PhotonGeocoder
import app.capmystere.data.routing.RoutingProvider
import app.capmystere.data.routing.ValhallaRoutingProvider
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    @Provides @Singleton
    fun database(@ApplicationContext context: Context): CapDatabase =
        Room.databaseBuilder(context, CapDatabase::class.java, "cap.db").fallbackToDestructiveMigration(true).build()

    @Provides fun tripDao(db: CapDatabase): TripDao = db.trips()
    @Provides fun cacheDao(db: CapDatabase): CacheDao = db.cache()
    @Provides fun outboxDao(db: CapDatabase): OutboxDao = db.outbox()

    @Provides @Singleton
    fun httpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS).readTimeout(25, TimeUnit.SECONDS).writeTimeout(60, TimeUnit.SECONDS)
        .build()

    @Provides @Singleton
    fun routing(client: OkHttpClient): RoutingProvider = ValhallaRoutingProvider(client, BuildConfig.VALHALLA_URL)

    @Provides @Singleton
    fun geocoder(client: OkHttpClient): Geocoder = PhotonGeocoder(client)

    /** Backend embarqué, toujours disponible (aperçu et simulation côté hôte). */
    @Provides @Singleton
    fun localBackend(trips: TripDao, cache: CacheDao): LocalBackend = LocalBackend(trips, cache)

    /** Backend actif : Supabase si configuré dans local.properties, sinon tout reste sur l'appareil. */
    @Provides @Singleton
    fun backend(local: LocalBackend, client: OkHttpClient, prefs: Prefs, media: MediaFiles): TripBackend {
        val remote = if (BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_ANON_KEY.isNotBlank())
            SupabaseBackend(client, BuildConfig.SUPABASE_URL, BuildConfig.SUPABASE_ANON_KEY, prefs, media) else null
        return BackendRouter(local, remote)
    }
}

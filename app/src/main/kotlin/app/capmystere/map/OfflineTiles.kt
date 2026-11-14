package app.capmystere.map

import android.content.Context
import app.capmystere.BuildConfig
import app.capmystere.core.geo.Geo
import app.capmystere.core.model.LatLon
import app.capmystere.theme.ThemeSpec
import dagger.hilt.android.qualifiers.ApplicationContext
import org.maplibre.android.MapLibre
import org.maplibre.android.offline.OfflineGeometryRegionDefinition
import org.maplibre.android.offline.OfflineManager
import org.maplibre.android.offline.OfflineRegion
import org.maplibre.android.offline.OfflineRegionError
import org.maplibre.android.offline.OfflineRegionStatus
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pré-téléchargement des tuiles sur un couloir autour du tronçon courant.
 * Une seule région est conservée à la fois : celle du tronçon en cours.
 */
@Singleton
class OfflineTiles @Inject constructor(@ApplicationContext private val context: Context) {
    @Volatile var lastStatus: String = "idle"
        private set

    /** À appeler depuis le thread principal. */
    fun prefetch(route: List<LatLon>, theme: ThemeSpec, night: Boolean, key: String, halfWidthM: Double = CORRIDOR_HALF_WIDTH_M) {
        if (route.size < 2) return
        runCatching {
            MapLibre.getInstance(context)
            val manager = OfflineManager.getInstance(context)
            // Le style doit être adressable par URL pour une région hors ligne : on l'écrit dans un fichier.
            val styleFile = File(context.filesDir, "offline-style-${theme.id}-${if (night) "n" else "d"}.json")
            styleFile.writeText(
                MapStyleFactory.build(if (night) theme.map.night else theme.map.day, BuildConfig.TILES_URL, MapStyleFactory.Options(forTraveler = true, streetNames = true))
            )
            val ring = Geo.corridor(route, halfWidthM).map { Point.fromLngLat(it.lon, it.lat) }
            val definition = OfflineGeometryRegionDefinition(
                "file://${styleFile.absolutePath}", Polygon.fromLngLats(listOf(ring)),
                MIN_ZOOM, MAX_ZOOM, context.resources.displayMetrics.density,
            )
            manager.listOfflineRegions(object : OfflineManager.ListOfflineRegionsCallback {
                override fun onList(regions: Array<OfflineRegion>?) {
                    // On supprime les couloirs des tronçons précédents.
                    regions?.forEach { r -> if (String(r.metadata) != key) r.delete(NoopDelete) }
                    if (regions?.any { String(it.metadata) == key } == true) return
                    manager.createOfflineRegion(definition, key.toByteArray(), object : OfflineManager.CreateOfflineRegionCallback {
                        override fun onCreate(region: OfflineRegion) {
                            region.setObserver(object : OfflineRegion.OfflineRegionObserver {
                                override fun onStatusChanged(status: OfflineRegionStatus) {
                                    lastStatus = "${status.completedResourceCount}/${status.requiredResourceCount}"
                                    if (status.isComplete) { lastStatus = "complete"; region.setDownloadState(OfflineRegion.STATE_INACTIVE) }
                                }
                                override fun onError(error: OfflineRegionError) { lastStatus = "error: ${error.message}" }
                                override fun mapboxTileCountLimitExceeded(limit: Long) { lastStatus = "limit" }
                            })
                            region.setDownloadState(OfflineRegion.STATE_ACTIVE)
                        }
                        override fun onError(error: String) { lastStatus = "error: $error" }
                    })
                }
                override fun onError(error: String) { lastStatus = "error: $error" }
            })
        }.onFailure { lastStatus = "error: ${it.message}" }
    }

    private object NoopDelete : OfflineRegion.OfflineRegionDeleteCallback {
        override fun onDelete() = Unit
        override fun onError(error: String) = Unit
    }

    companion object {
        /** Demi-largeur du couloir : 2 km de chaque côté de la route. */
        const val CORRIDOR_HALF_WIDTH_M = 2000.0
        // Les tuiles vectorielles OpenMapTiles s'arrêtent au zoom 14 (sur-zoomées au-delà).
        const val MIN_ZOOM = 11.0
        const val MAX_ZOOM = 14.0
    }
}

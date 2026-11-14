package app.capmystere.map

import android.content.Context
import android.graphics.Color
import android.graphics.PointF
import app.capmystere.core.geo.Geo
import app.capmystere.core.model.LatLon
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon

/** Couleurs des surcouches (itinéraire, étapes) issues du thème. */
data class OverlayColors(val route: Int, val routeCasing: Int, val marker: Int, val markerText: Int, val accent: Int, val dash: List<Float>)

data class StageMarker(val label: String, val position: LatLon, val radiusM: Int, val selected: Boolean)

/**
 * Enveloppe impérative autour de MapLibre : style, surcouches et caméra.
 * Toutes les données restent en attente tant que le style n'est pas chargé, puis sont rejouées.
 */
class MapController(context: Context, private val locked: Boolean) {
    val mapView: MapView
    private var map: MapLibreMap? = null
    private var style: Style? = null

    private var styleJson: String? = null
    private var colors: OverlayColors? = null
    private var route: List<LatLon> = emptyList()
    private var trail: List<LatLon> = emptyList()
    private var stages: List<StageMarker> = emptyList()
    private var traveler: LatLon? = null
    private var pendingCamera: (() -> Unit)? = null

    var onClick: ((LatLon) -> Unit)? = null
    var onStageClick: ((Int) -> Unit)? = null

    init {
        MapLibre.getInstance(context)
        mapView = MapView(context)
        mapView.onCreate(null)
        mapView.getMapAsync { m ->
            map = m
            m.uiSettings.apply {
                isLogoEnabled = false
                isCompassEnabled = false
                isAttributionEnabled = true
                if (locked) setAllGesturesEnabled(false) else { isRotateGesturesEnabled = false; isTiltGesturesEnabled = false }
            }
            if (!locked) {
                m.addOnMapClickListener { ll ->
                    val hit = m.queryRenderedFeatures(m.projection.toScreenLocation(ll).let { PointF(it.x, it.y) }, L_STAGES)
                    val idx = hit.firstOrNull()?.getNumberProperty("index")?.toInt()
                    if (idx != null) onStageClick?.invoke(idx) else onClick?.invoke(LatLon(ll.latitude, ll.longitude))
                    true
                }
            }
            applyStyle()
        }
    }

    fun setStyle(json: String, colors: OverlayColors) {
        if (json == styleJson && colors == this.colors) return
        styleJson = json
        this.colors = colors
        applyStyle()
    }

    private fun applyStyle() {
        val m = map ?: return
        val json = styleJson ?: return
        val c = colors ?: return
        style = null
        m.setStyle(Style.Builder().fromJson(json)) { s ->
            s.addSource(GeoJsonSource(S_ROUTE))
            s.addSource(GeoJsonSource(S_TRAIL))
            s.addSource(GeoJsonSource(S_RADIUS))
            s.addSource(GeoJsonSource(S_STAGES))
            s.addSource(GeoJsonSource(S_TRAVELER))
            s.addLayer(FillLayer("radius-fill", S_RADIUS).withProperties(PropertyFactory.fillColor(c.accent), PropertyFactory.fillOpacity(0.16f)))
            s.addLayer(LineLayer("radius-line", S_RADIUS).withProperties(PropertyFactory.lineColor(c.accent), PropertyFactory.lineWidth(1.5f), PropertyFactory.lineOpacity(0.8f)))
            s.addLayer(
                LineLayer("trail", S_TRAIL).withProperties(
                    PropertyFactory.lineColor(c.accent), PropertyFactory.lineWidth(3f), PropertyFactory.lineOpacity(0.7f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                )
            )
            s.addLayer(
                LineLayer("route-casing", S_ROUTE).withProperties(
                    PropertyFactory.lineColor(c.routeCasing), PropertyFactory.lineWidth(if (locked) 15f else 8f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                )
            )
            val routeLine = LineLayer("route-line", S_ROUTE).withProperties(
                PropertyFactory.lineColor(c.route), PropertyFactory.lineWidth(if (locked) 9f else 4.5f),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND), PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            )
            if (c.dash.size >= 2) routeLine.setProperties(PropertyFactory.lineDasharray(c.dash.toTypedArray()))
            s.addLayer(routeLine)
            s.addLayer(
                CircleLayer(L_STAGES, S_STAGES).withProperties(
                    PropertyFactory.circleRadius(Expression.switchCase(Expression.get("selected"), Expression.literal(17f), Expression.literal(13f))),
                    PropertyFactory.circleColor(Expression.switchCase(Expression.get("selected"), Expression.color(c.accent), Expression.color(c.marker))),
                    PropertyFactory.circleStrokeColor(c.markerText), PropertyFactory.circleStrokeWidth(2f),
                )
            )
            s.addLayer(
                SymbolLayer("stage-labels", S_STAGES).withProperties(
                    PropertyFactory.textField(Expression.get("label")), PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
                    PropertyFactory.textSize(13f), PropertyFactory.textColor(c.markerText),
                    PropertyFactory.textAllowOverlap(true), PropertyFactory.textIgnorePlacement(true),
                )
            )
            s.addLayer(
                CircleLayer("traveler", S_TRAVELER).withProperties(
                    PropertyFactory.circleRadius(9f), PropertyFactory.circleColor(c.accent),
                    PropertyFactory.circleStrokeColor(Color.WHITE), PropertyFactory.circleStrokeWidth(3f),
                )
            )
            style = s
            pushRoute(); pushTrail(); pushStages(); pushTraveler()
            pendingCamera?.invoke()
            pendingCamera = null
        }
    }

    fun setRoute(points: List<LatLon>) { route = points; pushRoute() }
    fun setTrail(points: List<LatLon>) { trail = points; pushTrail() }
    fun setStages(markers: List<StageMarker>) { stages = markers; pushStages() }
    fun setTraveler(position: LatLon?) { traveler = position; pushTraveler() }

    private fun line(points: List<LatLon>): FeatureCollection =
        if (points.size < 2) FeatureCollection.fromFeatures(emptyList())
        else FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it.lon, it.lat) })))

    private fun pushRoute() { style?.getSourceAs<GeoJsonSource>(S_ROUTE)?.setGeoJson(line(route)) }
    private fun pushTrail() { style?.getSourceAs<GeoJsonSource>(S_TRAIL)?.setGeoJson(line(trail)) }

    private fun pushStages() {
        val s = style ?: return
        s.getSourceAs<GeoJsonSource>(S_STAGES)?.setGeoJson(
            FeatureCollection.fromFeatures(stages.mapIndexed { i, st ->
                Feature.fromGeometry(Point.fromLngLat(st.position.lon, st.position.lat)).apply {
                    addStringProperty("label", st.label)
                    addNumberProperty("index", i)
                    addBooleanProperty("selected", st.selected)
                }
            })
        )
        // Rayon de validation de l'étape sélectionnée, dessiné à l'échelle.
        val sel = stages.firstOrNull { it.selected }
        s.getSourceAs<GeoJsonSource>(S_RADIUS)?.setGeoJson(
            if (sel == null) FeatureCollection.fromFeatures(emptyList())
            else {
                val ring = (0..48).map { k -> Geo.offset(sel.position, k * 7.5, sel.radiusM.toDouble()).let { Point.fromLngLat(it.lon, it.lat) } }
                FeatureCollection.fromFeature(Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))))
            }
        )
    }

    private fun pushTraveler() {
        val t = traveler
        style?.getSourceAs<GeoJsonSource>(S_TRAVELER)?.setGeoJson(
            if (t == null) FeatureCollection.fromFeatures(emptyList())
            else FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(t.lon, t.lat)))
        )
    }

    /** Caméra verrouillée : centrée sur le voyageur, zoom fixe, orientée dans le sens de la marche. */
    fun follow(position: LatLon, bearingDeg: Double, zoom: Double, tilt: Double, topPaddingPx: Double, animate: Boolean) {
        val m = map ?: return
        m.setMinZoomPreference(zoom)
        m.setMaxZoomPreference(zoom)
        val cam = CameraPosition.Builder()
            .target(LatLng(position.lat, position.lon)).zoom(zoom).bearing(bearingDeg).tilt(tilt)
            .padding(0.0, topPaddingPx, 0.0, 0.0)
            .build()
        if (animate) m.easeCamera(CameraUpdateFactory.newCameraPosition(cam), 1000, false)
        else m.moveCamera(CameraUpdateFactory.newCameraPosition(cam))
    }

    fun flyTo(position: LatLon, zoom: Double = 13.0) = whenReady {
        map?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(position.lat, position.lon), zoom), 900)
    }

    fun fit(points: List<LatLon>, paddingPx: Int) = whenReady {
        val m = map ?: return@whenReady
        when {
            points.isEmpty() -> m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(46.6, 2.5), 4.6))
            points.size == 1 -> m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(points[0].lat, points[0].lon), 13.0))
            else -> {
                val b = LatLngBounds.Builder().apply { points.forEach { include(LatLng(it.lat, it.lon)) } }.build()
                runCatching { m.moveCamera(CameraUpdateFactory.newLatLngBounds(b, paddingPx)) }
            }
        }
    }

    private fun whenReady(block: () -> Unit) {
        if (style != null) block() else pendingCamera = block
    }

    companion object {
        private const val S_ROUTE = "cap-route"
        private const val S_TRAIL = "cap-trail"
        private const val S_RADIUS = "cap-radius"
        private const val S_STAGES = "cap-stages"
        private const val S_TRAVELER = "cap-traveler"
        private const val L_STAGES = "stage-circles"
    }
}

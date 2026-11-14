package app.capmystere.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import app.capmystere.BuildConfig
import app.capmystere.core.model.LatLon
import app.capmystere.core.model.TravelProfile
import app.capmystere.theme.Cap

/** Position verticale du voyageur à l'écran (0 = haut, 1 = bas) : on voit davantage devant que derrière. */
const val PUCK_Y_FRACTION = 0.60f

fun lockedZoom(profile: TravelProfile): Double = when (profile) {
    TravelProfile.CAR -> 15.4
    TravelProfile.BIKE -> 16.2
    TravelProfile.WALK -> 17.0
}

@Composable
private fun rememberMapController(locked: Boolean): MapController {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val controller = remember { MapController(context, locked) }
    DisposableEffect(lifecycle, controller) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> controller.mapView.onStart()
                Lifecycle.Event.ON_RESUME -> controller.mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> controller.mapView.onPause()
                Lifecycle.Event.ON_STOP -> controller.mapView.onStop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) controller.mapView.onStart()
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) controller.mapView.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            controller.mapView.onPause()
            controller.mapView.onStop()
            controller.mapView.onDestroy()
        }
    }
    return controller
}

@Composable
private fun overlayColors(): OverlayColors {
    val c = Cap.colors
    val dash = Cap.spec.decor.routeDash
    return remember(c, dash) { OverlayColors(c.route.toArgb(), c.routeCasing.toArgb(), c.primary.toArgb(), c.onPrimary.toArgb(), c.secondary.toArgb(), dash) }
}

@Composable
private fun styleJson(forTraveler: Boolean, streetNames: Boolean): String {
    val spec = Cap.spec
    val night = Cap.colors.isNight
    return remember(spec, night, forTraveler, streetNames) {
        MapStyleFactory.build(if (night) spec.map.night else spec.map.day, BuildConfig.TILES_URL, MapStyleFactory.Options(forTraveler, streetNames))
    }
}

/**
 * Carte du voyageur : zoom figé, aucun geste, aucune toponymie.
 * Seule la portion d'itinéraire proche ([routeAhead]) est transmise au moteur de rendu.
 */
@Composable
fun LockedMap(
    position: LatLon?,
    bearingDeg: Double,
    routeAhead: List<LatLon>,
    profile: TravelProfile,
    streetNames: Boolean,
    modifier: Modifier = Modifier,
) {
    if (LocalInspectionMode.current) { Box(modifier); return }
    val controller = rememberMapController(locked = true)
    val json = styleJson(forTraveler = true, streetNames = streetNames)
    val colors = overlayColors()
    BoxWithConstraints(modifier) {
        val heightPx = with(LocalDensity.current) { maxHeight.toPx() }
        AndroidView(factory = { controller.mapView }, modifier = Modifier.fillMaxSize())
        LaunchedEffect(json, colors) { controller.setStyle(json, colors) }
        LaunchedEffect(routeAhead) { controller.setRoute(routeAhead) }
        val first = remember { booleanArrayOf(true) }
        LaunchedEffect(position, bearingDeg, heightPx, profile) {
            if (position != null) {
                controller.follow(
                    position, bearingDeg, lockedZoom(profile), tilt = 45.0,
                    topPaddingPx = (heightPx * (2 * PUCK_Y_FRACTION - 1)).toDouble(), animate = !first[0],
                )
                first[0] = false
            }
        }
    }
}

/** Carte de l'hôte : libre, avec toponymie, étapes, itinéraire, et position du voyageur en suivi direct. */
@Composable
fun HostMap(
    stages: List<StageMarker>,
    route: List<LatLon>,
    modifier: Modifier = Modifier,
    traveler: LatLon? = null,
    trail: List<LatLon> = emptyList(),
    focus: LatLon? = null,
    fitKey: Any? = null,
    onMapClick: (LatLon) -> Unit = {},
    onStageClick: (Int) -> Unit = {},
) {
    if (LocalInspectionMode.current) { Box(modifier); return }
    val controller = rememberMapController(locked = false)
    val json = styleJson(forTraveler = false, streetNames = false)
    val colors = overlayColors()
    val padding = with(LocalDensity.current) { 64.toDp().roundToPx() * 3 }
    controller.onClick = onMapClick
    controller.onStageClick = onStageClick
    AndroidView(factory = { controller.mapView }, modifier = modifier)
    LaunchedEffect(json, colors) { controller.setStyle(json, colors) }
    LaunchedEffect(stages) { controller.setStages(stages) }
    LaunchedEffect(route) { controller.setRoute(route) }
    LaunchedEffect(traveler) { controller.setTraveler(traveler) }
    LaunchedEffect(trail) { controller.setTrail(trail) }
    LaunchedEffect(fitKey) { controller.fit(stages.map { it.position } + listOfNotNull(traveler), padding) }
    LaunchedEffect(focus) { if (focus != null) controller.flyTo(focus) }
}

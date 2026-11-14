package app.capmystere.location

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.core.content.ContextCompat
import app.capmystere.core.guidance.Fix
import app.capmystere.core.model.LatLon
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/** Source de positions : GPS réel ou simulation. */
interface LocationSource {
    fun fixes(): Flow<Fix>
}

/** FusedLocationProvider en haute précision (1 position par seconde). */
@Singleton
class FusedLocationSource @Inject constructor(@ApplicationContext private val context: Context) : LocationSource {
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    override fun fixes(): Flow<Fix> = callbackFlow {
        if (!hasPermission()) { close(SecurityException("Localisation non autorisée")); return@callbackFlow }
        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
            .setMinUpdateIntervalMillis(700L).setWaitForAccurateLocation(false).build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (l in result.locations) {
                    trySend(
                        Fix(
                            position = LatLon(l.latitude, l.longitude),
                            accuracyM = if (l.hasAccuracy()) l.accuracy else 25f,
                            speedMps = if (l.hasSpeed()) l.speed else 0f,
                            bearingDeg = if (l.hasBearing() && l.speed > 1.5f) l.bearing else null,
                            timeMs = l.time,
                        )
                    )
                }
            }
        }
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        awaitClose { client.removeLocationUpdates(callback) }
    }

    /** Dernière position connue, pour centrer la carte de l'hôte. */
    @SuppressLint("MissingPermission")
    fun lastKnown(onResult: (LatLon?) -> Unit) {
        if (!hasPermission()) { onResult(null); return }
        LocationServices.getFusedLocationProviderClient(context).lastLocation
            .addOnSuccessListener { l -> onResult(l?.let { LatLon(it.latitude, it.longitude) }) }
            .addOnFailureListener { onResult(null) }
    }
}

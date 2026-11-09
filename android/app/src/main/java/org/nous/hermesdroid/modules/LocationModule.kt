package org.nous.hermesdroid.modules

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class LocationModule(private val context: Context) {

    @SuppressLint("MissingPermission")
    fun getCurrentLocation(): JSONObject {
        val result = JSONObject()

        val fineGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fineGranted && !coarseGranted) {
            result.put("error", "Location permission is not granted. Please open Hermes Droid -> Access -> Grant All Permissions.")
            return result
        }

        val locManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val isGpsOn = locManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val isNetworkOn = locManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

        if (!isGpsOn && !isNetworkOn) {
            result.put("error", "Location hardware is turned OFF. Please swipe down and turn on Location (GPS) in Quick Settings.")
            return result
        }

        // Try last known location first from any provider
        var bestLocation: Location? = null
        for (provider in locManager.getProviders(true)) {
            try {
                val l = locManager.getLastKnownLocation(provider) ?: continue
                if (bestLocation == null || l.time > bestLocation.time) {
                    bestLocation = l
                }
            } catch (e: Exception) {
                // ignore
            }
        }

        // Check persistent fallback in SharedPreferences
        val prefs = context.getSharedPreferences("hermes_location_cache", Context.MODE_PRIVATE)

        // Request live fix with 7-second timeout across both Network and GPS
        val latch = CountDownLatch(1)
        var freshLocation: Location? = null

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                freshLocation = location
                latch.countDown()
            }
            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }

        try {
            if (isNetworkOn) {
                locManager.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, listener, Looper.getMainLooper())
            }
            if (isGpsOn) {
                locManager.requestSingleUpdate(LocationManager.GPS_PROVIDER, listener, Looper.getMainLooper())
            }
            latch.await(7, TimeUnit.SECONDS)
            locManager.removeUpdates(listener)
        } catch (e: Exception) {
            // fallback
        }

        val finalLoc = freshLocation ?: bestLocation
        if (finalLoc != null) {
            // Cache in prefs
            prefs.edit()
                .putFloat("lat", finalLoc.latitude.toFloat())
                .putFloat("lon", finalLoc.longitude.toFloat())
                .putFloat("acc", finalLoc.accuracy)
                .putLong("time", finalLoc.time)
                .apply()

            putLocationDetails(result, finalLoc)
            result.put("source", if (freshLocation != null) "live_fix" else "last_known")
        } else if (prefs.contains("lat")) {
            result.put("latitude", prefs.getFloat("lat", 0f).toDouble())
            result.put("longitude", prefs.getFloat("lon", 0f).toDouble())
            result.put("accuracy_meters", prefs.getFloat("acc", 50f))
            result.put("timestamp", prefs.getLong("time", System.currentTimeMillis()))
            result.put("source", "persistent_cache")
        } else {
            result.put("error", "GPS is currently searching for satellites. Please open Google Maps/Balad once to initialize GPS, or ensure clear sky view.")
        }

        return result
    }

    private fun putLocationDetails(json: JSONObject, loc: Location) {
        json.put("latitude", loc.latitude)
        json.put("longitude", loc.longitude)
        json.put("altitude", loc.altitude)
        json.put("accuracy_meters", loc.accuracy)
        json.put("speed_mps", loc.speed)
        json.put("provider", loc.provider)
        json.put("timestamp", loc.time)
    }
}

package com.example.antennaaimer

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

class LocationHelper(context: Context) {

    interface LocationListener {
        fun onLocationChanged(latitude: Double, longitude: Double, altitude: Double, bearing: Float, hasBearing: Boolean)
    }

    var listener: LocationListener? = null

    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { location ->
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Location update: ${location.latitude}, ${location.longitude}, alt=${location.altitude}, bearing=${location.bearing}, hasBearing=${location.hasBearing()}")
                }
                listener?.onLocationChanged(
                    location.latitude,
                    location.longitude,
                    location.altitude,
                    location.bearing,
                    location.hasBearing()
                )
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        Log.d(TAG, "Starting location updates")

        fusedLocationClient.lastLocation.addOnSuccessListener { location ->
            if (location != null) {
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "Last known location: ${location.latitude}, ${location.longitude}")
                }
                listener?.onLocationChanged(
                    location.latitude,
                    location.longitude,
                    location.altitude,
                    location.bearing,
                    location.hasBearing()
                )
            }
        }.addOnFailureListener { e ->
            Log.e(TAG, "Failed to get last location", e)
        }

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .build()

        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
            .addOnSuccessListener {
                Log.d(TAG, "Location updates requested successfully")
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Failed to request location updates", e)
            }
    }

    fun stop() {
        fusedLocationClient.removeLocationUpdates(locationCallback)
    }

    companion object {
        private const val TAG = "AntennaAimer.Location"
    }
}

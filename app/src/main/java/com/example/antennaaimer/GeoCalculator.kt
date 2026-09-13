package com.example.antennaaimer

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object GeoCalculator {

    private const val EARTH_RADIUS_METERS = 6_371_000.0

    /**
     * Calculate the initial bearing (forward azimuth) from point 1 to point 2.
     * Returns degrees in range [0, 360).
     */
    fun calculateBearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)
        val dLonRad = Math.toRadians(lon2 - lon1)

        val y = sin(dLonRad) * cos(lat2Rad)
        val x = cos(lat1Rad) * sin(lat2Rad) - sin(lat1Rad) * cos(lat2Rad) * cos(dLonRad)
        val bearingRad = atan2(y, x)

        return (Math.toDegrees(bearingRad) + 360) % 360
    }

    /**
     * Calculate the great-circle distance between two points using the Haversine formula.
     * Returns distance in meters.
     */
    fun calculateDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val lat1Rad = Math.toRadians(lat1)
        val lat2Rad = Math.toRadians(lat2)

        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1Rad) * cos(lat2Rad) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))

        return EARTH_RADIUS_METERS * c
    }

    /**
     * Calculate the elevation angle from point 1 to point 2 based on altitude
     * difference and horizontal distance.
     * Returns degrees (positive = look up, negative = look down).
     */
    fun calculateElevationAngle(
        lat1: Double, lon1: Double, alt1: Double,
        lat2: Double, lon2: Double, alt2: Double
    ): Double {
        val horizontalDistance = calculateDistance(lat1, lon1, lat2, lon2)
        if (horizontalDistance < 0.001) return 0.0
        val altDiff = alt2 - alt1
        return Math.toDegrees(atan2(altDiff, horizontalDistance))
    }

    /**
     * Format distance for display.
     */
    fun formatDistance(meters: Double): String {
        return if (meters >= 1000) {
            String.format("%.1f km", meters / 1000)
        } else {
            String.format("%.0f m", meters)
        }
    }

    /**
     * Format bearing as compass direction.
     */
    fun bearingToCompass(bearing: Double): String {
        val directions = arrayOf("N", "NNE", "NE", "ENE", "E", "ESE", "SE", "SSE",
            "S", "SSW", "SW", "WSW", "W", "WNW", "NW", "NNW")
        val index = ((bearing + 11.25) / 22.5).toInt() % 16
        return directions[index]
    }

    /**
     * Normalize angle difference to (-180, 180].
     */
    fun angleDiff(a: Double, b: Double): Double {
        var d = a - b
        while (d > 180.0) d -= 360.0
        while (d <= -180.0) d += 360.0
        return d
    }
}

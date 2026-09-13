package com.example.antennaaimer

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

object SunPosition {

    data class SunAzimuthElevation(val azimuth: Double, val elevation: Double)

    /**
     * Calculate the sun's azimuth and elevation for a given location and time.
     * @param latitude observer latitude in degrees
     * @param longitude observer longitude in degrees
     * @return azimuth (0=N, 90=E, 180=S, 270=W) and elevation (degrees above horizon)
     */
    fun calculate(latitude: Double, longitude: Double): SunAzimuthElevation {
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        val year = cal.get(Calendar.YEAR)
        val month = cal.get(Calendar.MONTH) + 1
        val day = cal.get(Calendar.DAY_OF_MONTH)
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        val second = cal.get(Calendar.SECOND)

        val utcHours = hour + minute / 60.0 + second / 3600.0

        // Julian date
        val jd = julianDate(year, month, day, utcHours)
        val jc = (jd - 2451545.0) / 36525.0 // Julian century

        // Sun's geometric mean longitude (degrees)
        val l0 = (280.46646 + jc * (36000.76983 + 0.0003032 * jc)) % 360

        // Sun's mean anomaly (degrees)
        val m = (357.52911 + jc * (35999.05029 - 0.0001537 * jc)) % 360
        val mRad = Math.toRadians(m)

        // Equation of center
        val c = sin(mRad) * (1.914602 - jc * (0.004817 + 0.000014 * jc)) +
                sin(2 * mRad) * (0.019993 - 0.000101 * jc) +
                sin(3 * mRad) * 0.000289

        // Sun's true longitude
        val sunLon = l0 + c

        // Sun's apparent longitude
        val omega = 125.04 - 1934.136 * jc
        val lambda = sunLon - 0.00569 - 0.00478 * sin(Math.toRadians(omega))
        val lambdaRad = Math.toRadians(lambda)

        // Obliquity of ecliptic
        val obliquity = 23.439291 - jc * (0.013004167 + jc * (0.0000001639 + jc * 0.0000005036))
        val obliqCorr = obliquity + 0.00256 * cos(Math.toRadians(omega))
        val obliqRad = Math.toRadians(obliqCorr)

        // Sun's declination
        val sinDec = sin(obliqRad) * sin(lambdaRad)
        val declination = asin(sinDec)

        // Equation of time (minutes)
        val y = Math.pow(Math.tan(obliqRad / 2), 2.0)
        val l0Rad = Math.toRadians(l0)
        val eqTime = 4 * Math.toDegrees(
            y * sin(2 * l0Rad) -
            2 * 0.016709 * sin(mRad) + // eccentricity approximation
            4 * 0.016709 * y * sin(mRad) * cos(2 * l0Rad) -
            0.5 * y * y * sin(4 * l0Rad) -
            1.25 * 0.016709 * 0.016709 * sin(2 * mRad)
        )

        // Solar noon and hour angle
        val tst = (utcHours * 60 + eqTime + 4 * longitude) % 1440 // true solar time in minutes
        val hourAngle = if (tst / 4 < 0) tst / 4 + 180 else tst / 4 - 180
        val haRad = Math.toRadians(hourAngle)

        val latRad = Math.toRadians(latitude)

        // Solar elevation
        val sinElev = sin(latRad) * sin(declination) + cos(latRad) * cos(declination) * cos(haRad)
        val elevation = Math.toDegrees(asin(sinElev))

        // Solar azimuth
        val cosAz = (sin(declination) - sin(latRad) * sinElev) / (cos(latRad) * cos(Math.toRadians(elevation)))
        val azimuthBase = Math.toDegrees(acos(cosAz.coerceIn(-1.0, 1.0)))
        val azimuth = if (hourAngle > 0) (360 - azimuthBase) % 360 else azimuthBase % 360

        return SunAzimuthElevation(azimuth, elevation)
    }

    private fun julianDate(year: Int, month: Int, day: Int, utcHours: Double): Double {
        var y = year
        var m = month
        if (m <= 2) {
            y -= 1
            m += 12
        }
        val a = (y / 100)
        val b = 2 - a + (a / 4)
        return (365.25 * (y + 4716)).toInt() + (30.6001 * (m + 1)).toInt() + day + utcHours / 24.0 + b - 1524.5
    }
}

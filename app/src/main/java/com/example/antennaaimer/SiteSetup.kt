package com.example.antennaaimer

data class SiteSetup(
    val id: String,
    val label: String,
    val observerLat: Double,
    val observerLon: Double,
    val observerAltM: Double,
    val sites: List<Site>,
    val profileId: String,
    val tripodScaleAtHeading: Double? = null,
    val tripodHeadingTrue: Double? = null,
    val createdMs: Long = System.currentTimeMillis()
) {
    /** Convert a true heading to a tripod scale reading. */
    fun headingToScale(headingTrue: Double): Double? {
        val scale = tripodScaleAtHeading ?: return null
        val heading = tripodHeadingTrue ?: return null
        val diff = GeoCalculator.angleDiff(headingTrue, heading)
        return ((scale + diff) % 360.0 + 360.0) % 360.0
    }

    /** Convert a tripod scale reading to a true heading. */
    fun scaleToHeading(scaleReading: Double): Double? {
        val scale = tripodScaleAtHeading ?: return null
        val heading = tripodHeadingTrue ?: return null
        val diff = scaleReading - scale
        return ((heading + diff) % 360.0 + 360.0) % 360.0
    }

    /** Compute SiteGeometry list for the optimizer. */
    fun toGeometries(): List<BoresightOptimizer.SiteGeometry> {
        return sites.filter { it.role != SiteRole.IGNORED }.map { site ->
            BoresightOptimizer.SiteGeometry(
                label = site.label,
                bearingTrue = GeoCalculator.calculateBearing(observerLat, observerLon, site.latitude, site.longitude),
                distanceM = GeoCalculator.calculateDistance(observerLat, observerLon, site.latitude, site.longitude),
                erpW = site.erpW,
                role = site.role
            )
        }
    }
}

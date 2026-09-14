package com.example.antennaaimer

enum class SiteRole { WANTED, INTERFERER, IGNORED }

data class Site(
    val id: String,
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val groundElevationM: Double = 0.0,
    val antennaHeightM: Double = 0.0,
    val erpW: Double? = null,
    val role: SiteRole = SiteRole.IGNORED
) {
    val altitudeM: Double get() = groundElevationM + antennaHeightM
}

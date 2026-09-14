package com.example.antennaaimer

data class AntennaTarget(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val groundElevation: Double = 0.0,
    val antennaHeight: Double = 0.0
) {
    val altitude: Double get() = groundElevation + antennaHeight
}

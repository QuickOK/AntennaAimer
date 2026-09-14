package com.example.antennaaimer

data class Landmark(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val groundElevation: Double = 0.0,
    val structureHeight: Double = 0.0
) {
    val totalAltitude: Double get() = groundElevation + structureHeight
}

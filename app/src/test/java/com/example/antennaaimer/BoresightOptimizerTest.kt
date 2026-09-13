package com.example.antennaaimer

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.abs

class BoresightOptimizerTest {

    private val profile = AntennaProfile.lairdY8066()

    // OKWIN seed data from the spec
    private val observerLat = 35.6413334
    private val observerLon = -97.4329821

    // Loc 1: Edmond, Danforth & Midwest
    private val loc1Lat = 35.66833
    private val loc1Lon = -97.38778
    private val loc1Erp = 417.0

    // Loc 3: Edmond, UCO
    private val loc3Lat = 35.66056
    private val loc3Lon = -97.47000
    private val loc3Erp = 359.0

    // Loc 5: OKC, I-240 & Anderson
    private val loc5Lat = 35.39583
    private val loc5Lon = -97.31972
    private val loc5Erp = 325.0

    // --- AntennaProfile tests ---

    @Test
    fun `gainAt 0 degrees returns 0 dB`() {
        assertEquals(0.0, profile.gainAt(0.0), 0.01)
    }

    @Test
    fun `gainAt 110 degrees returns deepest null`() {
        assertEquals(-24.0, profile.gainAt(110.0), 0.01)
    }

    @Test
    fun `gainAt 180 degrees returns back lobe`() {
        assertEquals(-19.0, profile.gainAt(180.0), 0.01)
    }

    @Test
    fun `gainAt interpolates between samples`() {
        // 15° should be midpoint between 10° (-0.4) and 20° (-1.7)
        val gain = profile.gainAt(15.0)
        assertEquals(-1.05, gain, 0.01)
    }

    @Test
    fun `gainAt mirrors negative angles`() {
        assertEquals(profile.gainAt(90.0), profile.gainAt(-90.0), 0.01)
        assertEquals(profile.gainAt(45.0), profile.gainAt(-45.0), 0.01)
    }

    @Test
    fun `gainAt wraps angles beyond 180`() {
        // 270° should mirror to 90°
        assertEquals(profile.gainAt(90.0), profile.gainAt(270.0), 0.01)
    }

    // --- Geometry assertions from spec ---

    @Test
    fun `Loc 1 bearing is approximately 53_7 degrees`() {
        val bearing = GeoCalculator.calculateBearing(observerLat, observerLon, loc1Lat, loc1Lon)
        assertEquals(53.7, bearing, 0.5)
    }

    @Test
    fun `Loc 3 bearing is approximately 302_6 degrees`() {
        val bearing = GeoCalculator.calculateBearing(observerLat, observerLon, loc3Lat, loc3Lon)
        assertEquals(302.6, bearing, 0.5)
    }

    @Test
    fun `Loc 5 bearing is approximately 159_4 degrees`() {
        val bearing = GeoCalculator.calculateBearing(observerLat, observerLon, loc5Lat, loc5Lon)
        assertEquals(159.4, bearing, 0.5)
    }

    @Test
    fun `Loc 1 distance is approximately 3_15 miles`() {
        val dist = GeoCalculator.calculateDistance(observerLat, observerLon, loc1Lat, loc1Lon)
        val miles = dist / 1609.344
        assertEquals(3.15, miles, 0.1)
    }

    @Test
    fun `Loc 3 distance is approximately 2_47 miles`() {
        val dist = GeoCalculator.calculateDistance(observerLat, observerLon, loc3Lat, loc3Lon)
        val miles = dist / 1609.344
        assertEquals(2.47, miles, 0.1)
    }

    @Test
    fun `Loc 1 to Loc 3 angular separation is approximately 111 degrees`() {
        val bearing1 = GeoCalculator.calculateBearing(observerLat, observerLon, loc1Lat, loc1Lon)
        val bearing3 = GeoCalculator.calculateBearing(observerLat, observerLon, loc3Lat, loc3Lon)
        val separation = abs(BoresightOptimizer.angleDiff(bearing1, bearing3))
        assertEquals(111.1, separation, 1.0)
    }

    // --- angleDiff tests ---

    @Test
    fun `angleDiff normalizes correctly`() {
        assertEquals(10.0, BoresightOptimizer.angleDiff(20.0, 10.0), 0.01)
        assertEquals(-10.0, BoresightOptimizer.angleDiff(10.0, 20.0), 0.01)
        assertEquals(10.0, BoresightOptimizer.angleDiff(5.0, 355.0), 0.01)
        assertEquals(-10.0, BoresightOptimizer.angleDiff(355.0, 5.0), 0.01)
    }

    // --- Optimizer tests ---

    @Test
    fun `optimizer with Loc 3 wanted and Loc 1 interferer offsets from 302_6`() {
        val bearing1 = GeoCalculator.calculateBearing(observerLat, observerLon, loc1Lat, loc1Lon)
        val bearing3 = GeoCalculator.calculateBearing(observerLat, observerLon, loc3Lat, loc3Lon)
        val dist1 = GeoCalculator.calculateDistance(observerLat, observerLon, loc1Lat, loc1Lon)
        val dist3 = GeoCalculator.calculateDistance(observerLat, observerLon, loc3Lat, loc3Lon)

        val sites = listOf(
            BoresightOptimizer.SiteGeometry("Loc 3", bearing3, dist3, loc3Erp, SiteRole.WANTED),
            BoresightOptimizer.SiteGeometry("Loc 1", bearing1, dist1, loc1Erp, SiteRole.INTERFERER)
        )

        val solutions = BoresightOptimizer.optimize(sites, profile)
        assertTrue("Should return at least one solution", solutions.isNotEmpty())

        val best = solutions[0]
        // Optimal boresight should be offset from the wanted bearing (302.6°)
        assertTrue("Boresight should differ from direct bearing to wanted",
            abs(best.offsetFromWanted) > 0.5)

        // The margin should be positive (wanted beats interferer)
        assertTrue("Margin should be positive", best.marginDb > 0)
    }

    @Test
    fun `optimal is better than naive`() {
        val bearing1 = GeoCalculator.calculateBearing(observerLat, observerLon, loc1Lat, loc1Lon)
        val bearing3 = GeoCalculator.calculateBearing(observerLat, observerLon, loc3Lat, loc3Lon)
        val dist1 = GeoCalculator.calculateDistance(observerLat, observerLon, loc1Lat, loc1Lon)
        val dist3 = GeoCalculator.calculateDistance(observerLat, observerLon, loc3Lat, loc3Lon)

        val sites = listOf(
            BoresightOptimizer.SiteGeometry("Loc 3", bearing3, dist3, loc3Erp, SiteRole.WANTED),
            BoresightOptimizer.SiteGeometry("Loc 1", bearing1, dist1, loc1Erp, SiteRole.INTERFERER)
        )

        val solutions = BoresightOptimizer.optimize(sites, profile)
        val optimal = solutions[0]

        // Naive: point straight at wanted
        val naive = BoresightOptimizer.score(bearing3, sites, profile)

        assertTrue("Optimal margin should be >= naive margin",
            optimal.marginDb >= naive.marginDb)
    }

    @Test
    fun `Loc 5 is a non-factor due to distance`() {
        val bearing1 = GeoCalculator.calculateBearing(observerLat, observerLon, loc1Lat, loc1Lon)
        val bearing3 = GeoCalculator.calculateBearing(observerLat, observerLon, loc3Lat, loc3Lon)
        val bearing5 = GeoCalculator.calculateBearing(observerLat, observerLon, loc5Lat, loc5Lon)
        val dist1 = GeoCalculator.calculateDistance(observerLat, observerLon, loc1Lat, loc1Lon)
        val dist3 = GeoCalculator.calculateDistance(observerLat, observerLon, loc3Lat, loc3Lon)
        val dist5 = GeoCalculator.calculateDistance(observerLat, observerLon, loc5Lat, loc5Lon)

        // Loc 5's path term relative to Loc 3 should be about -17.7 dB
        val pt = BoresightOptimizer.pathTerm(loc5Erp, dist5, loc3Erp, dist3)
        assertTrue("Loc 5 path term should be strongly negative (>15 dB down)", pt < -15.0)
    }
}

package com.example.antennaaimer

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.max

object BoresightOptimizer {

    data class SiteGeometry(
        val label: String,
        val bearingTrue: Double,
        val distanceM: Double,
        val erpW: Double?,
        val role: SiteRole
    )

    data class InterfererDetail(
        val label: String,
        val bearingTrue: Double,
        val offAxisDeg: Double,
        val relGainDb: Double,
        val pathTermDb: Double
    )

    data class Solution(
        val boresightTrue: Double,
        val offsetFromWanted: Double,
        val wantedRelGainDb: Double,
        val interfererDetails: List<InterfererDetail>,
        val marginDb: Double
    )

    /**
     * Brute-force sweep 0-360° at 0.25° steps.
     * Returns best solution + up to 3 additional local maxima, sorted best-first.
     */
    fun optimize(sites: List<SiteGeometry>, profile: AntennaProfile): List<Solution> {
        val wanted = sites.firstOrNull { it.role == SiteRole.WANTED } ?: return emptyList()
        val interferers = sites.filter { it.role == SiteRole.INTERFERER }
        if (interferers.isEmpty()) {
            // No interferers — just point at the wanted site
            val sol = score(wanted.bearingTrue, sites, profile)
            return listOf(sol)
        }

        val stepCount = 1440 // 360 / 0.25
        val scores = DoubleArray(stepCount)
        val solutions = arrayOfNulls<Solution>(stepCount)

        for (i in 0 until stepCount) {
            val heading = i * 0.25
            val sol = score(heading, sites, profile)
            scores[i] = sol.marginDb
            solutions[i] = sol
        }

        // Find global best
        var bestIdx = 0
        for (i in 1 until stepCount) {
            if (scores[i] > scores[bestIdx]) bestIdx = i
        }

        // Find local maxima (peaks higher than both neighbors, within 6 dB of best)
        val localMaxima = mutableListOf<Int>()
        val threshold = scores[bestIdx] - 6.0
        for (i in 0 until stepCount) {
            if (i == bestIdx) continue
            val prev = if (i == 0) stepCount - 1 else i - 1
            val next = if (i == stepCount - 1) 0 else i + 1
            if (scores[i] > scores[prev] && scores[i] > scores[next] && scores[i] >= threshold) {
                // Skip if too close to an already-found peak (within 5°)
                val heading = i * 0.25
                val tooClose = localMaxima.any { abs(angleDiff(heading, it * 0.25)) < 5.0 }
                if (!tooClose) {
                    localMaxima.add(i)
                }
            }
        }

        // Sort local maxima by score descending, take top 3
        localMaxima.sortByDescending { scores[it] }
        val topAlternatives = localMaxima.take(3)

        val result = mutableListOf(solutions[bestIdx]!!)
        topAlternatives.forEach { result.add(solutions[it]!!) }
        return result
    }

    /**
     * Score a single candidate heading.
     */
    fun score(candidateHeading: Double, sites: List<SiteGeometry>, profile: AntennaProfile): Solution {
        val wanted = sites.firstOrNull { it.role == SiteRole.WANTED }
            ?: return Solution(candidateHeading, 0.0, 0.0, emptyList(), 0.0)
        val interferers = sites.filter { it.role == SiteRole.INTERFERER }

        val wantedOffAxis = angleDiff(wanted.bearingTrue, candidateHeading)
        val wantedGain = profile.gainAt(wantedOffAxis)

        val details = interferers.map { interferer ->
            val offAxis = angleDiff(interferer.bearingTrue, candidateHeading)
            val patternGain = profile.gainAt(offAxis)
            val pt = pathTerm(interferer.erpW, interferer.distanceM, wanted.erpW, wanted.distanceM)

            InterfererDetail(
                label = interferer.label,
                bearingTrue = interferer.bearingTrue,
                offAxisDeg = offAxis,
                relGainDb = patternGain + pt,
                pathTermDb = pt
            )
        }

        val worstInterferer = details.maxByOrNull { it.relGainDb }?.relGainDb ?: -999.0
        val margin = wantedGain - worstInterferer

        return Solution(
            boresightTrue = candidateHeading,
            offsetFromWanted = angleDiff(candidateHeading, wanted.bearingTrue),
            wantedRelGainDb = wantedGain,
            interfererDetails = details,
            marginDb = margin
        )
    }

    /**
     * Free-space path loss weighting: 10*log10(erp/d^2) relative to reference.
     * Returns 0.0 if either ERP is unknown.
     */
    fun pathTerm(erpW: Double?, distanceM: Double, refErpW: Double?, refDistanceM: Double): Double {
        if (erpW == null || refErpW == null || refErpW <= 0.0 || erpW <= 0.0) return 0.0
        if (distanceM <= 0.0 || refDistanceM <= 0.0) return 0.0
        // Interferer's signal relative to wanted's signal (positive = interferer is stronger)
        return 10.0 * log10((erpW * refDistanceM * refDistanceM) / (refErpW * distanceM * distanceM))
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

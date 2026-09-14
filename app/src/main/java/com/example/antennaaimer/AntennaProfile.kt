package com.example.antennaaimer

import kotlin.math.abs
import kotlin.math.floor

data class AntennaProfile(
    val id: String,
    val label: String,
    val gainDbd: Double,
    val hpbwDeg: Double,
    val azimuthPatternDb: DoubleArray // 19 entries: 0°, 10°, 20° ... 180°
) {
    init {
        require(azimuthPatternDb.size == 19) {
            "azimuthPatternDb must have exactly 19 entries (0° to 180° in 10° steps), got ${azimuthPatternDb.size}"
        }
        require(azimuthPatternDb.all { it.isFinite() }) {
            "azimuthPatternDb must contain only finite values"
        }
    }

    /**
     * Interpolated relative gain in dB for any off-axis angle.
     * Input is normalized to [0, 180] (mirrored for negative angles).
     */
    fun gainAt(offAxisDeg: Double): Double {
        val angle = abs(offAxisDeg % 360.0).let {
            if (it > 180.0) 360.0 - it else it
        }
        val index = angle / 10.0
        val lower = floor(index).toInt().coerceIn(0, azimuthPatternDb.size - 1)
        val upper = (lower + 1).coerceAtMost(azimuthPatternDb.size - 1)
        val frac = index - lower
        return azimuthPatternDb[lower] + frac * (azimuthPatternDb[upper] - azimuthPatternDb[lower])
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AntennaProfile) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    companion object {
        /**
         * Seed profile: Laird Y8066 — 6 element, 806–896 MHz, 9 dBd, ~42° HPBW.
         * These are a reasonable generic 6-element yagi shape, not measured data.
         */
        fun lairdY8066(): AntennaProfile = AntennaProfile(
            id = "laird-y8066",
            label = "Laird Y8066 (6-el, 806-896 MHz)",
            gainDbd = 9.0,
            hpbwDeg = 42.0,
            azimuthPatternDb = doubleArrayOf(
                //  0°     10°    20°    30°    40°    50°    60°    70°    80°    90°
                  0.0,  -0.4,  -1.7,  -4.2,  -8.0, -13.0, -17.0, -15.5, -16.0, -18.0,
                // 100°   110°   120°   130°   140°   150°   160°   170°   180°
                -20.0, -24.0, -22.0, -19.0, -17.5, -17.0, -17.5, -18.5, -19.0
            )
        )
    }
}

package com.example.antennaaimer

import kotlin.math.abs

object CoordinateParser {

    data class ParseResult(val degrees: Double, val isValid: Boolean, val error: String = "")

    /**
     * Parse a coordinate string in various formats:
     * - Decimal degrees: 35.6414 or -97.4329
     * - Degrees minutes: 35 38.484 or 35° 38.484'
     * - Degrees minutes seconds: 35 38 29 or 35° 38' 29" or 35° 38' 29.0"
     * - With direction suffix: 35.6414 N, 97.4329 W
     *
     * @param input the coordinate string
     * @param negativeDirection 'S' for latitude, 'W' for longitude — used when direction letter is present
     * @return parsed decimal degrees (negative for S/W)
     */
    fun parse(input: String, negativeDirection: Char): ParseResult {
        val trimmed = input.trim().uppercase()
        if (trimmed.isEmpty()) return ParseResult(0.0, false, "Empty input")

        // Check for direction letter at start or end
        var cleaned = trimmed
        var negative = false

        // Check last character for direction
        val lastChar = cleaned.last()
        if (lastChar == 'N' || lastChar == 'S' || lastChar == 'E' || lastChar == 'W') {
            negative = (lastChar == negativeDirection)
            cleaned = cleaned.dropLast(1).trim()
        }
        // Check first character for direction
        else if (cleaned.first().let { it == 'N' || it == 'S' || it == 'E' || it == 'W' }) {
            negative = (cleaned.first() == negativeDirection)
            cleaned = cleaned.drop(1).trim()
        }

        // Check for leading negative sign
        if (cleaned.startsWith("-")) {
            negative = !negative // toggle — if already negative from direction, double negative = positive
            cleaned = cleaned.drop(1).trim()
        }

        // Remove degree/minute/second symbols and replace with spaces
        cleaned = cleaned
            .replace("°", " ")
            .replace("'", " ")
            .replace("'", " ")
            .replace("\"", " ")
            .replace("″", " ")
            .replace("′", " ")
            .replace(",", " ")
            .trim()

        // Split into parts
        val parts = cleaned.split("\\s+".toRegex()).filter { it.isNotEmpty() }

        val degrees: Double = try {
            when (parts.size) {
                1 -> {
                    // Decimal degrees: 35.6414
                    parts[0].toDouble()
                }
                2 -> {
                    // Degrees decimal minutes: 35 38.484
                    val d = parts[0].toDouble()
                    val m = parts[1].toDouble()
                    d + m / 60.0
                }
                3 -> {
                    // Degrees minutes seconds: 35 38 29.0
                    val d = parts[0].toDouble()
                    val m = parts[1].toDouble()
                    val s = parts[2].toDouble()
                    d + m / 60.0 + s / 3600.0
                }
                else -> return ParseResult(0.0, false, "Too many parts")
            }
        } catch (e: NumberFormatException) {
            return ParseResult(0.0, false, "Invalid number")
        }

        val result = if (negative) -abs(degrees) else abs(degrees)
        return ParseResult(result, true)
    }

    /**
     * Format decimal degrees to a readable string with direction.
     */
    fun formatLatitude(degrees: Double): String {
        val dir = if (degrees >= 0) "N" else "S"
        val abs = abs(degrees)
        val d = abs.toInt()
        val mFull = (abs - d) * 60
        val m = mFull.toInt()
        val s = (mFull - m) * 60
        return String.format("%d° %d' %.1f\" %s", d, m, s, dir)
    }

    fun formatLongitude(degrees: Double): String {
        val dir = if (degrees >= 0) "E" else "W"
        val abs = abs(degrees)
        val d = abs.toInt()
        val mFull = (abs - d) * 60
        val m = mFull.toInt()
        val s = (mFull - m) * 60
        return String.format("%d° %d' %.1f\" %s", d, m, s, dir)
    }
}

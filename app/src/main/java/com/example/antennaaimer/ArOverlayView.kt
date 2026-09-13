package com.example.antennaaimer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class ArOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // Camera field of view (degrees) — approximate for most phone cameras
    var horizontalFov: Float = 67f
    var verticalFov: Float = 50f

    // Current device orientation
    var deviceAzimuth: Float = 0f
    var devicePitch: Float = 0f

    // Target direction
    var targetBearing: Float = 0f
    var targetElevation: Float = 0f
    var targetDistance: Double = 0.0
    var hasTarget: Boolean = false
    var hasLocation: Boolean = false
    var showCalibrationHint: Boolean = false

    // Calibration reticle (sun or landmark)
    var sunAzimuth: Float = 0f
    var sunElevation: Float = 0f
    var hasSunPosition: Boolean = false
    var calibrationLabel: String = "SUN"

    // Calibration drift tracking
    var calibrationTimeMs: Long = 0L

    // Beamwidth overlay (0 = disabled)
    var beamwidthDegrees: Float = 0f

    // Interference mode
    var interferenceMode: Boolean = false
    var optimalBoresight: Float = 0f
    var wantedSiteBearing: Float = 0f
    var wantedSiteLabel: String = ""
    var currentMarginDb: Float = 0f
    var naiveMarginDb: Float = 0f
    var interfererOverlayData: List<InterfererOverlay> = emptyList()

    var tripodScaleOffset: Double? = null

    data class InterfererOverlay(
        val label: String,
        val bearingTrue: Float,
        val offAxisDeg: Float,
        val relGainDb: Float
    )

    // Alignment threshold in degrees
    private val alignedThreshold = 3f
    private val closeThreshold = 15f

    private val crosshairPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 3f
        style = Paint.Style.STROKE
    }

    private val sunPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 3f
        style = Paint.Style.STROKE
        color = Color.rgb(255, 200, 0)
    }

    private val sunFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(255, 200, 0)
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 40f
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }

    private val hudPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 36f
        setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }

    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.YELLOW
    }

    private val compassPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 32f
        setShadowLayer(3f, 1f, 1f, Color.BLACK)
    }

    private val arrowPath = Path()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        drawCompassStrip(canvas)

        // During calibration: show center aim crosshair
        if (showCalibrationHint) {
            drawCalibrationAimPoint(canvas)
            drawCalibrationHint(canvas)
            return
        }

        if (!hasTarget || !hasLocation) {
            drawWaitingMessage(canvas)
            return
        }

        // In interference mode, aim at optimal boresight
        val aimBearing = if (interferenceMode) optimalBoresight else targetBearing

        // Calculate angular offset from device orientation to target
        var horizontalOffset = aimBearing - deviceAzimuth
        while (horizontalOffset > 180f) horizontalOffset -= 360f
        while (horizontalOffset < -180f) horizontalOffset += 360f

        val verticalOffset = targetElevation - devicePitch

        // Map angular offset to screen pixels
        val pixelsPerDegreeH = width / horizontalFov
        val pixelsPerDegreeV = height / verticalFov

        val screenX = width / 2f + horizontalOffset * pixelsPerDegreeH
        val screenY = height / 2f - verticalOffset * pixelsPerDegreeV

        val totalOffset = sqrt(
            (horizontalOffset * horizontalOffset + verticalOffset * verticalOffset).toDouble()
        ).toFloat()

        // Choose color based on alignment
        val color = when {
            totalOffset < alignedThreshold -> Color.GREEN
            totalOffset < closeThreshold -> Color.YELLOW
            else -> Color.RED
        }
        crosshairPaint.color = color

        val onScreen = screenX in 0f..width.toFloat() && screenY in 0f..height.toFloat()

        if (onScreen) {
            if (beamwidthDegrees > 0f) {
                drawBeamwidth(canvas, screenX, screenY, pixelsPerDegreeH, pixelsPerDegreeV)
            }
            val label = if (interferenceMode) "OPTIMAL" else "TARGET"
            drawCrosshair(canvas, screenX, screenY, totalOffset, label)
        } else {
            drawDirectionArrow(canvas, horizontalOffset, verticalOffset)
        }

        // Interference mode: draw interferer markers and margin
        if (interferenceMode) {
            drawInterfererMarkers(canvas, pixelsPerDegreeH)
            drawOptimalBoresightOnCompass(canvas)
            drawMarginHud(canvas)
        }

        drawHud(canvas, horizontalOffset, verticalOffset, totalOffset)
        drawDriftWarning(canvas)
    }

    private fun drawCalibrationAimPoint(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val size = 80f
        val innerSize = 25f
        val gap = 10f

        sunPaint.color = Color.rgb(255, 200, 0)
        sunPaint.strokeWidth = 3f

        // Outer circle
        canvas.drawCircle(cx, cy, size, sunPaint)

        // Cross lines with gap
        canvas.drawLine(cx - size - 15, cy, cx - innerSize, cy, sunPaint)
        canvas.drawLine(cx + innerSize, cy, cx + size + 15, cy, sunPaint)
        canvas.drawLine(cx, cy - size - 15, cx, cy - innerSize, sunPaint)
        canvas.drawLine(cx, cy + innerSize, cx, cy + size + 15, sunPaint)

        // Center dot
        sunFillPaint.color = Color.rgb(255, 200, 0)
        canvas.drawCircle(cx, cy, 5f, sunFillPaint)

        // Label above
        textPaint.color = Color.rgb(255, 200, 0)
        textPaint.textSize = 42f
        val label = "AIM HERE AT $calibrationLabel"
        canvas.drawText(label, cx - textPaint.measureText(label) / 2, cy - size - 25, textPaint)
        textPaint.textSize = 40f
        textPaint.color = Color.WHITE
    }

    private fun drawCrosshair(canvas: Canvas, x: Float, y: Float, offset: Float, label: String) {
        val size = 60f
        val innerSize = 20f

        // Outer circle
        canvas.drawCircle(x, y, size, crosshairPaint)

        // Cross lines
        canvas.drawLine(x - size - 10, y, x - innerSize, y, crosshairPaint)
        canvas.drawLine(x + innerSize, y, x + size + 10, y, crosshairPaint)
        canvas.drawLine(x, y - size - 10, x, y - innerSize, crosshairPaint)
        canvas.drawLine(x, y + innerSize, x, y + size + 10, crosshairPaint)

        // Inner dot when close to aligned
        if (offset < alignedThreshold) {
            val dotPaint = Paint(crosshairPaint).apply {
                style = Paint.Style.FILL
                color = Color.GREEN
            }
            canvas.drawCircle(x, y, 8f, dotPaint)
        }

        // Label
        textPaint.color = crosshairPaint.color
        canvas.drawText(label, x - textPaint.measureText(label) / 2, y - size - 20, textPaint)
    }

    private fun drawDirectionArrow(canvas: Canvas, hOffset: Float, vOffset: Float) {
        val margin = 80f
        val arrowSize = 40f

        val angle = Math.atan2(-vOffset.toDouble(), hOffset.toDouble()).toFloat()
        val centerX = width / 2f
        val centerY = height / 2f

        val arrowX = (centerX + cos(angle.toDouble()) * (width / 2f - margin)).toFloat()
            .coerceIn(margin, width - margin)
        val arrowY = (centerY - sin(angle.toDouble()) * (height / 2f - margin)).toFloat()
            .coerceIn(margin, height - margin)

        arrowPath.reset()
        val tipX = arrowX + cos(angle.toDouble()).toFloat() * arrowSize
        val tipY = arrowY - sin(angle.toDouble()).toFloat() * arrowSize
        val perpAngle = angle + Math.PI.toFloat() / 2f
        val baseX1 = arrowX + cos(perpAngle.toDouble()).toFloat() * arrowSize * 0.4f
        val baseY1 = arrowY - sin(perpAngle.toDouble()).toFloat() * arrowSize * 0.4f
        val baseX2 = arrowX - cos(perpAngle.toDouble()).toFloat() * arrowSize * 0.4f
        val baseY2 = arrowY + sin(perpAngle.toDouble()).toFloat() * arrowSize * 0.4f

        arrowPath.moveTo(tipX, tipY)
        arrowPath.lineTo(baseX1, baseY1)
        arrowPath.lineTo(baseX2, baseY2)
        arrowPath.close()

        canvas.drawPath(arrowPath, arrowPaint)

        val distText = GeoCalculator.formatDistance(targetDistance)
        textPaint.color = Color.YELLOW
        canvas.drawText(distText, arrowX - textPaint.measureText(distText) / 2, arrowY - arrowSize - 10, textPaint)
    }

    private fun drawHud(canvas: Canvas, hOffset: Float, vOffset: Float, totalOffset: Float) {
        val x = 30f
        var y = height - 30f

        val bearing = String.format("%.1f\u00B0 %s", targetBearing, GeoCalculator.bearingToCompass(targetBearing.toDouble()))
        val dist = GeoCalculator.formatDistance(targetDistance)
        val elev = String.format("%.1f\u00B0", targetElevation)
        val offsetText = String.format("Off: %.1f\u00B0", totalOffset)

        hudPaint.color = if (totalOffset < alignedThreshold) Color.GREEN else Color.WHITE

        val lines = listOf(
            "Bearing: $bearing",
            "Distance: $dist",
            "Elevation: $elev",
            offsetText
        )

        for (line in lines.reversed()) {
            canvas.drawText(line, x, y, hudPaint)
            y -= 44f
        }

        var rightY = height - 30f
        val tso = tripodScaleOffset
        if (tso != null) {
            val scaleReading = ((deviceAzimuth + tso) % 360.0 + 360.0) % 360.0
            hudPaint.color = Color.CYAN
            val scaleText = String.format("Scale: %.1f\u00B0", scaleReading)
            val rightX2 = width - hudPaint.measureText(scaleText) - 30f
            canvas.drawText(scaleText, rightX2, rightY, hudPaint)
            rightY -= 44f
            hudPaint.color = Color.WHITE
        }

        val headingText = String.format("Heading: %.0f\u00B0", deviceAzimuth)
        val rightX = width - hudPaint.measureText(headingText) - 30f
        canvas.drawText(headingText, rightX, rightY, hudPaint)

        val pitchText = String.format("Pitch: %.0f\u00B0", devicePitch)
        canvas.drawText(pitchText, rightX, rightY - 44f, hudPaint)

        val zoom = 67f / horizontalFov
        if (zoom > 1.05f) {
            val zoomText = String.format("Zoom: %.1fx", zoom)
            canvas.drawText(zoomText, rightX, height - 118f, hudPaint)
        }
    }

    private fun drawCompassStrip(canvas: Canvas) {
        val stripY = 50f
        val degreesVisible = horizontalFov
        val pixelsPerDegree = width / degreesVisible

        val cardinalPoints = mapOf(
            0f to "N", 45f to "NE", 90f to "E", 135f to "SE",
            180f to "S", 225f to "SW", 270f to "W", 315f to "NW"
        )

        for ((deg, label) in cardinalPoints) {
            var offset = deg - deviceAzimuth
            while (offset > 180f) offset -= 360f
            while (offset < -180f) offset += 360f

            if (abs(offset) < degreesVisible / 2f + 10f) {
                val screenX = width / 2f + offset * pixelsPerDegree
                compassPaint.color = if (label == "N") Color.RED else Color.WHITE
                canvas.drawText(label, screenX - compassPaint.measureText(label) / 2, stripY, compassPaint)
                canvas.drawLine(screenX, stripY + 5f, screenX, stripY + 20f, compassPaint)
            }
        }

        val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            strokeWidth = 2f
        }
        canvas.drawLine(width / 2f, stripY + 5f, width / 2f, stripY + 30f, tickPaint)
    }

    private fun drawWaitingMessage(canvas: Canvas) {
        val message = when {
            !hasLocation -> "Waiting for GPS fix..."
            !hasTarget -> "Enter target coordinates"
            else -> ""
        }
        textPaint.color = Color.WHITE
        textPaint.textSize = 48f
        canvas.drawText(message, width / 2f - textPaint.measureText(message) / 2, height / 2f, textPaint)
        textPaint.textSize = 40f
    }

    private fun drawCalibrationHint(canvas: Canvas) {
        val hint = "Aim at the $calibrationLabel reticle, then tap calibrate"
        textPaint.color = Color.rgb(255, 102, 0)
        textPaint.textSize = 36f
        canvas.drawText(hint, width / 2f - textPaint.measureText(hint) / 2, height - 80f, textPaint)

        // Debug info
        hudPaint.color = Color.rgb(255, 200, 0)
        val sunInfo = String.format("Sun: az=%.1f\u00B0 el=%.1f\u00B0", sunAzimuth, sunElevation)
        val devInfo = String.format("Camera: az=%.1f\u00B0 pitch=%.1f\u00B0", deviceAzimuth, devicePitch)
        canvas.drawText(sunInfo, 30f, height - 30f, hudPaint)
        canvas.drawText(devInfo, 30f, height - 70f, hudPaint)
        hudPaint.color = Color.WHITE

        textPaint.textSize = 40f
        textPaint.color = Color.WHITE
    }

    private fun drawInterfererMarkers(canvas: Canvas, pixelsPerDegreeH: Float) {
        for (interferer in interfererOverlayData) {
            var hOffset = interferer.bearingTrue - deviceAzimuth
            while (hOffset > 180f) hOffset -= 360f
            while (hOffset < -180f) hOffset += 360f

            val screenX = width / 2f + hOffset * pixelsPerDegreeH
            if (screenX < -50f || screenX > width + 50f) continue

            // Vertical center of screen (interferers at horizon level)
            val screenY = height / 2f

            // Color based on how deep in the null
            val markerColor = when {
                interferer.relGainDb < -20f -> 0xFF00CC00.toInt() // deep null — green
                interferer.relGainDb < -15f -> 0xFF88CC00.toInt() // moderate null
                interferer.relGainDb < -10f -> 0xFFCCCC00.toInt() // shallow null — yellow
                else -> 0xFFCC0000.toInt() // in main lobe — red
            }

            val markerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = markerColor
                strokeWidth = 3f
                style = Paint.Style.STROKE
            }

            // Draw diamond marker
            val sz = 30f
            val path = Path()
            path.moveTo(screenX, screenY - sz)
            path.lineTo(screenX + sz * 0.6f, screenY)
            path.lineTo(screenX, screenY + sz)
            path.lineTo(screenX - sz * 0.6f, screenY)
            path.close()
            canvas.drawPath(path, markerPaint)

            // Label and gain
            val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = markerColor
                textSize = 24f
                setShadowLayer(3f, 1f, 1f, Color.BLACK)
            }
            val gainText = String.format("%.0f dB", interferer.relGainDb)
            canvas.drawText(interferer.label, screenX - labelPaint.measureText(interferer.label) / 2, screenY - sz - 24, labelPaint)
            canvas.drawText(gainText, screenX - labelPaint.measureText(gainText) / 2, screenY - sz - 4, labelPaint)
        }
    }

    private fun drawOptimalBoresightOnCompass(canvas: Canvas) {
        val stripY = 50f
        val pixelsPerDegree = width / horizontalFov
        var offset = optimalBoresight - deviceAzimuth
        while (offset > 180f) offset -= 360f
        while (offset < -180f) offset += 360f

        if (abs(offset) < horizontalFov / 2f + 10f) {
            val screenX = width / 2f + offset * pixelsPerDegree
            val diamondPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.GREEN
                style = Paint.Style.FILL
            }
            val path = Path()
            path.moveTo(screenX, stripY + 22f)
            path.lineTo(screenX - 6f, stripY + 34f)
            path.lineTo(screenX + 6f, stripY + 34f)
            path.close()
            canvas.drawPath(path, diamondPaint)
        }
    }

    private fun drawMarginHud(canvas: Canvas) {
        val x = width / 2f
        val y = height - 30f

        val marginColor = if (currentMarginDb > 0) Color.GREEN else Color.RED
        val marginPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = marginColor
            textSize = 32f
            setShadowLayer(3f, 1f, 1f, Color.BLACK)
        }

        val marginText = String.format("Margin: %+.1f dB", currentMarginDb)
        canvas.drawText(marginText, x - marginPaint.measureText(marginText) / 2, y, marginPaint)

        val improvement = currentMarginDb - naiveMarginDb
        if (abs(improvement) > 0.5f) {
            marginPaint.textSize = 26f
            marginPaint.color = Color.argb(180, 200, 200, 200)
            val impText = String.format("vs naive: %+.1f dB", improvement)
            canvas.drawText(impText, x - marginPaint.measureText(impText) / 2, y - 36f, marginPaint)
        }
    }

    private fun drawBeamwidth(canvas: Canvas, targetX: Float, targetY: Float, ppdH: Float, ppdV: Float) {
        val beamPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2f
            color = Color.argb(80, 0, 255, 0)
        }
        val beamFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(20, 0, 255, 0)
        }
        val radiusH = (beamwidthDegrees / 2f) * ppdH
        val radiusV = (beamwidthDegrees / 2f) * ppdV

        canvas.drawOval(targetX - radiusH, targetY - radiusV, targetX + radiusH, targetY + radiusV, beamFillPaint)
        canvas.drawOval(targetX - radiusH, targetY - radiusV, targetX + radiusH, targetY + radiusV, beamPaint)

        // Label
        beamPaint.textSize = 24f
        beamPaint.color = Color.argb(150, 0, 255, 0)
        val bwLabel = String.format("-3dB: %.0f\u00B0", beamwidthDegrees)
        canvas.drawText(bwLabel, targetX - beamPaint.measureText(bwLabel) / 2, targetY + radiusV + 20, beamPaint)
    }

    private fun drawDriftWarning(canvas: Canvas) {
        if (calibrationTimeMs <= 0L) return

        val elapsedMs = android.os.SystemClock.elapsedRealtime() - calibrationTimeMs
        val elapsedMin = elapsedMs / 60000f

        // Show time since calibration
        val timeText = when {
            elapsedMin < 1f -> String.format("Cal: %ds ago", (elapsedMs / 1000).toInt())
            else -> String.format("Cal: %.0fm ago", elapsedMin)
        }

        val warnColor = when {
            elapsedMin > 10f -> Color.RED
            elapsedMin > 5f -> Color.YELLOW
            else -> Color.argb(150, 150, 150, 150)
        }

        hudPaint.color = warnColor
        val x = width - hudPaint.measureText(timeText) - 30f
        canvas.drawText(timeText, x, height - 162f, hudPaint)

        if (elapsedMin > 5f) {
            val warn = "RECALIBRATE"
            hudPaint.textSize = 28f
            canvas.drawText(warn, x, height - 196f, hudPaint)
            hudPaint.textSize = 36f
        }

        hudPaint.color = Color.WHITE
    }
}

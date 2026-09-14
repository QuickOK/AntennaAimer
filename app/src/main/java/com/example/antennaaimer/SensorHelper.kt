package com.example.antennaaimer

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.atan2
import kotlin.math.sqrt

class SensorHelper(
    private val sensorManager: SensorManager
) : SensorEventListener {

    interface OrientationListener {
        /**
         * @param azimuth compass direction the camera faces (0=N, 90=E, 180=S, 270=W)
         * @param pitch camera elevation angle (positive=looking up, negative=looking down)
         */
        fun onOrientationChanged(azimuth: Float, pitch: Float, roll: Float)
    }

    var listener: OrientationListener? = null

    private val rotationMatrix = FloatArray(9)

    // North calibration offset for game rotation vector
    private var northOffset = 0f
    var isNorthCalibrated = false
        private set

    private var rawAzimuth = 0f
    private var logCount = 0
    var headingAccuracyRad: Float = -1f
        private set

    fun start() {
        val rotVec = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotVec != null) {
            isNorthCalibrated = true
            sensorManager.registerListener(this, rotVec, SensorManager.SENSOR_DELAY_GAME)
            Log.d(TAG, "Using TYPE_ROTATION_VECTOR (true north)")
            return
        }

        val gameRotVec = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
        if (gameRotVec != null) {
            sensorManager.registerListener(this, gameRotVec, SensorManager.SENSOR_DELAY_GAME)
            Log.d(TAG, "Using TYPE_GAME_ROTATION_VECTOR (manual calibration needed)")
            return
        }

        Log.e(TAG, "No usable orientation sensors found!")
    }

    fun stop() {
        sensorManager.unregisterListener(this)
    }

    /**
     * User points phone camera toward north, taps calibrate.
     */
    fun calibrateToNorth() {
        northOffset = -rawAzimuth
        isNorthCalibrated = true
        Log.d(TAG, "Calibrated to north: rawAz=$rawAzimuth offset=$northOffset")
    }

    /**
     * User points phone camera toward a known bearing (e.g., the target), taps calibrate.
     */
    fun calibrateWithBearing(knownBearing: Float) {
        northOffset = knownBearing - rawAzimuth
        isNorthCalibrated = true
        Log.d(TAG, "Calibrated with bearing: known=$knownBearing rawAz=$rawAzimuth offset=$northOffset")
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR &&
            event.sensor.type != Sensor.TYPE_GAME_ROTATION_VECTOR) return

        // Read estimated heading accuracy when available (TYPE_ROTATION_VECTOR, values[4])
        if (event.sensor.type == Sensor.TYPE_ROTATION_VECTOR && event.values.size > 4) {
            headingAccuracyRad = event.values[4]
        }

        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)

        // The rotation matrix R maps device coordinates to world coordinates.
        // Device: x=right, y=up, z=out-of-screen
        // World: x=East, y=North, z=Up
        //
        // Camera looks along -Z in device coords (through the back of the phone).
        // Camera direction in world = R * [0, 0, -1] = [-R[2], -R[5], -R[8]]
        //
        // This works regardless of phone orientation (portrait, landscape, etc.)

        val camEast = -rotationMatrix[2]
        val camNorth = -rotationMatrix[5]
        val camUp = -rotationMatrix[8]

        // Azimuth: horizontal angle from North, clockwise
        val azimuthRad = atan2(camEast, camNorth)
        rawAzimuth = Math.toDegrees(azimuthRad.toDouble()).toFloat()

        val calibratedAzimuth = ((rawAzimuth + northOffset) % 360f + 360f) % 360f

        // Pitch: elevation angle (positive = looking up)
        val horizontalDist = sqrt(camEast * camEast + camNorth * camNorth)
        val pitchRad = atan2(camUp, horizontalDist)
        val pitch = Math.toDegrees(pitchRad.toDouble()).toFloat()

        if (logCount++ % 60 == 0) {
            Log.d(TAG, String.format("R[2]=%.3f R[5]=%.3f R[8]=%.3f camUp=%.3f hDist=%.3f pitch=%.1f az=%.1f",
                rotationMatrix[2], rotationMatrix[5], rotationMatrix[8], camUp, horizontalDist, pitch, calibratedAzimuth))
        }

        listener?.onOrientationChanged(calibratedAzimuth, pitch, 0f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        private const val TAG = "AntennaAimer.Sensor"
    }
}

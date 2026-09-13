package com.example.antennaaimer

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.SensorManager
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.button.MaterialButton

class MainActivity : AppCompatActivity(), SensorHelper.OrientationListener, LocationHelper.LocationListener {

    private lateinit var previewView: PreviewView
    private lateinit var arOverlay: ArOverlayView
    private lateinit var btnCalibrate: MaterialButton
    private lateinit var btnRecalibrate: MaterialButton
    private lateinit var btnSound: MaterialButton

    private lateinit var sensorHelper: SensorHelper
    private lateinit var locationHelper: LocationHelper
    private lateinit var alignmentTone: AlignmentTone
    private var camera: androidx.camera.core.Camera? = null

    private var currentLat = 0.0
    private var currentLon = 0.0
    private var currentAlt = 0.0
    private var target: AntennaTarget? = null
    private var calLandmarkLat = Double.NaN
    private var calLandmarkLon = Double.NaN
    private var calLandmarkAlt = 0.0
    private var hasLandmark = false

    private var calibrationTime = 0L
    private var lastToneUpdate = 0L
    private var currentOffset = Float.MAX_VALUE

    // Interference mode
    private var interferenceMode = false
    private var optimalBoresight = 0f
    private var interferenceGeometries: List<BoresightOptimizer.SiteGeometry> = emptyList()
    private var interferenceProfile: AntennaProfile? = null
    private var tripodScaleOffset: Double? = null // scaleReading - trueHeading

    private val requiredPermissions = arrayOf(
        Manifest.permission.CAMERA,
        Manifest.permission.ACCESS_FINE_LOCATION
    )

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            startCamera()
            locationHelper.start()
        } else {
            Toast.makeText(this, "Camera and Location permissions are required", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        arOverlay = findViewById(R.id.arOverlay)
        btnCalibrate = findViewById(R.id.btnCalibrate)
        btnRecalibrate = findViewById(R.id.btnRecalibrate)
        btnSound = findViewById(R.id.btnSound)

        alignmentTone = AlignmentTone()

        findViewById<MaterialButton>(R.id.btnBack).setOnClickListener {
            finish()
        }

        sensorHelper = SensorHelper(getSystemService(SENSOR_SERVICE) as SensorManager)
        sensorHelper.listener = this

        locationHelper = LocationHelper(this)
        locationHelper.listener = this

        // Read target from intent
        val lat = intent.getDoubleExtra(TargetEntryActivity.EXTRA_LATITUDE, Double.NaN)
        val lon = intent.getDoubleExtra(TargetEntryActivity.EXTRA_LONGITUDE, Double.NaN)
        val alt = intent.getDoubleExtra(TargetEntryActivity.EXTRA_ALTITUDE, 0.0)
        val name = intent.getStringExtra(TargetEntryActivity.EXTRA_NAME) ?: "Target"

        if (!lat.isNaN() && !lon.isNaN()) {
            target = AntennaTarget(name, lat, lon, alt)
        }

        // Read beamwidth from intent
        val beamwidth = intent.getFloatExtra(EXTRA_BEAMWIDTH, 0f)
        if (beamwidth > 0f) {
            arOverlay.beamwidthDegrees = beamwidth
        }

        // Interference mode
        interferenceMode = intent.getBooleanExtra(EXTRA_INTERFERENCE_MODE, false)
        if (interferenceMode) {
            optimalBoresight = intent.getFloatExtra(EXTRA_OPTIMAL_BORESIGHT, 0f)
            arOverlay.interferenceMode = true
            arOverlay.optimalBoresight = optimalBoresight

            // Parse geometries
            val geoJson = intent.getStringExtra(EXTRA_GEOMETRIES_JSON)
            if (geoJson != null) {
                val arr = org.json.JSONArray(geoJson)
                val geos = mutableListOf<BoresightOptimizer.SiteGeometry>()
                for (i in 0 until arr.length()) {
                    val obj = arr.getJSONObject(i)
                    geos.add(BoresightOptimizer.SiteGeometry(
                        label = obj.getString("label"),
                        bearingTrue = obj.getDouble("bearing"),
                        distanceM = obj.getDouble("distance"),
                        erpW = if (obj.has("erp")) obj.getDouble("erp") else null,
                        role = SiteRole.valueOf(obj.getString("role"))
                    ))
                }
                interferenceGeometries = geos

                val wanted = geos.firstOrNull { it.role == SiteRole.WANTED }
                if (wanted != null) {
                    arOverlay.wantedSiteBearing = wanted.bearingTrue.toFloat()
                    arOverlay.wantedSiteLabel = wanted.label
                }
            }

            // Parse profile
            val profileJson = intent.getStringExtra(EXTRA_PROFILE_JSON)
            if (profileJson != null) {
                val obj = org.json.JSONObject(profileJson)
                val patternArr = obj.getJSONArray("pattern")
                val pattern = DoubleArray(patternArr.length()) { patternArr.getDouble(it) }
                interferenceProfile = AntennaProfile(
                    id = obj.getString("id"),
                    label = obj.getString("label"),
                    gainDbd = obj.getDouble("gainDbd"),
                    hpbwDeg = obj.getDouble("hpbwDeg"),
                    azimuthPatternDb = pattern
                )
            }
        }

        // Read calibration landmark
        calLandmarkLat = intent.getDoubleExtra(TargetEntryActivity.EXTRA_CAL_LAT, Double.NaN)
        calLandmarkLon = intent.getDoubleExtra(TargetEntryActivity.EXTRA_CAL_LON, Double.NaN)
        calLandmarkAlt = intent.getDoubleExtra(TargetEntryActivity.EXTRA_CAL_ALT, 0.0)
        hasLandmark = !calLandmarkLat.isNaN() && !calLandmarkLon.isNaN()

        btnCalibrate.text = if (hasLandmark) "Point at LANDMARK + tap" else "Point at SUN + tap"

        btnCalibrate.setOnClickListener { performCalibration() }
        btnRecalibrate.setOnClickListener {
            arOverlay.showCalibrationHint = true
            btnCalibrate.visibility = View.VISIBLE
            btnRecalibrate.visibility = View.GONE
            arOverlay.invalidate()
        }

        // Sound toggle
        btnSound.setOnClickListener {
            alignmentTone.enabled = !alignmentTone.enabled
            btnSound.text = if (alignmentTone.enabled) "\uD83D\uDD0A" else "\uD83D\uDD07"
            Toast.makeText(this, if (alignmentTone.enabled) "Sound ON" else "Sound OFF", Toast.LENGTH_SHORT).show()
        }

        checkPermissions()
    }

    override fun onDestroy() {
        android.util.Log.e("AntennaAimer.Main", "onDestroy called", Exception("Stack trace"))
        super.onDestroy()
    }

    private fun performCalibration() {
        if (!arOverlay.hasLocation) {
            Toast.makeText(this, "Waiting for GPS fix...", Toast.LENGTH_SHORT).show()
            return
        }

        if (hasLandmark) {
            val bearing = GeoCalculator.calculateBearing(
                currentLat, currentLon, calLandmarkLat, calLandmarkLon
            ).toFloat()
            sensorHelper.calibrateWithBearing(bearing)
            val dir = GeoCalculator.bearingToCompass(bearing.toDouble())
            Toast.makeText(this,
                "Calibrated! Landmark at ${String.format("%.0f", bearing)}\u00B0 ($dir)",
                Toast.LENGTH_SHORT).show()
        } else {
            val sun = SunPosition.calculate(currentLat, currentLon)
            if (sun.elevation < -5) {
                Toast.makeText(this, "Sun below horizon. Enter a landmark to calibrate.", Toast.LENGTH_LONG).show()
                return
            }
            sensorHelper.calibrateWithBearing(sun.azimuth.toFloat())
            val dir = GeoCalculator.bearingToCompass(sun.azimuth)
            Toast.makeText(this,
                "Calibrated! Sun at ${String.format("%.0f", sun.azimuth)}\u00B0 ($dir)",
                Toast.LENGTH_SHORT).show()
        }

        calibrationTime = SystemClock.elapsedRealtime()
        arOverlay.showCalibrationHint = false
        arOverlay.calibrationTimeMs = calibrationTime
        btnCalibrate.visibility = View.GONE
        btnRecalibrate.visibility = View.VISIBLE
        arOverlay.invalidate()

        // In interference mode, offer tripod scale entry
        if (interferenceMode) {
            showTripodScaleDialog()
        }
    }

    private fun showTripodScaleDialog() {
        val input = android.widget.EditText(this).apply {
            hint = "Current tripod scale reading (degrees)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            setPadding(48, 32, 48, 16)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Tripod Scale (optional)")
            .setMessage("Enter the tripod scale reading at the current heading to show scale values in the HUD.")
            .setView(input)
            .setPositiveButton("Set") { _, _ ->
                val scaleReading = input.text.toString().toDoubleOrNull()
                if (scaleReading != null) {
                    // Current heading from sensor
                    val currentHeading = arOverlay.deviceAzimuth.toDouble()
                    tripodScaleOffset = scaleReading - currentHeading
                    arOverlay.tripodScaleOffset = tripodScaleOffset
                    Toast.makeText(this, "Tripod scale set", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Skip", null)
            .show()
    }

    private fun checkPermissions() {
        val allGranted = requiredPermissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            startCamera()
            locationHelper.start()
        } else {
            permissionLauncher.launch(requiredPermissions)
        }
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder().build().also {
                it.surfaceProvider = previewView.surfaceProvider
            }

            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

            cameraProvider.unbindAll()
            camera = cameraProvider.bindToLifecycle(this, cameraSelector, preview)

            val scaleGestureDetector = android.view.ScaleGestureDetector(this,
                object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
                    override fun onScale(detector: android.view.ScaleGestureDetector): Boolean {
                        val cam = camera ?: return false
                        val zoomState = cam.cameraInfo.zoomState.value ?: return false
                        val currentZoom = zoomState.zoomRatio
                        val newZoom = (currentZoom * detector.scaleFactor)
                            .coerceIn(zoomState.minZoomRatio, zoomState.maxZoomRatio)
                        cam.cameraControl.setZoomRatio(newZoom)

                        val baseHFov = 67f
                        val baseVFov = 50f
                        arOverlay.horizontalFov = baseHFov / newZoom
                        arOverlay.verticalFov = baseVFov / newZoom

                        return true
                    }
                })

            // Tap-to-focus + pinch-to-zoom
            val gestureDetector = android.view.GestureDetector(this,
                object : android.view.GestureDetector.SimpleOnGestureListener() {
                    override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
                        val cam = camera ?: return false
                        val factory = previewView.meteringPointFactory
                        val point = factory.createPoint(e.x, e.y)
                        val action = androidx.camera.core.FocusMeteringAction.Builder(point)
                            .setAutoCancelDuration(3, java.util.concurrent.TimeUnit.SECONDS)
                            .build()
                        cam.cameraControl.startFocusAndMetering(action)
                        return true
                    }
                })

            previewView.setOnTouchListener { _, event ->
                gestureDetector.onTouchEvent(event)
                scaleGestureDetector.onTouchEvent(event)
                true
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onResume() {
        super.onResume()
        sensorHelper.start()
        alignmentTone.start()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            locationHelper.start()
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onPause() {
        super.onPause()
        sensorHelper.stop()
        locationHelper.stop()
        alignmentTone.stop()
    }

    override fun onOrientationChanged(azimuth: Float, pitch: Float, roll: Float) {
        arOverlay.deviceAzimuth = azimuth
        arOverlay.devicePitch = pitch
        arOverlay.showCalibrationHint = !sensorHelper.isNorthCalibrated

        // Update drift warning
        if (calibrationTime > 0) {
            arOverlay.calibrationTimeMs = calibrationTime
        }

        arOverlay.invalidate()

        // Real-time interference scoring
        if (interferenceMode && sensorHelper.isNorthCalibrated) {
            val profile = interferenceProfile
            if (profile != null && interferenceGeometries.isNotEmpty()) {
                val currentSolution = BoresightOptimizer.score(azimuth.toDouble(), interferenceGeometries, profile)
                arOverlay.currentMarginDb = currentSolution.marginDb?.toFloat()
                arOverlay.interfererOverlayData = currentSolution.interfererDetails.map { d ->
                    ArOverlayView.InterfererOverlay(d.label, d.bearingTrue.toFloat(), d.offAxisDeg.toFloat(), d.relGainDb.toFloat())
                }

                // Also compute naive margin for comparison
                val wanted = interferenceGeometries.firstOrNull { it.role == SiteRole.WANTED }
                if (wanted != null) {
                    val naiveSol = BoresightOptimizer.score(wanted.bearingTrue, interferenceGeometries, profile)
                    arOverlay.naiveMarginDb = naiveSol.marginDb?.toFloat()
                }
            }
        }

        // Update audio tone (throttle to avoid overwhelming the audio buffer)
        if (sensorHelper.isNorthCalibrated && arOverlay.hasTarget) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastToneUpdate > 150) {
                lastToneUpdate = now
                // In interference mode, aim at optimal boresight; otherwise at target bearing
                val aimBearing = if (interferenceMode) optimalBoresight else arOverlay.targetBearing
                var hOffset = aimBearing - azimuth
                while (hOffset > 180f) hOffset -= 360f
                while (hOffset < -180f) hOffset += 360f
                val vOffset = arOverlay.targetElevation - pitch
                currentOffset = Math.sqrt((hOffset * hOffset + vOffset * vOffset).toDouble()).toFloat()

                alignmentTone.updateAlignment(currentOffset)
            }
        }
    }

    override fun onLocationChanged(latitude: Double, longitude: Double, altitude: Double, bearing: Float, hasBearing: Boolean) {
        currentLat = latitude
        currentLon = longitude
        currentAlt = altitude
        arOverlay.hasLocation = true

        if (!calLandmarkLat.isNaN() && !calLandmarkLon.isNaN()) {
            arOverlay.sunAzimuth = GeoCalculator.calculateBearing(
                latitude, longitude, calLandmarkLat, calLandmarkLon
            ).toFloat()
            arOverlay.sunElevation = GeoCalculator.calculateElevationAngle(
                latitude, longitude, altitude,
                calLandmarkLat, calLandmarkLon, calLandmarkAlt
            ).toFloat()
            arOverlay.hasSunPosition = true
            arOverlay.calibrationLabel = "LANDMARK"
        } else {
            val sun = SunPosition.calculate(latitude, longitude)
            arOverlay.sunAzimuth = sun.azimuth.toFloat()
            arOverlay.sunElevation = sun.elevation.toFloat()
            arOverlay.hasSunPosition = sun.elevation > -5
            arOverlay.calibrationLabel = "SUN"
        }

        arOverlay.invalidate()
        updateTargetCalculations()
    }

    private fun updateTargetCalculations() {
        val t = target ?: return

        arOverlay.targetBearing = GeoCalculator.calculateBearing(
            currentLat, currentLon, t.latitude, t.longitude
        ).toFloat()

        arOverlay.targetDistance = GeoCalculator.calculateDistance(
            currentLat, currentLon, t.latitude, t.longitude
        )

        arOverlay.targetElevation = GeoCalculator.calculateElevationAngle(
            currentLat, currentLon, currentAlt,
            t.latitude, t.longitude, t.altitude
        ).toFloat()

        arOverlay.hasTarget = true
        arOverlay.invalidate()
    }

    companion object {
        const val EXTRA_BEAMWIDTH = "beamwidth"
        const val EXTRA_INTERFERENCE_MODE = "interference_mode"
        const val EXTRA_OPTIMAL_BORESIGHT = "optimal_boresight"
        const val EXTRA_GEOMETRIES_JSON = "geometries_json"
        const val EXTRA_PROFILE_JSON = "profile_json"
    }
}

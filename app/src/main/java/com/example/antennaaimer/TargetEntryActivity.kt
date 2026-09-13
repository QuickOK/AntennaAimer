package com.example.antennaaimer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.textfield.TextInputEditText

class TargetEntryActivity : AppCompatActivity() {

    private lateinit var storage: TargetStorage
    private lateinit var adapter: TargetAdapter
    private lateinit var recyclerView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var landmarkAdapter: LandmarkAdapter
    private var userLat = Double.NaN
    private var userLon = Double.NaN

    private lateinit var editLatitude: TextInputEditText
    private lateinit var editLongitude: TextInputEditText
    private lateinit var toggleLatDirection: MaterialButtonToggleGroup
    private lateinit var toggleLonDirection: MaterialButtonToggleGroup
    private lateinit var previewText: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_target_entry)

        storage = TargetStorage(this)

        val editName = findViewById<TextInputEditText>(R.id.editName)
        editLatitude = findViewById(R.id.editLatitude)
        editLongitude = findViewById(R.id.editLongitude)
        val btnAim = findViewById<MaterialButton>(R.id.btnAim)
        recyclerView = findViewById(R.id.savedTargetsList)
        emptyView = findViewById(R.id.emptyView)
        previewText = findViewById(R.id.previewText)
        toggleLatDirection = findViewById(R.id.toggleLatDirection)
        toggleLonDirection = findViewById(R.id.toggleLonDirection)

        // Default selections: N and W (for North America)
        toggleLatDirection.check(R.id.btnNorth)
        toggleLonDirection.check(R.id.btnWest)

        adapter = TargetAdapter(
            onSelect = { target -> launchAimer(target) },
            onDelete = { target ->
                storage.deleteTarget(target.name)
                refreshList()
            },
            onEdit = { target -> showEditTargetDialog(target) }
        )
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // Landmark list
        val landmarkRecycler = findViewById<RecyclerView>(R.id.savedLandmarksList)
        landmarkAdapter = LandmarkAdapter(
            onSelect = { landmark ->
                // Fill landmark fields when selected
                findViewById<TextInputEditText>(R.id.editCalName).setText(landmark.name)
                findViewById<TextInputEditText>(R.id.editCalLat).setText(landmark.latitude.toString())
                findViewById<TextInputEditText>(R.id.editCalLon).setText(kotlin.math.abs(landmark.longitude).toString())
                findViewById<TextInputEditText>(R.id.editCalGroundElev).setText(landmark.groundElevation.toString())
                findViewById<TextInputEditText>(R.id.editCalHeight).setText(landmark.structureHeight.toString())
                if (landmark.longitude < 0) toggleLonDirection.check(R.id.btnWest)
                else toggleLonDirection.check(R.id.btnEast)
                Toast.makeText(this, "Landmark '${landmark.name}' selected", Toast.LENGTH_SHORT).show()
            },
            onDelete = { landmark ->
                storage.deleteLandmark(landmark.name)
                refreshLandmarks()
            },
            onEdit = { landmark -> showEditLandmarkDialog(landmark) }
        )
        landmarkRecycler.layoutManager = LinearLayoutManager(this)
        landmarkRecycler.adapter = landmarkAdapter

        findViewById<MaterialButton>(R.id.btnSaveLandmark).setOnClickListener {
            val calLat = findViewById<TextInputEditText>(R.id.editCalLat).text.toString()
            val calLon = findViewById<TextInputEditText>(R.id.editCalLon).text.toString()
            if (calLat.isBlank() || calLon.isBlank()) {
                Toast.makeText(this, "Enter landmark coordinates first", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val latResult = CoordinateParser.parse(calLat, 'S')
            val lonResult = CoordinateParser.parse(calLon, 'W')
            if (!latResult.isValid || !lonResult.isValid) {
                Toast.makeText(this, "Invalid landmark coordinates", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            var lat = latResult.degrees
            var lon = lonResult.degrees
            val latHasDir = calLat.trim().uppercase().let { it.endsWith("N") || it.endsWith("S") || it.startsWith("N") || it.startsWith("S") }
            val lonHasDir = calLon.trim().uppercase().let { it.endsWith("E") || it.endsWith("W") || it.startsWith("E") || it.startsWith("W") }
            if (!latHasDir) lat = kotlin.math.abs(lat) * if (toggleLatDirection.checkedButtonId == R.id.btnNorth) 1.0 else -1.0
            if (!lonHasDir) lon = kotlin.math.abs(lon) * if (toggleLonDirection.checkedButtonId == R.id.btnWest) -1.0 else 1.0

            val groundElev = findViewById<TextInputEditText>(R.id.editCalGroundElev).text.toString().toDoubleOrNull() ?: 0.0
            val height = findViewById<TextInputEditText>(R.id.editCalHeight).text.toString().toDoubleOrNull() ?: 0.0
            val nameField = findViewById<TextInputEditText>(R.id.editCalName)
            val lmName = nameField.text.toString().trim().ifEmpty { "Landmark ${storage.loadLandmarks().size + 1}" }

            storage.saveLandmark(Landmark(lmName, lat, lon, groundElev, height))
            refreshLandmarks()
            Toast.makeText(this, "Landmark '$lmName' saved", Toast.LENGTH_SHORT).show()
        }

        // Interference-aware button
        findViewById<MaterialButton>(R.id.btnInterference).setOnClickListener {
            startActivity(Intent(this, InterferenceSetupActivity::class.java))
        }

        refreshList()
        refreshLandmarks()

        // Live preview as user types
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) { updatePreview() }
        }
        editLatitude.addTextChangedListener(watcher)
        editLongitude.addTextChangedListener(watcher)
        toggleLatDirection.addOnButtonCheckedListener { _, _, _ -> updatePreview() }
        toggleLonDirection.addOnButtonCheckedListener { _, _, _ -> updatePreview() }

        // +/- sign toggle buttons
        findViewById<MaterialButton>(R.id.btnLatSign).setOnClickListener { toggleSign(editLatitude) }
        findViewById<MaterialButton>(R.id.btnLonSign).setOnClickListener { toggleSign(editLongitude) }
        findViewById<MaterialButton>(R.id.btnCalLatSign).setOnClickListener {
            toggleSign(findViewById(R.id.editCalLat))
        }
        findViewById<MaterialButton>(R.id.btnCalLonSign).setOnClickListener {
            toggleSign(findViewById(R.id.editCalLon))
        }

        btnAim.setOnClickListener {
            val name = editName.text.toString().trim().ifEmpty { "Unnamed" }
            val parsed = parseCoordinates()
            if (parsed == null) return@setOnClickListener

            val groundElev = findViewById<TextInputEditText>(R.id.editGroundElev).text.toString().toDoubleOrNull() ?: 0.0
            val antennaHeight = findViewById<TextInputEditText>(R.id.editAntennaHeight).text.toString().toDoubleOrNull() ?: 0.0
            val target = AntennaTarget(name, parsed.first, parsed.second, groundElev, antennaHeight)
            storage.saveTarget(target)
            launchAimer(target)
        }
    }

    private fun parseCoordinates(): Pair<Double, Double>? {
        val latInput = editLatitude.text.toString()
        val lonInput = editLongitude.text.toString()

        if (latInput.isBlank() || lonInput.isBlank()) {
            Toast.makeText(this, "Enter latitude and longitude", Toast.LENGTH_SHORT).show()
            return null
        }

        val isNorth = toggleLatDirection.checkedButtonId == R.id.btnNorth
        val isWest = toggleLonDirection.checkedButtonId == R.id.btnWest

        val latResult = CoordinateParser.parse(latInput, 'S')
        val lonResult = CoordinateParser.parse(lonInput, 'W')

        if (!latResult.isValid) {
            Toast.makeText(this, "Invalid latitude: ${latResult.error}", Toast.LENGTH_SHORT).show()
            return null
        }
        if (!lonResult.isValid) {
            Toast.makeText(this, "Invalid longitude: ${lonResult.error}", Toast.LENGTH_SHORT).show()
            return null
        }

        // Apply direction from toggle buttons (unless the text already had a direction letter)
        var lat = latResult.degrees
        var lon = lonResult.degrees

        // If the input didn't contain a direction letter, apply the toggle
        val latHasDir = latInput.trim().uppercase().let { it.endsWith("N") || it.endsWith("S") || it.startsWith("N") || it.startsWith("S") }
        val lonHasDir = lonInput.trim().uppercase().let { it.endsWith("E") || it.endsWith("W") || it.startsWith("E") || it.startsWith("W") }

        if (!latHasDir) {
            lat = if (isNorth) kotlin.math.abs(lat) else -kotlin.math.abs(lat)
        }
        if (!lonHasDir) {
            lon = if (isWest) -kotlin.math.abs(lon) else kotlin.math.abs(lon)
        }

        if (lat < -90 || lat > 90) {
            Toast.makeText(this, "Latitude must be between -90 and 90", Toast.LENGTH_SHORT).show()
            return null
        }
        if (lon < -180 || lon > 180) {
            Toast.makeText(this, "Longitude must be between -180 and 180", Toast.LENGTH_SHORT).show()
            return null
        }

        return Pair(lat, lon)
    }

    private fun updatePreview() {
        val latInput = editLatitude.text.toString()
        val lonInput = editLongitude.text.toString()

        if (latInput.isBlank() || lonInput.isBlank()) {
            previewText.text = ""
            return
        }

        val parsed = try { parseCoordinatesQuiet() } catch (e: Exception) { null }
        if (parsed == null) {
            previewText.text = "Invalid coordinates"
            previewText.setTextColor(0xFFFF4444.toInt())
            return
        }

        val (lat, lon) = parsed
        val normal = "${CoordinateParser.formatLatitude(lat)}, ${CoordinateParser.formatLongitude(lon)}"
        val decimalStr = String.format("Decimal: %.6f, %.6f", lat, lon)

        // Show what happens if lat/lon were swapped
        val swappedValid = lon in -90.0..90.0
        val swappedLine = if (swappedValid) {
            "If swapped: ${CoordinateParser.formatLatitude(lon)}, ${CoordinateParser.formatLongitude(lat)}"
        } else {
            "If swapped: invalid (${lon} out of latitude range)"
        }

        previewText.setTextColor(0xFF00CC00.toInt())
        previewText.text = "$normal\n$decimalStr\n$swappedLine"
    }

    private fun parseCoordinatesQuiet(): Pair<Double, Double>? {
        val latInput = editLatitude.text.toString()
        val lonInput = editLongitude.text.toString()
        if (latInput.isBlank() || lonInput.isBlank()) return null

        val isNorth = toggleLatDirection.checkedButtonId == R.id.btnNorth
        val isWest = toggleLonDirection.checkedButtonId == R.id.btnWest

        val latResult = CoordinateParser.parse(latInput, 'S')
        val lonResult = CoordinateParser.parse(lonInput, 'W')

        if (!latResult.isValid || !lonResult.isValid) return null

        var lat = latResult.degrees
        var lon = lonResult.degrees

        val latHasDir = latInput.trim().uppercase().let { it.endsWith("N") || it.endsWith("S") || it.startsWith("N") || it.startsWith("S") }
        val lonHasDir = lonInput.trim().uppercase().let { it.endsWith("E") || it.endsWith("W") || it.startsWith("E") || it.startsWith("W") }

        if (!latHasDir) {
            lat = if (isNorth) kotlin.math.abs(lat) else -kotlin.math.abs(lat)
        }
        if (!lonHasDir) {
            lon = if (isWest) -kotlin.math.abs(lon) else kotlin.math.abs(lon)
        }

        return Pair(lat, lon)
    }

    @android.annotation.SuppressLint("MissingPermission")
    override fun onResume() {
        super.onResume()
        refreshList()
        refreshLandmarks()

        // Get last known location for distance display
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this)
                .lastLocation.addOnSuccessListener { location ->
                    if (location != null) {
                        userLat = location.latitude
                        userLon = location.longitude
                        refreshList()
                    }
                }
        }
    }

    private fun toggleSign(editText: TextInputEditText) {
        val text = editText.text.toString().trim()
        if (text.isEmpty()) return
        if (text.startsWith("-")) {
            editText.setText(text.substring(1))
        } else {
            editText.setText("-$text")
        }
        editText.setSelection(editText.text?.length ?: 0)
    }

    private fun refreshLandmarks() {
        landmarkAdapter.submitList(storage.loadLandmarks())
    }

    private fun refreshList() {
        val targets = storage.loadTargets()
        adapter.userLat = userLat
        adapter.userLon = userLon
        adapter.submitList(targets)
        emptyView.visibility = if (targets.isEmpty()) View.VISIBLE else View.GONE
        recyclerView.visibility = if (targets.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun makeCoordDialogField(label: String, value: String): android.widget.LinearLayout {
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8 }
        }
        val inputLayout = com.google.android.material.textfield.TextInputLayout(this).apply {
            hint = label
            layoutParams = android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val editText = com.google.android.material.textfield.TextInputEditText(this).apply {
            setText(value)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        inputLayout.addView(editText)

        val signBtn = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "+/-"
            textSize = 11f
            minimumWidth = 0
            minWidth = 0
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = 8 }
            setPadding(16, 0, 16, 0)
        }
        signBtn.setOnClickListener { toggleSign(editText) }

        row.addView(inputLayout)
        row.addView(signBtn)
        row.tag = inputLayout
        return row
    }

    private fun getCoordDialogText(row: android.widget.LinearLayout): String {
        val layout = row.tag as com.google.android.material.textfield.TextInputLayout
        return (layout.editText?.text ?: "").toString()
    }

    private fun makeDialogField(label: String, value: String, isNumeric: Boolean = false): com.google.android.material.textfield.TextInputLayout {
        val layout = com.google.android.material.textfield.TextInputLayout(this).apply {
            hint = label
            val params = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.bottomMargin = 8
            layoutParams = params
        }
        val editText = com.google.android.material.textfield.TextInputEditText(this).apply {
            setText(value)
            if (isNumeric) {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                    android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
            }
        }
        layout.addView(editText)
        return layout
    }

    private fun getDialogFieldText(field: com.google.android.material.textfield.TextInputLayout): String {
        return (field.editText?.text ?: "").toString()
    }

    private fun showEditTargetDialog(target: AntennaTarget) {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        val nameField = makeDialogField("Name", target.name)
        val latRow = makeCoordDialogField("Latitude", target.latitude.toString())
        val lonRow = makeCoordDialogField("Longitude", target.longitude.toString())
        val elevField = makeDialogField("Ground Level (m ASL)", target.groundElevation.toString(), true)
        val heightField = makeDialogField("Mast/Antenna Height (m)", target.antennaHeight.toString(), true)
        layout.addView(nameField)
        layout.addView(latRow)
        layout.addView(lonRow)
        layout.addView(elevField)
        layout.addView(heightField)

        android.app.AlertDialog.Builder(this)
            .setTitle("Edit Target")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newName = getDialogFieldText(nameField).trim().ifEmpty { target.name }
                val lat = getCoordDialogText(latRow).toDoubleOrNull() ?: target.latitude
                val lon = getCoordDialogText(lonRow).toDoubleOrNull() ?: target.longitude
                val elev = getDialogFieldText(elevField).toDoubleOrNull() ?: target.groundElevation
                val height = getDialogFieldText(heightField).toDoubleOrNull() ?: target.antennaHeight
                storage.deleteTarget(target.name)
                storage.saveTarget(AntennaTarget(newName, lat, lon, elev, height))
                refreshList()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditLandmarkDialog(landmark: Landmark) {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        val nameField = makeDialogField("Name", landmark.name)
        val latRow = makeCoordDialogField("Latitude", landmark.latitude.toString())
        val lonRow = makeCoordDialogField("Longitude", landmark.longitude.toString())
        val elevField = makeDialogField("Ground Level (m ASL)", landmark.groundElevation.toString(), true)
        val heightField = makeDialogField("Structure Height (m)", landmark.structureHeight.toString(), true)
        layout.addView(nameField)
        layout.addView(latRow)
        layout.addView(lonRow)
        layout.addView(elevField)
        layout.addView(heightField)

        android.app.AlertDialog.Builder(this)
            .setTitle("Edit Landmark")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newName = getDialogFieldText(nameField).trim().ifEmpty { landmark.name }
                val lat = getCoordDialogText(latRow).toDoubleOrNull() ?: landmark.latitude
                val lon = getCoordDialogText(lonRow).toDoubleOrNull() ?: landmark.longitude
                val elev = getDialogFieldText(elevField).toDoubleOrNull() ?: landmark.groundElevation
                val height = getDialogFieldText(heightField).toDoubleOrNull() ?: landmark.structureHeight
                storage.deleteLandmark(landmark.name)
                storage.saveLandmark(Landmark(newName, lat, lon, elev, height))
                refreshLandmarks()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun launchAimer(target: AntennaTarget) {
        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(EXTRA_LATITUDE, target.latitude)
            putExtra(EXTRA_LONGITUDE, target.longitude)
            putExtra(EXTRA_ALTITUDE, target.altitude)
            putExtra(EXTRA_NAME, target.name)

            // Pass calibration landmark if entered
            val calLatField = findViewById<TextInputEditText>(R.id.editCalLat)
            val calLonField = findViewById<TextInputEditText>(R.id.editCalLon)
            val calLatInput = calLatField.text.toString()
            val calLonInput = calLonField.text.toString()

            if (calLatInput.isNotBlank() && calLonInput.isNotBlank()) {
                val calLat = CoordinateParser.parse(calLatInput, 'S')
                val calLon = CoordinateParser.parse(calLonInput, 'W')
                if (calLat.isValid && calLon.isValid) {
                    var lat = calLat.degrees
                    var lon = calLon.degrees

                    val latHasDir = calLatInput.trim().uppercase().let { it.endsWith("N") || it.endsWith("S") || it.startsWith("N") || it.startsWith("S") }
                    val lonHasDir = calLonInput.trim().uppercase().let { it.endsWith("E") || it.endsWith("W") || it.startsWith("E") || it.startsWith("W") }
                    if (!latHasDir) lat = kotlin.math.abs(lat) * if (toggleLatDirection.checkedButtonId == R.id.btnNorth) 1.0 else -1.0
                    if (!lonHasDir) lon = kotlin.math.abs(lon) * if (toggleLonDirection.checkedButtonId == R.id.btnWest) -1.0 else 1.0

                    val calGroundElev = findViewById<TextInputEditText>(R.id.editCalGroundElev).text.toString().toDoubleOrNull() ?: 0.0
                    val calHeight = findViewById<TextInputEditText>(R.id.editCalHeight).text.toString().toDoubleOrNull() ?: 0.0

                    putExtra(EXTRA_CAL_LAT, lat)
                    putExtra(EXTRA_CAL_LON, lon)
                    putExtra(EXTRA_CAL_ALT, calGroundElev + calHeight)
                }
            }

            // Pass beamwidth
            val beamwidth = findViewById<TextInputEditText>(R.id.editBeamwidth).text.toString().toFloatOrNull() ?: 0f
            if (beamwidth > 0f) {
                putExtra(MainActivity.EXTRA_BEAMWIDTH, beamwidth)
            }
        }
        startActivity(intent)
    }

    companion object {
        const val EXTRA_LATITUDE = "target_lat"
        const val EXTRA_LONGITUDE = "target_lon"
        const val EXTRA_ALTITUDE = "target_alt"
        const val EXTRA_NAME = "target_name"
        const val EXTRA_CAL_LAT = "cal_lat"
        const val EXTRA_CAL_LON = "cal_lon"
        const val EXTRA_CAL_ALT = "cal_alt"
    }
}

class TargetAdapter(
    private val onSelect: (AntennaTarget) -> Unit,
    private val onDelete: (AntennaTarget) -> Unit,
    private val onEdit: (AntennaTarget) -> Unit = {}
) : RecyclerView.Adapter<TargetAdapter.ViewHolder>() {

    var userLat: Double = Double.NaN
    var userLon: Double = Double.NaN
    private var targets: List<AntennaTarget> = emptyList()

    fun submitList(list: List<AntennaTarget>) {
        targets = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_target, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(targets[position])
    }

    override fun getItemCount() = targets.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameText: TextView = itemView.findViewById(R.id.targetName)
        private val coordsText: TextView = itemView.findViewById(R.id.targetCoords)
        private val deleteBtn: View = itemView.findViewById(R.id.btnDelete)

        fun bind(target: AntennaTarget) {
            nameText.text = target.name
            val coords = "${CoordinateParser.formatLatitude(target.latitude)}, ${CoordinateParser.formatLongitude(target.longitude)}"
            val elev = "${target.groundElevation.toInt()}m + ${target.antennaHeight.toInt()}m"
            val distStr = if (!userLat.isNaN() && !userLon.isNaN()) {
                val dist = GeoCalculator.calculateDistance(userLat, userLon, target.latitude, target.longitude)
                val bearing = GeoCalculator.calculateBearing(userLat, userLon, target.latitude, target.longitude)
                "  ${GeoCalculator.formatDistance(dist)} ${GeoCalculator.bearingToCompass(bearing)}"
            } else ""
            coordsText.text = "$coords  $elev$distStr"

            itemView.setOnClickListener { onSelect(target) }
            itemView.setOnLongClickListener { onEdit(target); true }
            deleteBtn.setOnClickListener { onDelete(target) }
        }
    }
}

class LandmarkAdapter(
    private val onSelect: (Landmark) -> Unit,
    private val onDelete: (Landmark) -> Unit,
    private val onEdit: (Landmark) -> Unit = {}
) : RecyclerView.Adapter<LandmarkAdapter.ViewHolder>() {

    private var landmarks: List<Landmark> = emptyList()

    fun submitList(list: List<Landmark>) {
        landmarks = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_landmark, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(landmarks[position])
    }

    override fun getItemCount() = landmarks.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val nameText: TextView = itemView.findViewById(R.id.landmarkName)
        private val detailsText: TextView = itemView.findViewById(R.id.landmarkDetails)
        private val deleteBtn: View = itemView.findViewById(R.id.btnDeleteLandmark)

        fun bind(landmark: Landmark) {
            nameText.text = landmark.name
            detailsText.text = "${CoordinateParser.formatLatitude(landmark.latitude)}, ${CoordinateParser.formatLongitude(landmark.longitude)}  ${landmark.groundElevation.toInt()}m + ${landmark.structureHeight.toInt()}m"

            itemView.setOnClickListener { onSelect(landmark) }
            itemView.setOnLongClickListener { onEdit(landmark); true }
            deleteBtn.setOnClickListener { onDelete(landmark) }
        }
    }
}

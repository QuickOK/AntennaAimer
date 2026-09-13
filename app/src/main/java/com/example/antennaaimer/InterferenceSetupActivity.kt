package com.example.antennaaimer

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class InterferenceSetupActivity : AppCompatActivity() {

    private lateinit var storage: TargetStorage
    private lateinit var siteAdapter: SiteAdapter
    private val sites = mutableListOf<Site>()
    private lateinit var profile: AntennaProfile

    private var observerLat = Double.NaN
    private var observerLon = Double.NaN
    private var observerAlt = 0.0

    private var solutions: List<BoresightOptimizer.Solution> = emptyList()
    private var naiveSolution: BoresightOptimizer.Solution? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_interference_setup)

        storage = TargetStorage(this)
        profile = storage.loadAntennaProfiles().first()

        findViewById<TextView>(R.id.profileLabel).text = profile.label

        // Site list
        siteAdapter = SiteAdapter(
            sites = sites,
            observerLat = { observerLat },
            observerLon = { observerLon },
            onRoleToggle = { position ->
                val site = sites[position]
                val newRole = when (site.role) {
                    SiteRole.IGNORED -> SiteRole.WANTED
                    SiteRole.WANTED -> SiteRole.INTERFERER
                    SiteRole.INTERFERER -> SiteRole.IGNORED
                }
                // If setting to WANTED, clear any existing WANTED
                if (newRole == SiteRole.WANTED) {
                    sites.forEachIndexed { i, s ->
                        if (s.role == SiteRole.WANTED) sites[i] = s.copy(role = SiteRole.INTERFERER)
                    }
                }
                sites[position] = site.copy(role = newRole)
                siteAdapter.notifyDataSetChanged()
            },
            onDelete = { position ->
                sites.removeAt(position)
                siteAdapter.notifyItemRemoved(position)
            },
            onEdit = { position -> showEditSiteDialog(position) }
        )
        val recycler = findViewById<RecyclerView>(R.id.sitesList)
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = siteAdapter

        // Add site button
        findViewById<MaterialButton>(R.id.btnAddSite).setOnClickListener {
            showAddSiteDialog()
        }

        // Import from saved targets
        findViewById<MaterialButton>(R.id.btnImportTargets).setOnClickListener {
            importFromSavedTargets()
        }

        // Seed OKWIN data
        findViewById<MaterialButton>(R.id.btnSeedOkwin).setOnClickListener {
            seedOkwinData()
        }

        // Compute
        findViewById<MaterialButton>(R.id.btnCompute).setOnClickListener {
            compute()
        }

        // Aim button
        findViewById<MaterialButton>(R.id.btnAimOptimal).setOnClickListener {
            if (solutions.isNotEmpty()) {
                launchAimer(solutions[0])
            }
        }

        fetchLocation()
    }

    @SuppressLint("MissingPermission")
    private fun fetchLocation() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            == PackageManager.PERMISSION_GRANTED) {
            com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(this)
                .lastLocation.addOnSuccessListener { location ->
                    if (location != null) {
                        observerLat = location.latitude
                        observerLon = location.longitude
                        observerAlt = location.altitude
                        findViewById<TextView>(R.id.observerCoords).text =
                            "${CoordinateParser.formatLatitude(observerLat)}, ${CoordinateParser.formatLongitude(observerLon)}  alt: ${observerAlt.toInt()}m"
                        siteAdapter.notifyDataSetChanged()
                    }
                }
        }
    }

    private fun showAddSiteDialog() {
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        val labelField = makeField("Label")
        val latRow = makeCoordField("Latitude")
        val lonRow = makeCoordField("Longitude")
        val elevField = makeField("Ground Elevation (m ASL)", numeric = true)
        val heightField = makeField("Antenna Height (m AGL)", numeric = true)
        val erpField = makeField("ERP (watts, optional)", numeric = true)
        layout.addView(labelField)
        layout.addView(latRow)
        layout.addView(lonRow)
        layout.addView(elevField)
        layout.addView(heightField)
        layout.addView(erpField)

        android.app.AlertDialog.Builder(this)
            .setTitle("Add Transmitter Site")
            .setView(layout)
            .setPositiveButton("Add") { _, _ ->
                val label = getText(labelField).ifEmpty { "Site ${sites.size + 1}" }
                val lat = getCoordText(latRow).toDoubleOrNull()
                val lon = getCoordText(lonRow).toDoubleOrNull()
                if (lat == null || lon == null) {
                    Toast.makeText(this, "Invalid coordinates", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                sites.add(Site(
                    id = UUID.randomUUID().toString(),
                    label = label,
                    latitude = lat,
                    longitude = lon,
                    groundElevationM = getText(elevField).toDoubleOrNull() ?: 0.0,
                    antennaHeightM = getText(heightField).toDoubleOrNull() ?: 0.0,
                    erpW = getText(erpField).toDoubleOrNull(),
                    role = if (sites.none { it.role == SiteRole.WANTED }) SiteRole.WANTED else SiteRole.INTERFERER
                ))
                siteAdapter.notifyItemInserted(sites.size - 1)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showEditSiteDialog(position: Int) {
        val site = sites[position]
        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(48, 32, 48, 16)
        }
        val labelField = makeField("Label", site.label)
        val latRow = makeCoordField("Latitude", site.latitude.toString())
        val lonRow = makeCoordField("Longitude", site.longitude.toString())
        val elevField = makeField("Ground Elevation (m)", site.groundElevationM.toString(), true)
        val heightField = makeField("Antenna Height (m)", site.antennaHeightM.toString(), true)
        val erpField = makeField("ERP (watts)", site.erpW?.toString() ?: "", true)
        layout.addView(labelField)
        layout.addView(latRow)
        layout.addView(lonRow)
        layout.addView(elevField)
        layout.addView(heightField)
        layout.addView(erpField)

        android.app.AlertDialog.Builder(this)
            .setTitle("Edit Site")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                sites[position] = site.copy(
                    label = getText(labelField).ifEmpty { site.label },
                    latitude = getCoordText(latRow).toDoubleOrNull() ?: site.latitude,
                    longitude = getCoordText(lonRow).toDoubleOrNull() ?: site.longitude,
                    groundElevationM = getText(elevField).toDoubleOrNull() ?: site.groundElevationM,
                    antennaHeightM = getText(heightField).toDoubleOrNull() ?: site.antennaHeightM,
                    erpW = getText(erpField).toDoubleOrNull()
                )
                siteAdapter.notifyItemChanged(position)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun importFromSavedTargets() {
        val targets = storage.loadTargets()
        if (targets.isEmpty()) {
            Toast.makeText(this, "No saved targets to import", Toast.LENGTH_SHORT).show()
            return
        }
        var added = 0
        for (t in targets) {
            if (sites.any { it.label == t.name }) continue
            sites.add(Site(
                id = UUID.randomUUID().toString(),
                label = t.name,
                latitude = t.latitude,
                longitude = t.longitude,
                groundElevationM = t.groundElevation,
                antennaHeightM = t.antennaHeight,
                role = SiteRole.IGNORED
            ))
            added++
        }
        siteAdapter.notifyDataSetChanged()
        Toast.makeText(this, "Imported $added targets", Toast.LENGTH_SHORT).show()
    }

    private fun seedOkwinData() {
        sites.clear()
        sites.addAll(listOf(
            Site("okwin-1", "OKWIN Loc 1 - Danforth/Midwest", 35.66833, -97.38778,
                320.7, 115.8, 417.0, SiteRole.INTERFERER),
            Site("okwin-3", "OKWIN Loc 3 - UCO/Chowning", 35.66056, -97.47000,
                366.7, 121.9, 359.0, SiteRole.WANTED),
            Site("okwin-5", "OKWIN Loc 5 - I240/Anderson", 35.39583, -97.31972,
                384.4, 73.2, 325.0, SiteRole.INTERFERER)
        ))
        siteAdapter.notifyDataSetChanged()
        Toast.makeText(this, "OKWIN seed data loaded", Toast.LENGTH_SHORT).show()
    }

    private fun compute() {
        if (observerLat.isNaN()) {
            Toast.makeText(this, "Waiting for GPS fix", Toast.LENGTH_SHORT).show()
            return
        }
        val wanted = sites.firstOrNull { it.role == SiteRole.WANTED }
        if (wanted == null) {
            Toast.makeText(this, "Mark one site as WANTED", Toast.LENGTH_SHORT).show()
            return
        }

        val setup = SiteSetup(
            id = UUID.randomUUID().toString(),
            label = "Session",
            observerLat = observerLat,
            observerLon = observerLon,
            observerAltM = observerAlt,
            sites = sites.toList(),
            profileId = profile.id
        )
        val geometries = setup.toGeometries()
        solutions = BoresightOptimizer.optimize(geometries, profile)

        // Naive score
        val wantedBearing = GeoCalculator.calculateBearing(observerLat, observerLon, wanted.latitude, wanted.longitude)
        naiveSolution = BoresightOptimizer.score(wantedBearing, geometries, profile)

        displayResults()
    }

    private fun displayResults() {
        val resultsHeader = findViewById<TextView>(R.id.resultsHeader)
        val resultsText = findViewById<TextView>(R.id.resultsText)
        val btnAim = findViewById<MaterialButton>(R.id.btnAimOptimal)

        if (solutions.isEmpty()) {
            resultsHeader.visibility = View.GONE
            resultsText.visibility = View.GONE
            btnAim.visibility = View.GONE
            return
        }

        resultsHeader.visibility = View.VISIBLE
        resultsText.visibility = View.VISIBLE
        btnAim.visibility = View.VISIBLE

        val sb = StringBuilder()
        val best = solutions[0]
        val naive = naiveSolution

        sb.appendLine("OPTIMAL HEADING: ${String.format("%.1f", best.boresightTrue)}\u00B0 ${GeoCalculator.bearingToCompass(best.boresightTrue)}")
        sb.appendLine("Offset from wanted: ${String.format("%+.1f", best.offsetFromWanted)}\u00B0")
        sb.appendLine("Wanted gain: ${String.format("%.1f", best.wantedRelGainDb)} dB")
        sb.appendLine("Margin: ${String.format("%.1f", best.marginDb)} dB")

        if (naive != null) {
            val improvement = best.marginDb - naive.marginDb
            sb.appendLine()
            sb.appendLine("NAIVE (point at wanted): ${String.format("%.1f", naive.boresightTrue)}\u00B0")
            sb.appendLine("Naive margin: ${String.format("%.1f", naive.marginDb)} dB")
            if (improvement > 1.0) {
                sb.appendLine("Improvement: +${String.format("%.1f", improvement)} dB")
            } else {
                sb.appendLine("Improvement: ${String.format("%.1f", improvement)} dB (minimal)")
            }
        }

        sb.appendLine()
        sb.appendLine("INTERFERERS:")
        for (detail in best.interfererDetails) {
            sb.appendLine("  ${detail.label}: ${String.format("%.1f", detail.relGainDb)} dB at ${String.format("%.0f", detail.offAxisDeg)}\u00B0 off-axis")
        }

        if (solutions.size > 1) {
            sb.appendLine()
            sb.appendLine("ALTERNATIVES:")
            for (i in 1 until solutions.size) {
                val alt = solutions[i]
                sb.appendLine("  ${String.format("%.1f", alt.boresightTrue)}\u00B0 margin: ${String.format("%.1f", alt.marginDb)} dB")
            }
        }

        resultsText.text = sb.toString()
    }

    private fun launchAimer(solution: BoresightOptimizer.Solution) {
        val wanted = sites.firstOrNull { it.role == SiteRole.WANTED } ?: return

        // Serialize site geometries and profile for MainActivity
        val setup = SiteSetup(
            id = UUID.randomUUID().toString(),
            label = "Session",
            observerLat = observerLat,
            observerLon = observerLon,
            observerAltM = observerAlt,
            sites = sites.toList(),
            profileId = profile.id
        )
        val geometries = setup.toGeometries()

        val geoJson = JSONArray()
        for (g in geometries) {
            geoJson.put(JSONObject().apply {
                put("label", g.label)
                put("bearing", g.bearingTrue)
                put("distance", g.distanceM)
                if (g.erpW != null) put("erp", g.erpW)
                put("role", g.role.name)
            })
        }

        val profileJson = JSONObject().apply {
            put("id", profile.id)
            put("label", profile.label)
            put("gainDbd", profile.gainDbd)
            put("hpbwDeg", profile.hpbwDeg)
            put("pattern", JSONArray(profile.azimuthPatternDb.toList()))
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            putExtra(TargetEntryActivity.EXTRA_LATITUDE, wanted.latitude)
            putExtra(TargetEntryActivity.EXTRA_LONGITUDE, wanted.longitude)
            putExtra(TargetEntryActivity.EXTRA_ALTITUDE, wanted.altitudeM)
            putExtra(TargetEntryActivity.EXTRA_NAME, wanted.label)
            putExtra(MainActivity.EXTRA_BEAMWIDTH, profile.hpbwDeg.toFloat())

            // Interference mode extras
            putExtra(MainActivity.EXTRA_INTERFERENCE_MODE, true)
            putExtra(MainActivity.EXTRA_OPTIMAL_BORESIGHT, solution.boresightTrue.toFloat())
            putExtra(MainActivity.EXTRA_GEOMETRIES_JSON, geoJson.toString())
            putExtra(MainActivity.EXTRA_PROFILE_JSON, profileJson.toString())
        }
        startActivity(intent)
    }

    // --- Helper methods for dialogs ---

    private fun makeField(hint: String, value: String = "", numeric: Boolean = false): com.google.android.material.textfield.TextInputLayout {
        val layout = com.google.android.material.textfield.TextInputLayout(this).apply {
            this.hint = hint
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8 }
        }
        val editText = com.google.android.material.textfield.TextInputEditText(this).apply {
            if (value.isNotEmpty()) setText(value)
            if (numeric) inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL or
                android.text.InputType.TYPE_NUMBER_FLAG_SIGNED
        }
        layout.addView(editText)
        return layout
    }

    private fun makeCoordField(hint: String, value: String = ""): android.widget.LinearLayout {
        val row = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = 8 }
        }
        val inputLayout = com.google.android.material.textfield.TextInputLayout(this).apply {
            this.hint = hint
            layoutParams = android.widget.LinearLayout.LayoutParams(0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val editText = com.google.android.material.textfield.TextInputEditText(this).apply {
            if (value.isNotEmpty()) setText(value)
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
        signBtn.setOnClickListener {
            val txt = editText.text.toString().trim()
            if (txt.isNotEmpty()) {
                if (txt.startsWith("-")) editText.setText(txt.substring(1))
                else editText.setText("-$txt")
                editText.setSelection(editText.text?.length ?: 0)
            }
        }

        row.addView(inputLayout)
        row.addView(signBtn)
        row.tag = inputLayout // store the TextInputLayout for getText
        return row
    }

    private fun getCoordText(row: android.widget.LinearLayout): String {
        val layout = row.tag as com.google.android.material.textfield.TextInputLayout
        return (layout.editText?.text ?: "").toString()
    }

    private fun getText(field: com.google.android.material.textfield.TextInputLayout): String {
        return (field.editText?.text ?: "").toString()
    }
}

// --- Site list adapter ---

class SiteAdapter(
    private val sites: List<Site>,
    private val observerLat: () -> Double,
    private val observerLon: () -> Double,
    private val onRoleToggle: (Int) -> Unit,
    private val onDelete: (Int) -> Unit,
    private val onEdit: (Int) -> Unit
) : RecyclerView.Adapter<SiteAdapter.ViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_site, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(sites[position], position)
    }

    override fun getItemCount() = sites.size

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val roleView: TextView = itemView.findViewById(R.id.siteRole)
        private val labelView: TextView = itemView.findViewById(R.id.siteLabel)
        private val detailsView: TextView = itemView.findViewById(R.id.siteDetails)
        private val deleteBtn: View = itemView.findViewById(R.id.btnDeleteSite)

        fun bind(site: Site, position: Int) {
            labelView.text = site.label

            // Role badge
            when (site.role) {
                SiteRole.WANTED -> {
                    roleView.text = "W"
                    roleView.setBackgroundColor(0xFF00AA00.toInt())
                }
                SiteRole.INTERFERER -> {
                    roleView.text = "I"
                    roleView.setBackgroundColor(0xFFCC0000.toInt())
                }
                SiteRole.IGNORED -> {
                    roleView.text = "-"
                    roleView.setBackgroundColor(0xFF555555.toInt())
                }
            }

            // Details
            val oLat = observerLat()
            val oLon = observerLon()
            val coords = "${CoordinateParser.formatLatitude(site.latitude)}, ${CoordinateParser.formatLongitude(site.longitude)}"
            val distStr = if (!oLat.isNaN()) {
                val dist = GeoCalculator.calculateDistance(oLat, oLon, site.latitude, site.longitude)
                val bearing = GeoCalculator.calculateBearing(oLat, oLon, site.latitude, site.longitude)
                "  ${GeoCalculator.formatDistance(dist)} ${GeoCalculator.bearingToCompass(bearing)}"
            } else ""
            val erpStr = if (site.erpW != null) "  ERP: ${site.erpW.toInt()}W" else ""
            detailsView.text = "$coords$distStr$erpStr"

            roleView.setOnClickListener { onRoleToggle(position) }
            deleteBtn.setOnClickListener { onDelete(position) }
            itemView.setOnLongClickListener { onEdit(position); true }
        }
    }
}

package com.example.antennaaimer

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class TargetStorage(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("antenna_targets", Context.MODE_PRIVATE)

    // --- Targets ---

    fun saveTarget(target: AntennaTarget) {
        val targets = loadTargets().toMutableList()
        targets.removeAll { it.name == target.name }
        targets.add(0, target)
        prefs.edit().putString(KEY_TARGETS, toTargetJson(targets).toString()).apply()
    }

    fun loadTargets(): List<AntennaTarget> {
        val jsonStr = prefs.getString(KEY_TARGETS, null) ?: return emptyList()
        val json = JSONArray(jsonStr)
        val targets = mutableListOf<AntennaTarget>()
        for (i in 0 until json.length()) {
            val obj = json.getJSONObject(i)
            // Backward compat: old entries have "altitude", new ones have "groundElevation" + "antennaHeight"
            val groundElev = obj.optDouble("groundElevation", 0.0)
            val antennaHeight = obj.optDouble("antennaHeight", 0.0)
            val legacyAlt = obj.optDouble("altitude", 0.0)
            targets.add(AntennaTarget(
                name = obj.getString("name"),
                latitude = obj.getDouble("latitude"),
                longitude = obj.getDouble("longitude"),
                groundElevation = if (groundElev != 0.0 || antennaHeight != 0.0) groundElev else legacyAlt,
                antennaHeight = antennaHeight
            ))
        }
        return targets
    }

    fun deleteTarget(name: String) {
        val targets = loadTargets().toMutableList()
        targets.removeAll { it.name == name }
        prefs.edit().putString(KEY_TARGETS, toTargetJson(targets).toString()).apply()
    }

    private fun toTargetJson(targets: List<AntennaTarget>): JSONArray {
        val json = JSONArray()
        for (t in targets) {
            json.put(JSONObject().apply {
                put("name", t.name)
                put("latitude", t.latitude)
                put("longitude", t.longitude)
                put("groundElevation", t.groundElevation)
                put("antennaHeight", t.antennaHeight)
            })
        }
        return json
    }

    // --- Landmarks ---

    fun saveLandmark(landmark: Landmark) {
        val landmarks = loadLandmarks().toMutableList()
        landmarks.removeAll { it.name == landmark.name }
        landmarks.add(0, landmark)
        prefs.edit().putString(KEY_LANDMARKS, toLandmarkJson(landmarks).toString()).apply()
    }

    fun loadLandmarks(): List<Landmark> {
        val jsonStr = prefs.getString(KEY_LANDMARKS, null) ?: return emptyList()
        val json = JSONArray(jsonStr)
        val landmarks = mutableListOf<Landmark>()
        for (i in 0 until json.length()) {
            val obj = json.getJSONObject(i)
            landmarks.add(Landmark(
                name = obj.getString("name"),
                latitude = obj.getDouble("latitude"),
                longitude = obj.getDouble("longitude"),
                groundElevation = obj.optDouble("groundElevation", 0.0),
                structureHeight = obj.optDouble("structureHeight", 0.0)
            ))
        }
        return landmarks
    }

    fun deleteLandmark(name: String) {
        val landmarks = loadLandmarks().toMutableList()
        landmarks.removeAll { it.name == name }
        prefs.edit().putString(KEY_LANDMARKS, toLandmarkJson(landmarks).toString()).apply()
    }

    private fun toLandmarkJson(landmarks: List<Landmark>): JSONArray {
        val json = JSONArray()
        for (l in landmarks) {
            json.put(JSONObject().apply {
                put("name", l.name)
                put("latitude", l.latitude)
                put("longitude", l.longitude)
                put("groundElevation", l.groundElevation)
                put("structureHeight", l.structureHeight)
            })
        }
        return json
    }

    // --- Antenna Profiles ---

    fun loadAntennaProfiles(): List<AntennaProfile> {
        val jsonStr = prefs.getString(KEY_PROFILES, null)
        if (jsonStr == null) {
            // Seed with default profile on first access — write directly to avoid recursion
            val seed = AntennaProfile.lairdY8066()
            val json = JSONArray()
            json.put(JSONObject().apply {
                put("id", seed.id)
                put("label", seed.label)
                put("gainDbd", seed.gainDbd)
                put("hpbwDeg", seed.hpbwDeg)
                put("pattern", JSONArray(seed.azimuthPatternDb.toList()))
            })
            prefs.edit().putString(KEY_PROFILES, json.toString()).apply()
            return listOf(seed)
        }
        val json = JSONArray(jsonStr)
        val profiles = mutableListOf<AntennaProfile>()
        for (i in 0 until json.length()) {
            val obj = json.getJSONObject(i)
            val patternArr = obj.getJSONArray("pattern")
            val pattern = DoubleArray(patternArr.length()) { patternArr.getDouble(it) }
            profiles.add(AntennaProfile(
                id = obj.getString("id"),
                label = obj.getString("label"),
                gainDbd = obj.getDouble("gainDbd"),
                hpbwDeg = obj.getDouble("hpbwDeg"),
                azimuthPatternDb = pattern
            ))
        }
        return profiles
    }

    fun saveAntennaProfile(profile: AntennaProfile) {
        val profiles = loadAntennaProfiles().toMutableList()
        profiles.removeAll { it.id == profile.id }
        profiles.add(0, profile)
        val json = JSONArray()
        for (p in profiles) {
            json.put(JSONObject().apply {
                put("id", p.id)
                put("label", p.label)
                put("gainDbd", p.gainDbd)
                put("hpbwDeg", p.hpbwDeg)
                put("pattern", JSONArray(p.azimuthPatternDb.toList()))
            })
        }
        prefs.edit().putString(KEY_PROFILES, json.toString()).apply()
    }

    // --- Site Setups ---

    fun saveSiteSetup(setup: SiteSetup) {
        val setups = loadSiteSetups().toMutableList()
        setups.removeAll { it.id == setup.id }
        setups.add(0, setup)
        prefs.edit().putString(KEY_SITE_SETUPS, toSetupJson(setups).toString()).apply()
    }

    fun loadSiteSetups(): List<SiteSetup> {
        val jsonStr = prefs.getString(KEY_SITE_SETUPS, null) ?: return emptyList()
        val json = JSONArray(jsonStr)
        val setups = mutableListOf<SiteSetup>()
        for (i in 0 until json.length()) {
            val obj = json.getJSONObject(i)
            val sitesArr = obj.getJSONArray("sites")
            val sites = mutableListOf<Site>()
            for (j in 0 until sitesArr.length()) {
                val s = sitesArr.getJSONObject(j)
                sites.add(Site(
                    id = s.getString("id"),
                    label = s.getString("label"),
                    latitude = s.getDouble("latitude"),
                    longitude = s.getDouble("longitude"),
                    groundElevationM = s.optDouble("groundElevationM", 0.0),
                    antennaHeightM = s.optDouble("antennaHeightM", 0.0),
                    erpW = if (s.has("erpW") && !s.isNull("erpW")) s.getDouble("erpW") else null,
                    role = SiteRole.valueOf(s.optString("role", "IGNORED"))
                ))
            }
            setups.add(SiteSetup(
                id = obj.getString("id"),
                label = obj.getString("label"),
                observerLat = obj.getDouble("observerLat"),
                observerLon = obj.getDouble("observerLon"),
                observerAltM = obj.optDouble("observerAltM", 0.0),
                sites = sites,
                profileId = obj.getString("profileId"),
                tripodScaleAtHeading = if (obj.has("tripodScale") && !obj.isNull("tripodScale")) obj.getDouble("tripodScale") else null,
                tripodHeadingTrue = if (obj.has("tripodHeading") && !obj.isNull("tripodHeading")) obj.getDouble("tripodHeading") else null,
                createdMs = obj.optLong("createdMs", 0L)
            ))
        }
        return setups
    }

    fun deleteSiteSetup(id: String) {
        val setups = loadSiteSetups().toMutableList()
        setups.removeAll { it.id == id }
        prefs.edit().putString(KEY_SITE_SETUPS, toSetupJson(setups).toString()).apply()
    }

    private fun toSetupJson(setups: List<SiteSetup>): JSONArray {
        val json = JSONArray()
        for (setup in setups) {
            val sitesJson = JSONArray()
            for (site in setup.sites) {
                sitesJson.put(JSONObject().apply {
                    put("id", site.id)
                    put("label", site.label)
                    put("latitude", site.latitude)
                    put("longitude", site.longitude)
                    put("groundElevationM", site.groundElevationM)
                    put("antennaHeightM", site.antennaHeightM)
                    if (site.erpW != null) put("erpW", site.erpW)
                    put("role", site.role.name)
                })
            }
            json.put(JSONObject().apply {
                put("id", setup.id)
                put("label", setup.label)
                put("observerLat", setup.observerLat)
                put("observerLon", setup.observerLon)
                put("observerAltM", setup.observerAltM)
                put("sites", sitesJson)
                put("profileId", setup.profileId)
                if (setup.tripodScaleAtHeading != null) put("tripodScale", setup.tripodScaleAtHeading)
                if (setup.tripodHeadingTrue != null) put("tripodHeading", setup.tripodHeadingTrue)
                put("createdMs", setup.createdMs)
            })
        }
        return json
    }

    companion object {
        private const val KEY_TARGETS = "saved_targets"
        private const val KEY_LANDMARKS = "saved_landmarks"
        private const val KEY_PROFILES = "antenna_profiles"
        private const val KEY_SITE_SETUPS = "site_setups"
    }
}

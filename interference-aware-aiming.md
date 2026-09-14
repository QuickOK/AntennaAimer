# Feature Spec: Interference-Aware Aiming

Add multi-target, pattern-aware boresight optimization to the AR yagi aiming app.

## Context

This app aims directional antennas. The immediate use case is receiving a P25
simulcast system where several transmitters send identical signals from
different locations. Signal strength is not the problem — **capture ratio** is.
Decode quality depends on one transmitter dominating all the others at the
receiver, so the goal is to maximize (wanted signal − strongest unwanted
signal), not to maximize wanted signal alone.

That distinction drives the whole feature. Pointing directly at the wanted
transmitter is usually **not** the optimal heading. A yagi's deepest pattern
nulls typically sit 100–140° off boresight, not at 180°, so deliberately
offsetting boresight by a few degrees can walk an interferer into a null and
buy 5–10 dB of rejection for a fraction of a dB of wanted-signal loss.

**Do not implement this as a user-entered offset field.** The offset is an
*output* of the optimization, not an input. The user enters where the
transmitters are; the app computes where to point.

## Platform constraint: no magnetometer

The target device (Samsung Galaxy S20 Ultra) reports no geomagnetic sensor.
This is load-bearing for the design:

- **All bearings are TRUE bearings, everywhere.** There is no magnetic frame in
  this app. Do not call `GeomagneticField`, do not apply declination, do not
  store a magnetic variant of any heading. Solar and landmark calibration both
  produce true bearings natively.
- Android's `TYPE_ROTATION_VECTOR` degrades to `TYPE_GAME_ROTATION_VECTOR`
  without a magnetometer: gyro + accelerometer, **arbitrary yaw origin**, and
  yaw that drifts. Absolute heading must come from an explicit calibration
  anchor; the gyro only propagates it forward.
- Detect sensor availability at startup and surface it plainly. If a
  magnetometer *is* present on some device, it may be used as a coarse sanity
  check but never as the primary heading source.

## Domain model

```
Site
  id, label
  lat, lon            // decimal degrees, WGS84
  antennaHeightM      // AGL, optional
  groundElevationM    // AMSL, optional
  erpW                // optional
  role: WANTED | INTERFERER | IGNORED

AntennaProfile
  id, label           // e.g. "Laird Y8066"
  freqRangeMHz
  gainDbd
  hpbwDeg             // half-power beamwidth
  azimuthPattern      // relative gain dB vs off-axis angle, see below

Solution                // computed, never user-entered
  boresightTrue        // degrees
  offsetFromWanted     // boresightTrue - bearingToWanted, signed, for display
  wantedRelGainDb
  perInterfererRelGainDb[]
  marginDb            // the objective value being maximized
```

## Geometry

Use proper great-circle math (haversine for distance, standard forward-azimuth
formula for bearing). Do not use flat-earth approximations — they are fine at
these distances but there is no reason to bake in an error source.

Normalize all angular differences to (−180, 180].

## Antenna pattern

Store a coarse azimuth pattern per antenna: relative gain in dB at 10°
increments from 0° to 180°, mirrored for negative angles, linearly interpolated
between samples.

**Accuracy is not the point.** The pattern needs to be roughly right in shape —
main lobe, sidelobe structure, nulls in approximately the right places — so the
optimizer picks a sane heading. The final few degrees are settled empirically by
BER measurement, not by the model. Do not over-engineer this.

Seed profile for the Laird Y8066 (6 element, 806–896 MHz, 9 dBd, ~42°
horizontal / ~40° vertical beamwidth). These values are a reasonable generic
6-element yagi shape, not measured data — mark them as such in the code and make
profiles editable:

| Off-axis | dB | | Off-axis | dB |
|---|---|---|---|---|
| 0° | 0.0 | | 100° | −20.0 |
| 10° | −0.4 | | 110° | −24.0 |
| 20° | −1.7 | | 120° | −22.0 |
| 30° | −4.2 | | 130° | −19.0 |
| 40° | −8.0 | | 140° | −17.5 |
| 50° | −13.0 | | 150° | −17.0 |
| 60° | −17.0 | | 160° | −17.5 |
| 70° | −15.5 | | 170° | −18.5 |
| 80° | −16.0 | | 180° | −19.0 |
| 90° | −18.0 | | | |

Note the intended shape: the minimum near 110° is deeper than the 180° back
lobe, and there is a shallow sidelobe bump around 70°. That asymmetry is
precisely what the optimizer exploits.

## Optimization

Brute force. Sweep candidate boresight headings over 360° at 0.25° steps and
score each:

```
score(h) = wantedGain(h) - max over interferers of interfererGain(h)
```

where `xGain(h) = patternGain(angleDiff(h, bearingTo(x)))` plus, if
`erpW` and distance are known for both, a free-space path term
`10*log10(erp / d^2)` so a stronger or closer interferer is weighted correctly.
When ERP is unknown, weight all sites equally and say so in the UI.

Return the best heading plus the top 3 local maxima, because the second-best
solution is sometimes preferable for mechanical or line-of-sight reasons and the
user should be able to see and choose it.

Also expose `score(bearingToWanted)` — the naive point-straight-at-it heading —
so the UI can show how much the optimization actually buys. If the gain is under
~1 dB, say so rather than implying precision that isn't there.

## Calibration

Two methods, both producing a true-heading anchor. Persist the anchor with a
timestamp.

### Solar

1. Compute sun azimuth from lat, lon, UTC using Grena algorithm 5 or NOAA SPA.
   Accuracy target better than 0.01°. Clock and GNSS error are negligible here
   (the sun moves ~0.004°/sec; 5 m of position error at 4 km is 0.07°) — do not
   add UI friction worrying about them.
2. **Prefer a shadow reading over sighting the sun.** A plumb gnomon on a level
   surface casts a shadow at sun azimuth + 180°. A 30 cm gnomon read to 2 mm
   gives ~0.4°. Sighting the sun directly through the camera is worse and
   unpleasant.
3. **Gate on solar elevation.** Device pitch/roll error maps into heading error
   scaled by `tan(elevation)`. At 60° elevation, 1° of leveling error becomes
   ~1.7° of heading error; at 20° it's ~0.4°. Refuse to calibrate above ~45°
   elevation and tell the user why. Mid-morning and late afternoon are the good
   windows.
4. Show the computed error contribution from current elevation live, so the
   quality of the anchor is visible rather than assumed.

### Landmark

User sights a feature whose coordinates are known or can be dropped on a map.
App computes true bearing from observer to landmark and stores the delta.
Faster than solar; ideal for re-anchoring mid-session.

## Drift management

Gyro yaw drift on MEMS parts typically runs 1–2°/min, which is comparable to the
width of the null being hunted. This is the single most likely reason the
feature fails in practice, so make it visible rather than silently wrong:

- Display **time since anchor** persistently, not buried in a settings screen.
- Escalate: neutral under 60 s, warning at 2 min, blocking banner at 5 min.
- Estimate accumulated drift from elapsed time and a configurable rate, and show
  it as a ± figure on the heading readout.
- Offer one-tap re-anchor from the main screen.
- Detect large accelerometer transients (the phone being set down or bumped) and
  invalidate the anchor.

## Tripod scale workflow

This is the feature that makes the whole thing usable, and it is easy to miss.

The antenna sits on a tripod head with a panning degree scale. Rather than
tracking the user through a slow sweep with a drifting gyro, the app should:

1. Establish the anchor and compute the optimal boresight.
2. Have the user point the antenna at that heading and enter the **tripod scale
   reading** at that position.
3. From then on, express every target as a scale reading: "sweep from 47 to 77
   on the tripod scale, in 3° steps."

The mechanical scale is driftless, repeatable, and returns to the same heading
weeks later. The app's job is to establish the starting number, not to babysit
the sweep. Store the scale-to-true-bearing mapping per site setup.

## Sweep logging

A measurement mode for the empirical step:

- User steps through headings (by tripod scale) and records a quality metric at
  each stop. Free-form numeric entry — BER, error rate percent, RSSI, whatever
  the receiver reports.
- Lower-is-better vs higher-is-better toggle per metric.
- Plot metric vs heading, mark the measured optimum, and show it against the
  predicted optimum.
- Export as CSV.
- Persist sweeps per site setup so results are comparable across sessions and
  across antenna positions.

Offer to update the stored boresight to the measured optimum when a sweep
completes. The model is a starting guess; the measurement is the truth.

## Seed data (dev/test fixture)

Observer: `35.6413334, -97.4329821`

OKWIN site 2 transmitters, FCC callsign WQQP280 (Oklahoma Dept of Public
Safety), 851–860 MHz downlink:

| Loc | Lat | Lon | Antenna AGL | Ground AMSL | ERP | Description |
|---|---|---|---|---|---|---|
| 1 | 35.66833 | −97.38778 | 115.8 m | 320.7 m | 417 W | ¼ mi NW Danforth & Midwest, Edmond (ASR 1065472) |
| 3 | 35.66056 | −97.47000 | 121.9 m | 366.7 m | 359 W | Chowning & Ayers, Edmond — UCO (ASR 1012602) |
| 5 | 35.39583 | −97.31972 | 73.2 m | 384.4 m | 325 W | I-240 & Anderson, OKC (ASR 1235587) |

Expected geometry from the observer, useful as unit-test assertions:

- Loc 1: 53.7° true, 3.15 mi
- Loc 3: 302.6° true, 2.47 mi
- Loc 5: 159.4° true, 18.12 mi
- Loc 1 ↔ Loc 3 separation: 111.1°
- Loc 5 is ~17.7 dB down on free-space terms and should fall out as a
  non-factor; Loc 1 and Loc 3 are within ~1.5 dB of each other

A good sanity check: with Loc 3 wanted and Loc 1 an interferer, the optimizer
should return a boresight a few degrees off 302.6° — the direction that moves
Loc 1's 53.7° bearing closer to the ~110° null — rather than 302.6° exactly.

## Out of scope

- Terrain and clutter modeling. At 2–3 miles in suburban terrain these dominate
  the 1.5 dB free-space difference between the two candidate sites, which is
  exactly why the workflow ends in measurement rather than prediction. Do not
  pretend to model them.
- Elevation aiming. Path elevation angles here are a fraction of a degree and
  the vertical beamwidth is ~40°. Keep the antenna level.
- Polarization. P25 land mobile is vertically polarized; elements vertical. This
  is a mounting note for the user, not app logic.

## Notes for implementation

- Keep bearing math, pattern interpolation, optimization, and solar position in
  pure functions with no Android dependencies, so they are unit-testable off
  device. The sensor and AR layers should be thin wrappers over them.
- The AR overlay should draw the main lobe cone, the wanted site marker, and a
  marker per interferer showing where it currently sits in the pattern with its
  current relative gain. Seeing an interferer sitting in a null is the thing
  that makes this feature legible.

Build a production-ready custom ClimbPro architecture for a Garmin Forerunner 255 Music consisting of:

1. Android companion app (java preferred, Flutter optional)

2. Garmin Connect IQ datafield app written in Monkey C

3. Bidirectional synchronization using the Garmin Connect IQ Communications API

Goal:
Create a near-native ClimbPro experience where all heavy route analysis is handled on the phone, while only compact and optimized climb data is transmitted to the watch.

The system must:

- work fully offline during activities
- be battery efficient
- have low memory usage on Garmin devices
- support realtime climb rendering
- run reliably on the Forerunner 255 Music

The user must have to following functions available:

- Select a route to follow
- Select mode to only check for climbs within radius
- the function of renaming routes/climbs
- add custom data to routes for details
- show current route/climb data on watch
- start navigation to selected route
- import a single climb/route from a GPX file

---

System Architecture

Phone App Responsibilities

The phone app must:

1. Detect new routes/courses/import from strava

2. Parse GPX and FIT files

3. Analyze elevation data

4. Detect climbs

5. Generate segment data

6. Compress climb data

7. Synchronize data to the Garmin watch

8. Store climb cache locally

9. Support background synchronization

---

Climb Detection

A climb is defined as:

- minimum length of 800 meters
- average gradient >= 3%

For every climb:

- detect start point
- detect end point
- calculate average gradient
- calculate total elevation gain
- calculate cumulative route distance

---

Segmentation

Split every climb into segments representing exactly 8% of the climb length.

For each segment calculate:

- average gradient
- elevation gain
- segment distance
- color category

Color mapping:

- 0–2% → light yellow
- 2–4% → yellow
- 4–6% → dark yellow
- 6–8% → orange
- 8–10% → dark orange
- 10%+ → red

---

Route Preprocessing

Implement smart preprocessing:

- GPX/FIT downsampling
- elevation smoothing
- route simplification
- cache-friendly data structures
- minimal memory footprint

Use:

- Douglas-Peucker or similar algorithm
- moving average elevation smoothing

---

Synchronization

Use the Garmin Connect IQ Communications API.

Synchronization flow:

1. Detect new or modified route/course
2. Analyze the route
3. Generate compact climb objects
4. Perform incremental synchronization
5. Re-sync only changed routes

Support:

- automatic resync
- reconnect handling
- sync status feedback
- retry mechanisms

---

Garmin Datafield Functionality

The Connect IQ datafield must display:

Current Climb View

- current climb profile
- realtime progress
- current position on profile
- color-coded segments
- remaining distance
- remaining elevation gain

Next Climb View

- distance to next climb
- next climb length
- average gradient
- total elevation gain
- miniature climb profile

---

Rendering Requirements

Rendering must:

- be performant
- minimize redraws
- be optimized for small screens
- have low battery impact

Use:

- precomputed segment arrays
- minimal realtime calculations
- efficient canvas rendering

---

Realtime Route Matching

Implement:
GPS position → nearest route point matching.

Requirements:

- performant
- low CPU usage
- stable during GPS drift
- suitable for realtime usage

Use:

- nearest point search
- route snapping
- hysteresis filtering
- progress continuity logic

---

Audio Alerts

At the start of a climb:

- vibration
- sound
- optional overlay

Trigger conditions:

- within 50 meters of climb start
- trigger only once per climb

---

Data Structures

Generate:

- complete Kotlin data models
- Monkey C structs/classes
- serialization formats
- compact sync payloads
- cache models

---

Example JSON Payload

Generate payload examples like:

{
"routeId": "route_123",
"climbs": [
{
"startDistance": 12500,
"endDistance": 15400,
"length": 2900,
"elevationGain": 210,
"avgGradient": 7.2,
"segments": [
{
"gradient": 5.2,
"color": "yellow"
}
]
}
]
}

Optimize payloads for minimal size.

---

Required Code Generation

Generate:

- Kotlin Android app architecture
- background services
- Garmin Connect IQ integration
- Monkey C datafield app
- route parser
- climb detection engine
- sync manager
- cache manager
- rendering engine
- gradient color system
- audio alert system
  -A strava integration to get my routes from strava

---

Recommended Architecture

Use:

- MVVM on Android
- Repository pattern
- background workers
- state management
- immutable data models

For Garmin:

- lightweight rendering
- precomputed arrays
- minimal allocations
- low-memory rendering pipeline

---

Folder Structure

Generate recommended folder structures for:

- Android app
- Garmin CIQ app
- shared protocol models

---

Edge Cases

Support:

- leaving the route
- route reversal
- GPS drift
- missing elevation data
- corrupted GPX files
- extremely long routes
- reconnects
- synchronization failures

---

Goal

The final result should:

- provide near-native ClimbPro functionality
- run reliably on Garmin devices
- have minimal battery impact
- be scalable
- be easy to extend
- be suitable for production use

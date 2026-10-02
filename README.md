# ClimbPro for Garmin Forerunner 255 Music

A custom, near-native **ClimbPro** experience for the Garmin Forerunner 255 Music.
All heavy route analysis runs on an **Android companion app**; the watch only renders
compact, precomputed climb data and matches GPS to the route in realtime.

The product is fully **offline-first**: once a route is on the watch, the ride needs no
phone and no network. Sync is opportunistic.

> **Status — implemented.** This is a working, multi-module codebase: a full Android
> app (Java, MVVM), three Connect IQ apps (Monkey C), and a shared protocol module with
> generated Java POJOs and round-trip tests. Local toolchains (Android Studio, Connect IQ
> SDK, Strava API keys) still have to be installed before you can build — see
> **[HANDLEIDING.md](HANDLEIDING.md)** (Dutch, full install manual + function reference)
> or **[Documentation/SETUP.md](Documentation/SETUP.md)**.

---

## What it does

- **Detects climbs** in Strava routes and imported GPX files — a climb is `≥ 800 m`
  long **and** `≥ 3 %` average gradient (both required). **Exception:** a *starred*
  Strava segment on a synced route is always shown as a climb when it is `≥ 3 %`, even
  if shorter than 800 m, and is named after the segment.
- **Trims false flats** (*vals plat*) off the start/end of each climb so it begins and
  ends on real climbing — but never trims a climb below 800 m.
- **Segments** each climb into a fixed number of slices and **color-codes** them by
  gradient (light yellow → red) using one shared table.
- **Syncs** a compact payload to the watch over the Connect IQ Communications API, with
  incremental resync, retry, and offline-first orchestration.
- **Stays reachable in the background** — a periodic keep-alive worker (plus a
  boot-time trigger) keeps a connected ClimbPro process around so the watch route
  list usually loads without opening the phone app; HELLO re-priming after every
  reconnect and a watch-side request retry make the sync robust across Bluetooth
  drops.
- **On the watch**: shows the live climb profile with progress, previews the next climb,
  shows the surface section you're on, and **alerts** (vibrate + tone) once per climb
  near the start. On a repeat climb, shows a live "ahead/behind your PR" delta per
  segment (falls back to the pacing-plan delta when no PR reference is available yet).
- **Phone-side extras**: per-climb time estimate (fatigue-aware), pacing plan, and a
  climb logbook built from your Strava history — including per-segment PR splits, once
  an activity has been matched against a segmented climb.
- **Wind-impact on the climb time** (issue #47): the climb screen adds a wind-corrected
  estimate with the delta vs. windless ("wind tegen" / "wind mee"), from the current
  Open-Meteo wind at the climb top projected onto each segment's direction. Offline it
  is clearly labelled as uncorrected. Phone-only.

---

## The five apps

| App | Folder | Tech | Role |
|-----|--------|------|------|
| **Android companion** | `android/` | Java, MVVM, WorkManager | Parse GPX/FIT, detect/segment climbs, Strava integration, sync orchestration, all phone-side analytics |
| **ClimbPro browse app** (watch) | `garmin-widget/` | Monkey C (`watch-app` + glance) | Browse synced routes/climbs, save them, mark one **active** |
| **ClimbPro datafield** (watch) | `garmin/` | Monkey C (`datafield`) | Render the active climb during a ride, match GPS, fire the start alert — fully offline |
| **Ondergrond datafield** (watch) | `garmin-surface/` | Monkey C (`datafield`) | Show the user-defined surface section you're on + the next one |
| **ClimbPro Onboard** (watch) | `garmin-onboard/` | Monkey C (`datafield`) | Receives a pushed raw route and does the entire ClimbPro analysis on the watch, rendering a 5 km terrain window during a ride |
| **Shared protocol** | `protocol/` | JSON Schema (canonical) | Single source of truth for the wire format and domain constants |

The four watch apps have **separate, isolated storage**, so selecting an active route on
the browse app is relayed *through the phone* to the datafields. Setting the active
route/climb needs the phone reachable at that moment; the ride itself does not.

### Connect IQ app IDs (must match across phone + manifests)

| App | UUID |
|-----|------|
| Browse app (`garmin-widget`) — phone's sync counterpart | `fedcba9876543210fedcba9876543210` |
| Climb datafield (`garmin`) | `0123456789abcdef0123456789abcdef` |
| Surface datafield (`garmin-surface`) | `00112233445566770011223344556677` |
| Onboard app (`garmin-onboard`) | `a0b1c2d3e4f50617a0b1c2d3e4f50617` |

These are mirrored in each `manifest.xml` and in
`android/.../connectiq/ConnectIqAppId.java`. **For a public release, regenerate all four
to fresh UUIDs** (change the manifest and `ConnectIqAppId` together).

---

## User features

- **Grouped menu** — the route list's overflow menu keeps only Sync, Sorteer, Filter op
  status and Instellingen at the top level; every other screen lives in one of six
  submenus: Klimmen, Ritten & analyse, Training & doelen, Voor de rit, Fiets & materiaal
  and Data & app. "menu → X" elsewhere in this README means: open the matching group first.
- **Choose your units** — Settings → "Eenheden": km or miles (also feet and mph), bar or
  psi, °C or °F. Only the display changes; everything is stored metric. The climb
  datafield, widget and surface datafield follow the distance choice after the next sync
  (optional payload key `un`).
- **Select a route to follow** — pick a synced route; it becomes active on the watch.
- **Radius mode** — no fixed route; the watch alerts on any known climb within a
  configurable radius of your GPS position.
- **Rename routes and climbs** — names survive resync (kept separate from source data).
- **Starred segments as climbs** — a starred Strava segment on a synced route is always
  shown as a climb (when `≥ 3 %`), regardless of length, named after the segment.
- **Automatic Strava segment matching** — during Strava route sync, known public Strava
  segments along the route (`segments/explore`, up to 8 map tiles per route) that meet the
  climb rule (`≥ 800 m` and `≥ 3 %`) replace the detected climb's bounds and give it the
  segment's name. Starred segments still take precedence. When the Strava rate limit gets
  close, exploring stops and continues on a later sync. Phone-only.
- **Flat starred segments** — a starred Strava segment that is too flat to be a climb
  (`< 3 %`) is kept as a separate entity. All such segments appear in the route detail
  screen with a ★. Tap one to tag it with a surface type (asphalt, gravel, dirt,
  cobblestone, mixed). Tagged segments are sent to the watch: they appear in the browse
  widget's route detail list after the climbs, and are included in the surface-section
  datafield payload so the Ondergrond field knows what surface you're on. Untagged
  segments stay phone-only. Surface tags and user renames survive Strava re-sync.
- **Custom notes/tags on routes** — phone-side only; not synced to the watch.
- **Route bucket list** — mark a route as "Wil ik rijden" or "Gereden" from the route
  detail screen; the route list shows the status and can be filtered on it (menu →
  "Filter op status"). Manual only, phone-side only, survives Strava re-sync.
- **Fuel planner** — "Voedingsplanner" on the route detail screen estimates ride time from
  your profile and the route's climbing, and tells you how many bars, gels and bottles to
  bring; temperature comes from the forecast at the start or is typed in.
- **Reverse a route** — "Omgekeerde richting" on the route detail screen creates
  "<naam> (omgekeerd)" with climbs re-detected for the other direction; the original stays
  untouched and tapping again reopens the existing reversed route.
- **Offline package for a route** — route detail → "Offline-pakket" downloads, while you still
  have signal, the hourly forecast at up to 8 points along the route (next 2 days, Open-Meteo)
  and water, food, toilet and bike points within 300 m of the route (OpenStreetMap). Opening it
  later works without any network: per point the temperature range, wind and rain chance for
  the coming 8 hours, and the points listed by km. Refresh or delete from the same dialog; it is
  removed with the route and not included in backups (public data).
- **Shorten a route** — "Route inkorten" on the route detail screen lists shorter variants
  within the route's own geometry (no road router): wherever the route comes back within 200 m
  of itself (figure-eight lobes, out-and-back, a loop past the start) it can be cut off. Each
  option shows the new length, km/hm saved and which climbs are skipped; picking one saves it as
  "<naam> (ingekort, N km)" with climbs re-detected, leaving the original untouched.
- **Loop generator** — "Rondje-generator" in the planning menu: enter a distance and a start
  point (current location or a favourite) and get up to five rides of about that length that
  start and end there, built only from your saved routes (no road router): a saved loop
  restarted at its nearest point, a shortened loop, two loops combined, or out-and-back along a
  route. Tap one to save it as a new route "Rondje N km (…)" with climbs detected.
- **Whole-route elevation profile** — the route detail screen shows the elevation
  profile of the full route with every climb highlighted in its gradient colors. Phone-only.
- **Border crossings** — the route detail screen lists every national border the route
  crosses ("km 84,3 → België · Nederlands/Frans/Duits · 112"), computed fully offline from
  bundled coarse European country boundaries. Phone-only, no wire change.
- **Custom surface sections** — mark an arbitrary stretch of a route with a surface type
  and optional name; rendered on the Ondergrond datafield.
- **Live data on the watch** — current/next climb, progress, surface section.
- **Watch fields** — Settings → "Horloge-velden": choose per slot what the datafield's
  active-climb page shows (remaining distance/elevation, gradient, VAM, ETA, PR/plan delta,
  interval block, speed, heart rate, power, cadence, elapsed time, or empty).
- **Kleurenblind-vriendelijk palet** (issue #258) — Settings → "Kleuren": swaps the
  yellow → red gradient colors for pale yellow → light blue → navy (blue–yellow axis,
  darker = steeper) and green/red status colors for blue/orange, in the app and — via the
  optional payload key `pal` — on the watch datafield and widget. Gradient buckets and
  color indices are unchanged; only the colors differ (table in `protocol/colors.md`).
- **Start navigation** — hands the GPX to Garmin Connect, which pushes the course. Navigating the selected route as a Garmin course also improves on-watch distance accuracy: the datafield matches on course distance (`rtl − distanceToDestination`) with a calibration trust check, falling back to the activity odometer when you are not navigating.
- **Import a single route/climb from a GPX file**.
- **Join two routes** — "Samenvoegen met…" on a route saves it plus a second route as one
  new route (e.g. approach + climbing loop), with climbs re-detected across the joint.
  Originals are kept; a gap between the routes is bridged in a straight line after a warning.
- **Sort the route library** (import time or name); auto-refreshes after a Strava sync.
- **Estimated climb time** — per-climb / per-segment, from your FTP + weights, with
  per-surface rolling resistance and route-wide fatigue (W'-balance). Phone-only.
- **Pacing passport + live ghost** — per-climb target times synced to the watch;
  the climb datafield shows `+/−s` vs plan and a post-summit summary.
- **Ghost at a target speed for new climbs** — Settings → "Ghost voor nieuwe klimmen": set a
  target speed (km/u) and/or VAM (m/u); climbs with no PR and no manual reference time get a
  virtual constant-pace ghost on the watch instead. Phone-only, reuses the existing `refsec`.
- **Interval block per climb** — climb detail → "Intervalblok koppelen": pick Drempel,
  Sweet spot, VO2max, Tempo or your own % FTP; the block lasts from the foot to the top.
  Outdoors the datafield starts it at the climb-start alert, shows the target band (and
  under/in/over with a power meter) and buzzes once at the top ("blok klaar"). Indoors the
  `.zwo`/`.erg`/MyWhoosh export holds the block's target on every climb step. Survives resync.
- **Felt temperature on descents** — between climbs, while descending (≥ 150 m at ≤ −3 %
  and ≥ 25 km/h) the datafield shows a blue `VOELT -4°C` strip: windchill from the
  watch temperature and your riding speed. Watch-only; with a paired Tempe sensor it is
  true air temperature, without one the wrist sensor reads warm. Hidden without a reading.
- **Tunnels and technical descents** — route detail → "Tunnels en gevaarlijke afdalingen"
  looks up road tunnels along the route in OpenStreetMap and lists them together with the
  technical descents found in the route profile (≤ −8 % over 300 m, or ≤ −5 % with hairpins).
  Both ride along to the climb datafield as compact markers: from 400 m ahead a purple strip
  (`TUNNEL 300m - LICHT` / `TECHN. AFDALING 250m`) and one buzz per hazard.
- **Everesting tracker** — climb detail → "Everesting plannen": pick a target (8848 m, or
  1000–10000 m custom) and see the repeats (rounded up), distance and estimated riding time.
  The datafield gets the plan in the payload (`ev`), counts a repeat each time you reach the
  top (re-armed back at the start) and shows a green `EVEREST 3/12  2650/8848m` strip with the
  activity's total ascent; two short buzzes per repeat, a long buzz + tone at the target.
  One attempt per route, survives resync.
- **Lights reminder at dusk** — the climb datafield buzzes once per ride (two long buzzes +
  low tone) and shows a yellow `LICHT AAN` strip for 30 s when it gets dark: from 15 min
  before sunset (configurable: at sunset / 15 / 30 / 60 min) or right at the first GPS fix
  when you start before sunrise or after dusk. Sun times are computed on the watch from GPS +
  clock, so it works offline and without a route. Toggle in the datafield settings.
- **Easier stretch ahead** — on a climb, when the next segments (≥ 200 m) are at least
  3 %-points less steep than the current one, the datafield shows a green `300 m vlakker`
  strip ~150 m before it and buzzes twice lightly, once per stretch. Watch-only, uses the
  synced segment gradients; toggle "Melding vlakker stuk" in the Connect IQ app settings.
- **Heart-rate alarm** — set a limit in the Connect IQ app settings (`Hartslag-alarm`,
  bpm, 0 = off): once your heart rate stays above it for 10 s the datafield buzzes and shows
  a purple `HARTSLAG 185` strip until it drops 5 bpm below, with a reminder every 5 minutes.
  Opt-in `Waarschuw bij onregelmatige hartslag` alerts on three ≥ 25 bpm jumps within a
  minute (at most every 10 min). Watch-only, works without a route; not a medical device.
- **Cadence coach** — opt-in `Cadans-coach` in the Connect IQ app settings (edited from the
  phone in Garmin Connect) with a target band (`Cadans ondergrens`/`bovengrens`, default
  80–100 rpm, 0 = that side off). When your cadence stays outside the band for 30 s of
  pedalling the datafield buzzes (one long = too low, two short = too high) and shows a blue
  `CADANS LAAG 68` strip while you stay out. Coasting doesn't count as too low; no repeat
  until you were back in the band for 20 s, at most one nudge per 2 min. Watch-only,
  works without a route; needs a cadence sensor.
- **Medical ID** — menu → "Medische ID": name, blood type, allergies, medication, emergency
  contact and notes. Optional silent, always-on lock-screen notification (public visibility)
  so first responders can read it without unlocking; re-posted after a reboot. The watch
  widget gets a copy (`MEDICAL_ID` message, re-sent with every route list) and shows it
  offline as a red first row "+ Medische ID" in its route list.
- **Climb history & facts** — the climb screen shows a "Weetjes" card for well-known climbs
  (Alpe d'Huez, Ventoux, Galibier, Tourmalet, Stelvio, Mortirolo, Zoncolan, Angliru, the
  Flemish and Limburg hills, …): Tour/Giro/Vuelta history, famous moments and the side you
  ride. Recognised by the climb's foot and top, or by its name. Bundled dataset, works
  offline; phone-only.
- **Local event calendar** — menu → "Evenementen in de buurt": tour rides and gran fondos
  within a chosen radius (25–250 km) of your last known location, for the coming year, with
  date, route options (km) and elevation. Add the iCal links (`.ics` / `webcal://`) organisers
  and clubs publish, or enter events by hand. Each event says whether it fits your level
  (longest ride / most climbing in the last 90 days), and can be set as your goal event.
  Phone-only.
- **Heat-index warning** — the datafield checks the heat index (NWS, from Garmin Weather's
  temperature + humidity; without weather data the watch temperature sensor) once a minute
  and buzzes when it reaches the threshold set in the Connect IQ app settings (off / 27 /
  32 / 39 °C, default 32). A dark-red `HITTE 41°C` strip stays up until it drops 2 °C
  below the threshold, with a reminder buzz every 20 minutes. Watch-only, works without a route.
- **Eat/drink reminder** — the datafield buzzes (climb-start vibration + time-alert tone) and
  shows a green `ETEN & DRINKEN` strip for 30 s every N minutes of activity time (off / 15 /
  20 / 30 / 45 / 60, default 30) and/or every N metres of ascent (off / 250 / 500 / 750 /
  1000), whichever comes first. When it is warm (from 20 / 25 / 30 °C, default 25) both are
  shortened to 75 %, from 8 °C above that to 50 %; never more than once per 10 minutes. Set
  in the Connect IQ app settings on the phone (Garmin Connect). Watch-only, works without a route.
- **Climb Logbook** — per-climb attempt history + PRs from your Strava rides. Phone-only.
- **Rain radar on the route** — route detail → "Regenradar tonen" lays the latest RainViewer
  radar image over the map and lists, per hour for the next 6 hours, at which kilometres of
  the route rain is expected (Open-Meteo, sampled every 5 km). Phone-only, keyless, on demand.
- **Best time to ride a climb** — climb detail → "Beste moment: juni–september, ochtend" with
  a small month table (temperature, wind, rain chance per month's best day-part), scored from
  3 years of Open-Meteo weather history at the climb (incl. headwind along the climb). Tap to
  load once; cached under `climate/`, so it works offline afterwards. Phone-only.
- **Points of interest along the route** — route detail → "Bezienswaardigheden" lists
  viewpoints, monuments, memorials, castles, ruins, artworks and attractions within 300 m of the
  route from OpenStreetMap (Overpass API, keyless), with type, kilometre and distance beside the
  route; tap one to open it in a map app. Fetched once per route and cached under `route_pois/`,
  so it works offline afterwards ("Vernieuwen" refetches). Phone-only.

- **Temperature trend over the ride** — route detail → "Temperatuurtrend tonen", pick a start
  time (today, or tomorrow if already past) and see a chart of the expected temperature at each
  point of the route at the moment you pass it, plus start/finish/warmest/coldest. Pace comes
  from your pacing plan (25 km/h without a profile). Open-Meteo, height-corrected. Phone-only.

- **Wind-optimised loop direction** — route detail → "Beste rijrichting (wind)" says which way
  round to ride a loop so the last kilometres home have the wind at your back (Open-Meteo
  wind at the start, averaged over the next 3 hours). Point-to-point routes are recognised
  and left alone. Phone-only, keyless, on demand.

- **Klim van de week** — one suggested climb per ISO week, chosen from your climb catalog by
  riding history (never / long not ridden), distance from your last known location and the
  week's weather (Open-Meteo); stays the same all week. Offline it skips the weather and says so.
  Phone-only.

- **Hardest climbs in your region** — top 10 known climbs within a radius of your location,
  ranked by difficulty score (elevation gain × average gradient). Phone-only.

- **Clothing advice** — what to wear for your planned ride window, from the forecast with
  the riding wind in the wind chill, plus rain and removable-layer hints. Phone-only.

- **Air quality and pollen** — particulate matter, European AQI and pollen for your planned
  ride window at your location, with a warning for asthma or hay fever (Open-Meteo, keyless).
  Phone-only.

- **Ride comparer** — tap a ride in Ritten and pick another ride over the same route to see
  time, speed, heart rate and the running time difference per kilometre. Phone-only.

- **Ride story** — tap a ride in Ritten → "Rit-verhaal delen": one shareable image with the
  route shape, stats, climbs and PRs, temperature and a photo from the ride. Phone-only.

- **Ritmaatjes (ride-buddy matcher)** — menu → Ritten & analyse → Ritmaatjes. "Deel mijn
  profiel" turns your last half year of rides into a short `CPR1:` profile code (flat-road
  pace, climbing VAM, typical distance, road/gravel/MTB, riding days and dayparts, and —
  opt-in only — a coarse ~5 km area, never your address); you tick which fields go in and see
  exactly what the code contains before sharing. Paste codes from other riders (or share the
  chat message to ClimbPro) and they are ranked by similarity with a score and a reason, e.g.
  "vergelijkbaar tempo, 12 km verderop". No server; phone-only.

- **Groepsrit plannen (group-ride planner)** — menu → Ritten & analyse → Groepsrit plannen.
  Pick a saved route and the riders (yourself, imported Ritmaatjes profile codes, or riders
  added by hand with just a name and average speed). ClimbPro estimates the group pace and
  riding time — the slowest rider sets the flat pace (plus a small draft bonus), climbs cost
  the slowest VAM because the group regroups at the top, plus a short stop per 2,5 h — warns
  about a big level difference, proposes the three best dates in the next two weeks from
  everyone's riding days and dayparts, and shares the whole proposal as text through any chat
  app. No server, nothing stored; phone-only.

- **Favorite start points** — save home, work or a parking spot once (current location or
  typed coordinates) and pick it as start in the Hoogtemeter-doel and Meerdaagse toer
  planning. Phone-only.

- **Recovery check after a ride** — tap a ride in "Ritten" → "Herstel-check" to log how hard it felt (RPE 1–10)
  and how you slept (1–5, optional hours and a note); "Herstel" shows the trend next to each
  ride's distance, time, speed and power, and warns when rides feel harder while sleep gets
  worse. Phone-only.

- **Packing list per ride type** — editable checklists (Training, Toerrit, Bikepacking or
  your own) to tick off before you leave, with a one-tap reset. Phone-only.

- **Bike theft passport** — frame number, brand/model, purchase details, photos and the
  receipt per bike, shared in one go (text + attachments) for a police report or insurance
  claim. Phone-only.

- **Bike garage ("Fietsgarage")** — several bikes (road, gravel, MTB, trainer) with weight,
  tyre width, gearing and a link to the Strava bike. Synced rides land on the right bike via
  Strava's gear id; unlinked indoor rides (MyWhoosh/VirtualRide) go to the trainer bike, the
  rest to the active bike. The active bike's weight and gearing feed the time estimates and
  the gear calculator; cost per km, maintenance parts and the tyre-pressure reminder can
  count only one bike's km. Existing bike weight, cost bikes and passports are migrated
  automatically. Phone-only.

- **Badges** — automatic achievements such as your first 100 km, 10.000 km in total, an
  Everest of climbing, five rides before 7:00 and every climb of a collection, with date or
  progress. Phone-only.

- **Climb repeats workout** — climb detail → "Exporteer als indoor-workout" → "Herhaal-klim"
  writes "N× deze klim" (2–10×, default 5) as a Zwift `.zwo` or `.erg`: warm-up, the climb's
  segment blocks N times with recovery at 50 % FTP in between (default half the climb time,
  3–10 min), cool-down. Phone-only.

- **Import via share link (Komoot / RideWithGPS)** — "Route toevoegen" (or menu *Data & app*)
  → "Route via deellink" takes a pasted Komoot tour or RideWithGPS route/trip link; sharing
  the link from the Komoot/RideWithGPS app to "Route importeren (ClimbPro)" works too. The route
  is fetched without API keys and runs through the normal GPX import. Private routes give a
  clear error (use a Komoot link with `share_token` or make the route public). Phone-only.
- **MyWhoosh import** — "Route toevoegen" → "MyWhoosh-rit importeren (FIT)" reads a ride
  exported from MyWhoosh (or Strava / Garmin Connect "export original"), runs the normal climb
  detection and files the route under the collection "MyWhoosh". Rides without GPS positions
  are laid out by distance and shown as profile only (not usable for radius mode or
  navigation). Phone-only.
- **MyWhoosh via Strava (automatic)**: with MyWhoosh linked to Strava, every sync turns new
  "MyWhoosh - <route>" rides from the ride archive into routes with climbs (at most 10 per
  sync, rate limit). Rides without climbs and repeats of an already-imported route add
  nothing; repeats show up as logbook attempts instead. Phone-only.
- **MyWhoosh export** — climb detail → "Exporteer als indoor-workout" → "MyWhoosh-workout"
  writes a `.zwo` that MyWhoosh's web workout builder accepts (plain steps, whole-percent FTP
  power, short name, gradients in the description). MyWhoosh can't import custom routes, so
  the climb goes in as a workout; a dialog explains the upload. Phone-only.
- **intervals.icu** — Settings → "intervals.icu koppelen": paste your personal API key
  (intervals.icu → Settings → Developer Settings) and athlete id (0 = your own), with a
  connection test. Climb detail → "Exporteer als indoor-workout" → "Naar intervals.icu" (or
  the repeat-climb variant) plans the `.zwo` workout on your intervals.icu calendar on a
  chosen date, as indoor (`VirtualRide`) or outdoor (`Ride`); from there it syncs on to
  Zwift/Garmin if you set that up in intervals.icu. The description carries the climb's PR and
  attempt count. The key is stored encrypted and kept out of backups. Phone-only.
- **FTP test assistant** — menu → "FTP-test" explains the 20-minute test (15 min warm-up,
  5 min blow-out, 10 min recovery, 20 min all-out, 10 min cool-down) with targets from your
  current FTP, and exports it as a plain `.zwo` for MyWhoosh/Zwift. After the Strava sync the
  screen picks up the test ride (named "FTP", or a maximal effort within 14 days of the
  export), shows best 20 min × 0.95 and offers "FTP bijwerken naar X W" — the FTP only
  changes after you confirm. Phone-only.

- **Training advice after a climb** — climb screen: short, concrete pacing tips for your
  latest attempt ("begin de eerste 400 m rustiger", where you dropped furthest below your
  average, where you lost most time against your best splits), from the stored segment
  splits. Phone-only.

- **PR chance before riding** — the climb screen shows whether a PR is realistic today
  (goede kans / matig / onwaarschijnlijk) with the main reasons, combining your attempt
  history on that climb, fitness and form from your ride archive, and the summit weather
  forecast (left out when offline). Phone-only.

- **Climb training block** — menu "Trainingsblok": a 4-week plan (3 build weeks, 1 recovery
  week) built from climbs you have already ridden. Weekly climbing load starts at your recent
  4-week average and rises 10 % per week while the allowed climbs get harder; each week lists
  2-3 sessions as "N× climb". Phone-only.

- **Training-load calendar** — menu → "Trainingskalender" shows a year of daily training load
  as a GitHub-style heatmap (TSS-style load per ride from the ride archive, plus climb
  attempts); tap a day for load, rides, climbs and elevation. Phone-only.

- **Sweat-loss estimator** — weigh yourself before and after a ride, log what you drank and
  get your sweat rate (L/h) plus a personal drinking advice (ml and bottles per hour) for
  future rides. Phone-only.

- **Gear calculator for a climb** — climb screen → "Versnellingen berekenen": enter your
  chainrings, cassette, wheel size and target cadence to see the cadence per gear on the
  steepest segment and at the average gradient, and whether your easiest gear is light
  enough. Phone-only.

- **Monthly challenge** — pick a goal for the month (distinct climbs, hoogtemeters, km or
  rides) or let the app suggest one from your last three months, with progress and pace. Phone-only.

- **Multilingual app** — Dutch (default), English, German, French and Italian. Settings → "Taal"
  picks a language for ClimbPro only (or follows the system language); on Android 13+ it is
  also available in the system's per-app language settings. The core screens (route list and
  menu, route detail, climb detail, settings, Strava sign-in, widget) are translated; texts
  computed in view models/domain code and some secondary screens still show Dutch. Phone-only.

---

## Repository layout

```
.
├── android/                # Android companion app (Java, Gradle Groovy DSL)
│   └── app/src/main/java/nl/paree/climbpro/
│       ├── connectiq/      # Connect IQ Mobile SDK wrapper, payload codec, app IDs
│       ├── data/           # Repositories: route, strava, sync, rider profile, attempts
│       ├── domain/         # Pure logic: climb, segment, route, matching, power
│       ├── service/        # SyncOrchestrator, workers, payload builders, pacing
│       └── ui/             # MVVM screens: routes, climbs, settings, strava
├── garmin/                 # ClimbPro datafield (Monkey C)
├── garmin-widget/          # ClimbPro browse app + glance (Monkey C)
├── garmin-surface/         # Ondergrond (surface) datafield (Monkey C)
├── garmin-onboard/         # ClimbPro Onboard watch app (Monkey C)
├── protocol/               # schema.json (canonical) + examples + colors.md + raw-route.md
├── Documentation/          # ARCHITECTURE.md, SETUP.md, CONNECTION.md, plans
├── docs/                   # garmin-widget-setup.md, superpowers plans & specs
├── HANDLEIDING.md          # Full Dutch manual: functions + complete install guide
└── CLAUDE.md               # AI-session operating instructions
```

`android` and `garmin*` both depend on `protocol`; they never depend on each other.

---

## Build & run (quick reference)

Full step-by-step (prerequisites, keys, sideloading, troubleshooting) is in
**[HANDLEIDING.md](HANDLEIDING.md)**.

```powershell
# Preflight gate (recommended): runs all checks, then builds only if they pass
pwsh -File scripts\preflight.ps1     # JVM tests + Monkey C compile (+ sim tests if available)
pwsh -File scripts\build.ps1         # preflight, then APK + .prg artefacts

# Android companion app (direct)
cd android
.\gradlew.bat assembleDebug          # build debug APK
.\gradlew.bat test                   # run JVM unit tests
.\gradlew.bat :app:installDebug      # install on a connected phone

# Watch apps (Connect IQ SDK + a developer key)
monkeyc -o garmin\ClimbPro.prg        -f garmin\monkey.jungle        -y <key> -d fr255m
monkeyc -o garmin-widget\ClimbBrowse.prg -f garmin-widget\monkey.jungle -y <key> -d fr255m
monkeyc -o garmin-surface\Surface.prg -f garmin-surface\monkey.jungle -y <key> -d fr255m
```

Target device: **Garmin Forerunner 255 Music** (`fr255m`). Other devices are out of scope.

---

## Domain rules (non-negotiable)

These come from `Idea.md` and live in `domain/climb/ClimbConstants.java` + `protocol/`:

| Rule | Value |
|------|-------|
| Min climb length | `800 m` |
| Min average gradient | `3 %` |
| False-flat trim threshold | `< 2 %` over `≥ 200 m` (never below the 800 m min) |
| Segment color cutoffs | 0–2 % light yellow · 2–4 % yellow · 4–6 % dark yellow · 6–8 % orange · 8–10 % dark orange · 10 %+ red |
| Climb-start alert | vibrate + tone, once per climb, within `50 m` of start (jitter-safe) |
| Route-matching hysteresis | `20 m` |

Change a threshold in **one** place (`ClimbConstants` / `protocol/colors.md`), never inline.

---

## Documentation map

| File | What it covers |
|------|----------------|
| **[HANDLEIDING.md](HANDLEIDING.md)** | **Start here** — full function reference + complete install manual (Dutch) |
| [Documentation/ARCHITECTURE.md](Documentation/ARCHITECTURE.md) | Module boundaries, data flow, design contract |
| [Documentation/CONNECTION.md](Documentation/CONNECTION.md) | Phone ⇄ watch Connect IQ connection internals (Dutch) |
| [Documentation/SETUP.md](Documentation/SETUP.md) | Toolchain install checklist |
| [DEPLOYMENT.md](DEPLOYMENT.md) | Release keystore, signed CI builds, in-app auto-update |
| [docs/garmin-widget-setup.md](docs/garmin-widget-setup.md) | Building + sideloading the browse widget (Dutch) |
| [protocol/schema.md](protocol/schema.md) | Why the wire format is shaped the way it is |
| [Idea.md](Idea.md) | Original product specification |
| [CLAUDE.md](CLAUDE.md) | AI-session operating instructions |

---

## Wire format in one glance

The watch payload is the **v3 packed format**: short keys and packed integer arrays to
fit the watch's tight memory (cap `4096 bytes` per message). Gradients are fixed-point
(`percent × 10`), colors are integer indices, and per-segment data is packed as
`segs = [distance, elevationGain, gradient×10, colorIndex, …]` (4 ints/segment).
An optional parallel array `vam = [avgVamMPerH, peakVamMPerH, …]` (2 ints/segment)
carries a gradient-implied VAM (vertical ascent m/h, not a measured ascent rate —
routes have no elapsed-time data) per segment, omitted unless every segment has one.
An optional `ib = [targetWatts, lowWatts, highWatts]` carries a climb's interval block
(issue #180), computed on the phone from % FTP; omitted without a block or FTP.
With FTP and weights set, an optional parallel array `zc` (1 int/segment, issue #66)
carries each segment's **FTP intensity-zone color** (Coggan zone → the same 0–5 color
indices); the watch shows it instead of the gradient colors when its *Kleurmodus*
setting is *FTP-zone*.
An optional top-level `"pal": 1` (issue #258) tells the watch to draw those same color
indices with the colorblind-friendly palette; absent = default palette.
An optional top-level `un` bitmask (issue #262: 1 = mi/ft, 2 = psi, 4 = °F; absent =
metric) tells the watch which display units the rider chose; all wire values stay metric.
`protocol/schema.json` is canonical; Java POJOs are **generated** from it
(`generateProtocolPojos`), Monkey C parsers are hand-written, and `ProtocolRoundTripTest`
validates both the examples and the live builder output against the schema. When you
change the wire format, update `schema.json`, `protocol/examples/`, `ClimbPayloadBuilder`,
**and** the Monkey C parsers together.

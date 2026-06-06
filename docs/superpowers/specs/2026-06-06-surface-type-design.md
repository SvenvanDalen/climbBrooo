---
name: surface-type-per-segment
description: Per-segment surface type annotation (asfalt/gravel/onverhard/kasseien/mixed) with auto-detection from GPX/Strava, manual override per segment in ClimbDetail, route-list filtering, and a color-coded surface bar on the Garmin watch
metadata:
  type: project
---

# Surface Type per Segment — Design

## Summary

Add surface type (asfalt / gravel / onverhard / kassei / mixed) to each climb segment. Surface type is auto-detected from GPX metadata and Strava `sport_type` at route level (propagated to all segments as default). Users can override per individual segment in ClimbDetailActivity, or set all segments of a climb at once with a bulk action. Routes are filterable in the route list by surface type. The Garmin watch shows a thin color-coded bar below the climb profile that updates live as the rider moves through segments.

---

## 1. Data Model & Protocol

### SurfaceType enum

Lives in `protocol/` so both Android and Garmin share the definition.

| Index | Constant | Watch color | Label |
|-------|----------|-------------|-------|
| 0 | ASPHALT | `#404040` donkergrijs | "A" |
| 1 | GRAVEL | `#C8A050` zandgeel | "G" |
| 2 | DIRT | `#8B4513` bruin | "D" |
| 3 | COBBLESTONE | `#909090` middengrijs | "K" |
| 4 | MIXED | `#9060C0` paars | "M" |
| 5 | UNKNOWN | niet getekend | — |

Java: `SurfaceType.java` in `protocol/` as an enum with `fromInt(int)` factory and `toInt()` method.
Monkey C: `SurfaceType.mc` hand-written constant block, same indices.

### Segment model changes

`Segment.java` (domain): add `public final int surfaceType;` (default `SurfaceType.UNKNOWN`).  
`StoredSegment.java` (data): add `public int surfaceType = 5;` (UNKNOWN).

### Climb / StoredClimb

No new field needed at climb level — surface type lives fully at segment level.

### catalog.json index entry

Add `surfaceTypes: [int]` — a deduplicated set of all non-UNKNOWN surface type indices present across all segments of all climbs on the route. Used for cheap filtering without loading individual route files.

Example:
```json
{ "routeId": "r1", "name": "Ardennen Lus", "surfaceTypes": [0, 1] }
```

---

## 2. Wire Payload (protocol v3)

Add an optional `surf` array per climb object — one int per segment, parallel to `segs`.

```json
{
  "v": 3,
  "mode": "route",
  "routeId": "r1",
  "climbs": [{
    "sd": 1000, "ed": 3000, "len": 2000, "eg": 80, "ag": 40,
    "segs": [125, 5, 40, 2, 125, 5, 52, 3, ...],
    "surf": [0, 0, 1, 1, 1, 0, 0, 0, 1, 1, 1, 1, 0, 0, 0, 0],
    "calib": [800, 5150000, 510000]
  }]
}
```

**Rules:**
- `surf` is omitted entirely if all segments of that climb are UNKNOWN — saves bytes, watch handles absence gracefully.
- `surf` length must equal number of segments (`segs.size() / 4`). Mismatch → watch treats entire climb as UNKNOWN.
- Payload version bumped to 3. CommListener version check updated accordingly.
- Payload budget impact: 16 segments × 1 int ≈ 48 bytes per climb — well within 4 KB budget even for 12 climbs.

---

## 3. Auto-Detection

Detection runs via a new `SurfaceTypeDetector` utility class (pure function, no I/O), called from `RouteRepository` when a route is first imported or resynced. Result is applied uniformly to all segments as a default; user overrides are preserved on subsequent resyncs.

**GPX sources (checked in order):**

| Source | Tag / attribute | Mapping |
|--------|----------------|---------|
| Garmin Connect | `<Type>road_cycling</Type>` | ASPHALT |
| Garmin Connect | `<Type>mountain_biking</Type>` | DIRT |
| Komoot | `<komoot:meta sport="racebike">` | ASPHALT |
| Komoot | `<komoot:meta sport="touringbicycle">` | ASPHALT |
| Komoot | `<komoot:meta sport="gravel">` | GRAVEL |
| Komoot | `<komoot:meta sport="mtb">` | DIRT |
| Ride with GPS | `<type>gravel</type>` on track | GRAVEL |
| Fallback | none found | UNKNOWN |

**Strava `sport_type` field:**

| Strava value | Mapping |
|---|---|
| `Ride` | ASPHALT |
| `GravelRide` | GRAVEL |
| `MountainBikeRide` | DIRT |
| anything else | UNKNOWN |

**Conflict resolution:** if GPX and Strava disagree, Strava wins (it reflects what the user classified the activity as). If only one source available, use that. User manual override always wins over both.

**Resync protection:** `StoredSegment.surfaceType` is only overwritten by auto-detection if the current value is UNKNOWN. A user-set value (non-UNKNOWN) is never overwritten.

---

## 4. Android UI

### RouteListActivity — filter chips

Horizontal scrollable chip row above the route list:

```
[ Alle ] [ Asfalt ] [ Gravel ] [ Onverhard ] [ Kasseien ] [ Mixed ]
```

- A route appears under a filter chip if `catalog.json` entry's `surfaceTypes` array contains that type's index.
- Routes with only UNKNOWN segments appear under "Alle" only.
- Multiple chips can be selected simultaneously (OR logic: show routes matching any selected type).
- Default: "Alle" selected, no filtering.

### ClimbDetailActivity — segment list

Each segment row (RecyclerView item) gains:
- A small badge on the right side: `[A]`, `[G]`, `[D]`, `[K]`, `[M]` — colored background matching the surface color.
- Rows with UNKNOWN show no badge.

**Long-press on a segment row** → BottomSheetDialog with radio buttons:
```
Oppervlaktype voor segment 3
○ Asfalt
● Gravel        ← currently selected
○ Onverhard
○ Kasseien
○ Mixed
[ Opslaan ]  [ Annuleer ]
```

**Bulk-setter** above the segment list:
```
Stel alle segmenten in op: [ Asfalt ▼ ]  [ Toepassen ]
```
Sets all segments of this climb to the chosen type. Does not affect other climbs on the route.

After saving: `RouteRepository` writes atomically, updates `catalog.json` `surfaceTypes`, `ClimbDetailViewModel` reloads the climb, and a sync is triggered to push the updated payload to the watch.

---

## 5. Garmin Watch — Surface Bar

### Visual layout

```
┌─────────────────────────────┐
│  Klim 2/4   2.3km  ↑ 87m   │  ← header (bestaand)
├─────────────────────────────┤
│  ████░░░░░░░░░░░░░░░░░░░░░  │  ← gradient kleurprofiel (bestaand)
│  ▓▓▓▓▓▓▓▓░░░░░░░░████████  │  ← surface bar (5px hoog, nieuw)
├─────────────────────────────┤
│  ▶ Seg 3 · 4.2% · [G]      │  ← huidig segment info (bestaand + surface letter)
└─────────────────────────────┘
```

- Surface bar: 5px tall, same x/width as the gradient profile bar, positioned 2px below it.
- Each segment fills its proportional width with its surface color.
- Bar is entirely omitted when `surf` array absent or all segments UNKNOWN — no empty grey strip.
- Current segment's surface type letter is appended to the existing segment info line.

### Monkey C implementation

**`ClimbData.mc`** — add parallel surface array:
```monkeyc
const MAX_SURF = 20;  // matches MAX_SEGMENTS
var segSurf;          // [MAX_CLIMBS][MAX_SURF] int arrays

// In initialize(), after segColor allocation:
segSurf = new [MAX_CLIMBS];
for (var i = 0; i < MAX_CLIMBS; i++) {
    segSurf[i] = new [MAX_SURF];
    for (var k = 0; k < MAX_SURF; k++) { segSurf[i][k] = 5; } // UNKNOWN
}
```

**`CommListener.mc`** — decode `surf` array after `segs`:
```monkeyc
var surf = climbDict.get("surf");
if (surf != null && surf instanceof Toybox.Lang.Array) {
    var surfCount = surf.size();
    if (surfCount > data.MAX_SURF) { surfCount = data.MAX_SURF; }
    for (var s = 0; s < surfCount && s < data.segCount[idx]; s++) {
        data.segSurf[idx][s] = surf[s];
    }
}
```

**`ClimbView.mc`** — draw surface bar in `onUpdate()`:
```monkeyc
hidden function drawSurfaceBar(dc, climbIdx, barX, barY, barWidth) {
    // barY = gradient bar bottom + 2
    var segCount = data.segCount[climbIdx];
    var allUnknown = true;
    for (var s = 0; s < segCount; s++) {
        if (data.segSurf[climbIdx][s] != 5) { allUnknown = false; break; }
    }
    if (allUnknown) { return; }

    var segW = barWidth / segCount;
    for (var s = 0; s < segCount; s++) {
        var color = surfaceColor(data.segSurf[climbIdx][s]);
        dc.setColor(color, Graphics.COLOR_TRANSPARENT);
        dc.fillRectangle(barX + s * segW, barY, segW, 5);
    }
}

hidden function surfaceColor(surfType) {
    if (surfType == 0) { return 0x404040; }  // ASPHALT
    if (surfType == 1) { return 0xC8A050; }  // GRAVEL
    if (surfType == 2) { return 0x8B4513; }  // DIRT
    if (surfType == 3) { return 0x909090; }  // COBBLESTONE
    if (surfType == 4) { return 0x9060C0; }  // MIXED
    return Graphics.COLOR_TRANSPARENT;       // UNKNOWN
}
```

---

## 6. Protocol Schema Update

`protocol/schema.json` bump to version 3:
- Add `"surf"` as optional array of integers to the climb object definition.
- Add `SurfaceType` as a named definition with `enum: [0,1,2,3,4,5]`.
- Update `protocol/examples/` with a v3 example payload containing `surf`.
- Update `protocol/schema.md` with change log entry.

Java POJOs are generated — no manual edits. Monkey C updated by hand in same change.

---

## 7. Migration

`CommListener.mc`: version check updated from `!= 2` to `!= 3`. Old payloads (v2) are rejected and the watch shows "Sync vereist" — user must resync from the phone. This is acceptable because surface type is a new feature; old payloads simply have no surface data.

`RouteRepository`: no migration needed for stored routes — `StoredSegment.surfaceType` defaults to UNKNOWN (5) for old stored routes. On first resync after update, the new payload v3 is sent with `surf` omitted (all UNKNOWN), matching the old behavior.

---

## 8. Edge Cases

- **`surf` array shorter than `segCount`:** remaining segments treated as UNKNOWN.
- **`surf` value out of range (>5):** treated as UNKNOWN.
- **User sets bulk type on a climb with existing overrides:** bulk action overwrites all segments — confirmed via dialog ("Dit overschrijft alle individuele instellingen").
- **Resync after user override:** override preserved because UNKNOWN-check gates auto-detection.
- **Radius mode:** `surf` array works identically — radius-mode climbs follow the same payload structure.
- **Watch display when bar is very narrow:** minimum segment draw width = 1px; for very long climbs with many segments the bar still renders, just fine-grained.

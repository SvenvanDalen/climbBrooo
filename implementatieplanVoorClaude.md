# Implementatieplan: 16 segmenten per klim + geen limiet op klimmen

## Overzicht van de wijzigingen

Er zijn twee kerndoelen:
1. Elke klim krijgt altijd exact 16 segmenten
2. Alle klimmen van een route worden getoond zonder maximum

---

## Appendix: Garmin FR255M — payload-limieten

### Connect IQ communicatiearchitectuur

De app communiceert met de Garmin FR255M via het Connect IQ **Communications API** (`Toybox.Communications`). Android stuurt data naar het horloge via `transmitMessage()`, en het horloge ontvangt dit via de `onMessage()` callback.

### Bekende limieten voor de FR255M

| Parameter | Waarde | Bron |
|-----------|--------|------|
| Connect IQ versie | 4.2.2 | Garmin device specs |
| App-geheugen | 16 MB | Garmin device specs |
| `transmitMessage()` payload | **maximaal 4.096 bytes** | Connect IQ Communications API documentatie |
| JSON-overhead | ~30–40% | Sleutelnamen + formatting kosten extra bytes |

> **Belangrijk:** De huidige `PayloadBudget.MAX_BYTES = 8 * 1024` (8 KB) in de code is dus **te hoog** voor de FR255M. Die waarde was een schatting; in de praktijk mag de payload niet groter zijn dan **4.096 bytes**.

### Wat dit betekent voor 16 segmenten

Een ruwe berekening van de JSON-payload per klim:

```
Per klim (JSON):
  "length": 4 cijfers          → ~11 bytes
  "elevationGain": 3 cijfers   → ~18 bytes
  "avgGradient": getal         → ~16 bytes
  "startDistance": 5 cijfers   → ~18 bytes
  "endDistance": 5 cijfers     → ~16 bytes
  "name": max 32 chars         → ~38 bytes
  "segments": array header     → ~14 bytes

Per segment (JSON, × 16):
  {"distance":NNN,"elevationGain":NN,"gradient":NNN,"colorIndex":N}
  → gemiddeld ~65 bytes per segment × 16 = ~1040 bytes

Totaal per klim: ~1.170 bytes
Payload-overhead (wrapper, mode, routeId): ~80 bytes
```

**Conclusie:** bij de 4 KB limiet van de FR255M passen comfortabel **2 klimmen** (2 × 1.170 + 80 = ~2.420 bytes). Met wat compressie (zie Plan D) haal je er 3, eventueel 4.

### Runtime limieten opvragen (aanbevolen)

De Connect IQ watch-app kan de feitelijke limiet opvragen via:
```monkeyc
var budget = Communications.getDeviceCapabilities().communicationsBudgetInBytes();
```
Dit geeft de werkelijke beschikbare bytes voor het huidige apparaat terug. Gebruik dit in de watch-app om te besluiten hoeveel data geaccepteerd wordt, in plaats van een hardcoded getal.

> **⚠️ Let op:** Mijn kennis over de exacte limiet voor de FR255M (4.096 bytes) is gebaseerd op de Connect IQ API-documentatie en bekende community-ervaringen tot mijn kennisdatum (augustus 2025). Garmin kan dit in firmwareupdates aanpassen. Verifieer via de [officiële Connect IQ API-docs](https://developer.garmin.com/connect-iq/api-docs/Toybox/Communications.html) en test op het echte apparaat.

---

## Deel 1 — Kern: 16 segmenten per klim

### 1.1 `ClimbConstants.java` — de enige plek om aan te draaien

Het huidige systeem gebruikt `SEGMENT_FRACTION = 0.08` (= 1/12,5), wat resulteert in een variabel aantal segmenten afhankelijk van hoe de klim is opgebouwd. Om altijd precies 16 segmenten te krijgen, vervang je die fractie door een vaste constante:

```java
// VERWIJDER:
public static final double SEGMENT_FRACTION = 0.08;

// VERVANG DOOR:
public static final int SEGMENT_COUNT = 16;
```

Dat is de **enige** constante die je nodig hebt. Geen andere klasse hoeft dit getal te kennen.

---

### 1.2 `Segmenter.java` — berekening aanpassen

De huidige logica berekent `segmentLength = totalLength * SEGMENT_FRACTION` en loopt door totdat de afstand op is. Dit geeft een variabel aantal segmenten.

De nieuwe logica: verdeel altijd in precies `SEGMENT_COUNT` gelijke stukken.

**Wijziging in `segment()`:**

```java
public static List<Segment> segment(List<RoutePoint> climbPoints) {
    if (climbPoints == null || climbPoints.size() < 2) return new ArrayList<>();

    RoutePoint first = climbPoints.get(0);
    RoutePoint last  = climbPoints.get(climbPoints.size() - 1);
    double totalLength = last.distance - first.distance;
    if (totalLength <= 0) return new ArrayList<>();

    // NIEUW: altijd exact SEGMENT_COUNT segmenten
    double segmentLength = totalLength / ClimbConstants.SEGMENT_COUNT;
    List<Segment> segments = new ArrayList<>(ClimbConstants.SEGMENT_COUNT);

    // ... rest van de logica blijft gelijk ...
}
```

De `while`-loop en interpolatielogica hoeven **niet** te veranderen — die werken al correct met elke `segmentLength`. Alleen de berekening van `segmentLength` wijzigt.

**Randgeval:** Als een klim korter is dan 16 meetpunten, kan de interpolatie alsnog minder dan 16 segmenten opleveren omdat meetpunten samenvallen. Dit is een bestaand probleem in de code en los je op door in `interpolateElevation` te zorgen dat ook bij `span == 0` correct wordt doorgegeven.

---

### 1.3 `RouteRepository.java` — bestaande opgeslagen routes migreren

Routes die al zijn opgeslagen in JSON hebben segmenten die zijn berekend met de oude fractie. Na de update worden nieuwe routes correct aangemaakt, maar oude routes hebben nog het verkeerde aantal segmenten.

**Optie A (simpel):** bij het laden van een route controleer je of `climb.segments.size() != 16`. Zo ja, herbereken je de segmenten opnieuw vanuit de opgeslagen route-punten (`route.lats`, `route.lons`, `route.elevations`, `route.distances`).

**Optie B (grondig):** voeg een versienummer toe aan `StoredRoute` (bijv. `int segmentVersion`). Als het versienummer ≠ de huidige versie, herbereken je alle klimmen en sla je de route opnieuw op.

Voor dit project is **optie A** het meest pragmatisch: gooi gewoon alle bestaande `.json`-bestanden weg bij de eerste installatie van de nieuwe versie (via een `SharedPreferences`-migratievlag).

---

## Deel 2 — Geen maximum op het aantal klimmen

### 2.1 Waar zit het huidige maximum?

Er is **geen expliciete limiet** in de Java-code (geen `subList(0, n)` of vergelijkbaar). De echte bottleneck is de `PayloadBudget.MAX_BYTES = 8 * 1024` (8 KB) in `ClimbPayloadBuilder`. Zoals hierboven beschreven is 8 KB al boven de harde limiet van de FR255M van ~4 KB — de huidige waarde is dus al te hoog.

Voor de **Android UI** (RouteDetailActivity → ClimbListAdapter) is er geen limiet — alle klimmen worden al getoond via de RecyclerView.

### 2.2 Wat te doen

**In de UI:** er is niets te wijzigen. De RecyclerView toont al alle klimmen.

**Voor de watch-payload:** corrigeer `PayloadBudget.MAX_BYTES` naar de werkelijke limiet van de FR255M:

```java
// WAS (te hoog voor FR255M):
public static final int MAX_BYTES = 8 * 1024;

// WORDT:
public static final int MAX_BYTES = 4 * 1024;  // FR255M limiet via transmitMessage()
```

Met 4 KB en 16 segmenten per klim passen er ~2–3 klimmen in één bericht. Als je meer klimmen naar het horloge wilt sturen, moet je de payload compacter maken (zie Plan D) of werken met meerdere berichten.

---

## Deel 3 — Extra implementatieplannen

### Plan A: Per-route instelbaar aantal segmenten (Settings-scherm)

**Doel:** de gebruiker kan per route zelf kiezen hoeveel segmenten een klim krijgt (bijv. 8, 12, 16, 20).

**Aanpak:**

1. Voeg een veld toe aan `StoredRoute`:
   ```java
   public int segmentCount = 16; // default
   ```

2. Voeg in `SettingsActivity` / het route-detail scherm een SeekBar of RadioGroup toe:
   ```
   Segmenten per klim: [8] [12] [16] [20]
   ```

3. Sla de keuze op in `StoredRoute.segmentCount` via `RouteRepository`.

4. Geef `segmentCount` door aan `Segmenter.segment()`:
   ```java
   public static List<Segment> segment(List<RoutePoint> climbPoints, int segmentCount)
   ```

5. Bij het laden van een route: als `segmentCount` is gewijzigd ten opzichte van de opgeslagen segmenten, herbereken dan alle klimmen.

**Betrokken bestanden:**
- `StoredRoute.java` — nieuw veld
- `Segmenter.java` — extra parameter
- `RouteRepository.java` — sla het veld op/laad het
- `SettingsActivity.java` of `RouteDetailActivity.java` — UI
- `SettingsViewModel.java` of `RouteDetailViewModel.java` — logica

---

### Plan B: Adaptieve segmentatie op basis van klimlengte

**Doel:** kortere klimmen krijgen minder segmenten (zodat elk segment betekenisvolle informatie bevat), langere klimmen meer. Heeft als bijkomend voordeel dat de payload kleiner blijft voor korte klimmen, wat ruimte maakt voor meer klimmen binnen de 4 KB FR255M-limiet.

**Aanpak:**

Pas `Segmenter.segment()` aan met een formule:

```java
int segmentCount;
if (totalLength < 1000) {
    segmentCount = 8;   // ~600 bytes per klim
} else if (totalLength < 3000) {
    segmentCount = 12;  // ~840 bytes per klim
} else {
    segmentCount = 16;  // ~1.170 bytes per klim
}
```

Of: `segmentCount = Math.min(16, Math.max(8, (int)(totalLength / 150)))` — 1 segment per 150 m, minimaal 8, maximaal 16.

**Betrokken bestanden:**
- `Segmenter.java` — aanpassen van de segmentatielogica
- `ClimbConstants.java` — eventueel drempelwaarden als constanten

---

### Plan C: Per-klim instelbaar aantal segmenten vanuit het ClimbDetailActivity

**Doel:** de gebruiker kan per individuele klim (niet per route) het aantal segmenten aanpassen via een dialoogvenster in `ClimbDetailActivity`.

**Aanpak:**

1. Voeg een veld toe aan `StoredClimb`:
   ```java
   public int segmentCount = 16;
   ```

2. Voeg een knop toe in `ClimbDetailActivity` naast "Rename": "Herbereken segmenten".

3. Toon een dialoog met een NumberPicker of invoerveld (4–32).

4. Sla de waarde op en herbereken de segmenten:
   ```java
   viewModel.reSegment(routeId, climbIndex, newCount);
   ```

5. De ViewModel roept `Segmenter.segment(climbPoints, newCount)` aan en slaat de route opnieuw op via `RouteRepository`.

**Betrokken bestanden:**
- `StoredClimb.java` — nieuw veld
- `ClimbDetailActivity.java` — UI-knop + dialoog
- `ClimbDetailViewModel.java` — `reSegment()`-methode
- `RouteRepository.java` — sla segmenten per klim op

---

### Plan D: Compacte binaire payload voor de FR255M (sterk aanbevolen)

**Doel:** bij 16 segmenten × meerdere klimmen past de payload binnen de harde 4 KB limiet van de FR255M. Dit plan is direct relevant gegeven het apparaat.

**Probleem met het huidige JSON-formaat:**

JSON is leesbaar maar inefficiënt. De sleutelnamen worden bij elk object herhaald:
```json
{"distance":450,"elevationGain":18,"gradient":40,"colorIndex":2}
```
→ 65 bytes voor 4 getallen die ook in 4 bytes passen als je de sleutelnamen weglaat.

**Geoptimaliseerd binair formaat per segment (4 bytes totaal):**

| Byte | Inhoud | Bereik |
|------|--------|--------|
| 0 | `gradient` als signed int8 (% × 10) | -128..127 = -12,8%..12,7% |
| 1 | `colorIndex` als uint8 | 0..5 |
| 2–3 | `distance` als uint16 in meters | 0..65.535 m |

De `elevationGain` per segment is afleidbaar: `gradient × distance / 1000`. Niet mee sturen.

**Resultaat:**
- Per segment: 4 bytes (was ~65 bytes JSON)
- Per klim met 16 segmenten: 64 bytes segmentdata + ~50 bytes metadata = ~115 bytes
- In 4 KB passen dan: ~34 klimmen

**Aanpak:**

1. Vervang in `ClimbPayloadBuilder` de JSON-serialisatie van segmenten door een `byte[]`:
   ```java
   byte[] segBytes = new byte[segments.size() * 4];
   for (int i = 0; i < segments.size(); i++) {
       StoredSegment s = segments.get(i);
       segBytes[i*4]   = (byte) toFixedPoint(s.gradient); // signed % × 10
       segBytes[i*4+1] = (byte) s.colorIndex;
       segBytes[i*4+2] = (byte) (s.distance >> 8);
       segBytes[i*4+3] = (byte) (s.distance & 0xFF);
   }
   ```

2. Pas de Connect IQ watch-app aan om het binaire formaat te decoderen.

3. Zet `PayloadBudget.MAX_BYTES` op `4 * 1024`.

**Betrokken bestanden:**
- `ClimbPayloadBuilder.java` — binaire serialisatie
- `PayloadBudget.java` — correct instellen op 4 KB
- `protocol/Segment.java` — eventueel kleinere typen
- Connect IQ watch-app — decoder aanpassen

---

### Plan E: GPS-kalibratiepunten per segmenteinde

**Doel:** het horloge kan tijdens het rijden van een klim zijn positie periodiek kalibreren door de gemeten GPS-positie te vergelijken met het verwachte segmenteinde. Dit corrigeert opgebouwde fouten in de afstandsmeting (sensorafwijking, GPS-drift) zodat de weergave van het huidige segment altijd klopt.

**Waarom is dit nodig?**

De FR255M combineert GPS met de loopsensor en barometer voor afstandsmeting. Op een klim van 5 km kan er na 2–3 km al een afwijking van 50–150 m zijn opgebouwd. Zonder kalibratie toont het horloge dan het verkeerde segment of de verkeerde gradiënt. Door bij geselecteerde segmenteinden te controleren of de GPS-positie overeenkomt met de verwachte coördinaat, kan de watch-app zijn interne "hoeveel meter ben ik in de klim" resetten.

---

**Minimum afstand tussen kalibratiepunten: 200 m**

Bij 16 segmenten is een segment bij een korte klim van 800 m maar 50 m lang — te kort om zinvol op te kalibreren. De regel is daarom: **elk segmenteinde wordt alleen als kalibratiepunt opgeslagen als de afstand tot het vorige kalibratiepunt ≥ 200 m is.** Is de segmentlengte kleiner dan 200 m, dan wordt dat segmenteinde overgeslagen en het volgende gecontroleerd.

**Concrete voorbeelden:**

| Klimlengte | Segmentlengte (÷16) | Kalibratiepunten | Stapgrootte |
|------------|---------------------|------------------|-------------|
| 800 m | 50 m | elk 4e punt → 3 punten | 200 m |
| 1.600 m | 100 m | elk 2e punt → 7 punten | 200 m |
| 3.200 m | 200 m | elk punt → 15 punten | 200 m |
| 6.400 m | 400 m | elk punt → 15 punten | 400 m |

Het eindpunt van de klim (na segment 16) is altijd een kalibratiepunt, ongeacht de afstand — dat is de definitieve reset aan het einde van de klim.

---

**Hoe de kalibratiepunten selecteren (Android-kant)**

De logica zit volledig in `Segmenter.java`. Na de bestaande segmentlus voer je een tweede pass uit die bepaalt welke segmenteinden als kalibratiepunt worden opgenomen.

De berekening van lat/lon per segmenteinde hergebruikt `interpolateLatLon()`, aangeroepen op hetzelfde `ptIdx` en `segEnd` als de bestaande `interpolateElevation()` — geen extra loop nodig.

**Nieuwe constante in `ClimbConstants.java`:**

```java
public static final int CALIBRATION_MIN_DISTANCE_M = 200;
```

**Selectielogica in `Segmenter.java` (na de segment-loop):**

```java
// Bepaal kalibratiepunten: segmenteinden met ≥ MIN_DISTANCE_M tussenruimte
List<CalibrationPoint> calibrationPoints = new ArrayList<>();
double lastCalibDist = first.distance; // startpunt van de klim

for (int i = 0; i < segments.size(); i++) {
    double segEndDist = first.distance + (i + 1) * segmentLength;
    segEndDist = Math.min(segEndDist, last.distance);

    double distSinceLast = segEndDist - lastCalibDist;

    boolean isLastSegment = (i == segments.size() - 1);

    if (distSinceLast >= ClimbConstants.CALIBRATION_MIN_DISTANCE_M || isLastSegment) {
        // Haal de ptIdx op die hoort bij deze segEndDist
        // (ptIdx is al bijgehouden in de segment-loop — sla hem op per segment)
        double[] latLon = interpolateLatLon(climbPoints, ptIdxAtSegment[i], segEndDist);
        calibrationPoints.add(new CalibrationPoint(
                (int) Math.round(segEndDist - first.distance), // afstand vanaf klimstart
                latLon[0],
                latLon[1]
        ));
        lastCalibDist = segEndDist;
    }
}
```

Om `ptIdx` per segment beschikbaar te hebben na de bestaande loop, sla je hem op in een `int[] ptIdxAtSegment` array tijdens de segment-loop.

**Nieuwe helpermethode `interpolateLatLon()` in `Segmenter.java`:**

```java
private static double[] interpolateLatLon(
        List<RoutePoint> pts, int idx, double targetDist) {
    if (idx <= 0) return new double[]{pts.get(0).lat, pts.get(0).lon};
    if (idx >= pts.size()) {
        RoutePoint p = pts.get(pts.size() - 1);
        return new double[]{p.lat, p.lon};
    }
    RoutePoint a = pts.get(idx - 1);
    RoutePoint b = pts.get(idx);
    double span = b.distance - a.distance;
    if (span <= 0) return new double[]{a.lat, a.lon};
    double t = Math.max(0, Math.min(1, (targetDist - a.distance) / span));
    return new double[]{
        a.lat + t * (b.lat - a.lat),
        a.lon + t * (b.lon - a.lon)
    };
}
```

---

**Nieuw datamodel: `CalibrationPoint`**

De kalibratiepunten worden los van de segmenten opgeslagen — segmenten beschrijven de gradiënt, kalibratiepunten beschrijven de positie. Dit scheidt de verantwoordelijkheden en houdt `Segment` compact.

**Nieuw bestand `CalibrationPoint.java` (domeinmodel):**

```java
package nl.paree.climbpro.domain.segment;

public final class CalibrationPoint {
    public final int distanceFromClimbStart; // meters
    public final double lat;
    public final double lon;

    public CalibrationPoint(int distanceFromClimbStart, double lat, double lon) {
        this.distanceFromClimbStart = distanceFromClimbStart;
        this.lat = lat;
        this.lon = lon;
    }
}
```

**Nieuw bestand `StoredCalibrationPoint.java` (persistentie):**

```java
package nl.paree.climbpro.data.route;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public final class StoredCalibrationPoint {
    public int distanceFromClimbStart;
    public double lat;
    public double lon;
}
```

**Uitbreiden `StoredClimb.java`:**

```java
// Bestaand veld:
public List<StoredSegment> segments;

// Nieuw veld:
public List<StoredCalibrationPoint> calibrationPoints; // null bij oude opgeslagen routes
```

`@JsonIgnoreProperties(ignoreUnknown = true)` staat al op `StoredClimb`, waardoor oude opgeslagen routes zonder `calibrationPoints` gewoon worden ingeladen met `null` — geen migratie nodig.

**Uitbreiden `Climb.java` (domeinmodel):**

```java
// Nieuw veld in Builder en Climb:
public final List<CalibrationPoint> calibrationPoints;
```

---

**Hoeveel kalibratiepunten verwacht je in de praktijk?**

| Klimlengte | Segmentlengte | Stap (eerste veelvoud ≥ 200 m) | Aantal punten |
|------------|---------------|-------------------------------|---------------|
| 800 m | 50 m | elk 4e (200 m) | 4 |
| 1.200 m | 75 m | elk 3e (225 m) | 5 |
| 1.600 m | 100 m | elk 2e (200 m) | 8 |
| 3.200 m | 200 m | elk 1e (200 m) | 16 |
| 6.400 m | 400 m | elk 1e (400 m) | 16 |

---

**Payload-impact voor de FR255M**

Kalibratiepunten zijn een aparte lijst naast de segmenten. In het compacte binaire formaat van Plan D kosten ze per punt:

| Byte | Inhoud | Precisie |
|------|--------|----------|
| 0–1 | `distanceFromClimbStart` als uint16 in meters | 1 m, max 65 km |
| 2–4 | Δlat t.o.v. klimstartpunt als 24-bit signed integer (Δlat × 2²³ / 90) | ~0,01 m |
| 5–6 | Δlon t.o.v. klimstartpunt als 16-bit signed integer (Δlon × 32767 / 5.0°) | ~2–3 m (bij klimmen ≤ 5° lon breed) |

→ **7 bytes per kalibratiepunt**

Bij een typische klim van 3,2 km: 16 punten × 7 bytes = 112 bytes extra per klim. Gecombineerd met Plan D (4 bytes/segment × 16 = 64 bytes) en metadata (~50 bytes) komt een klim dan op **~226 bytes**. In 4 KB passen dan nog steeds ~17 klimmen.

---

**Watch-app logica (Connect IQ / MonkeyC)**

```monkeyc
var calibIdx = 0; // index in calibrationPoints array

// Bij elke GPS-update tijdens een klim:
function onLocationUpdate(lat, lon) {
    if (calibIdx >= calibrationPoints.size()) { return; }

    var cp = calibrationPoints[calibIdx];
    var distToCalibPoint = haversine(lat, lon, cp.lat, cp.lon);

    if (distToCalibPoint < CALIBRATION_THRESHOLD_M) {
        // Kalibreer: reset de interne afstandsteller
        distanceAlongClimb = cp.distanceFromClimbStart;
        calibIdx++;

        // Update ook welk segment actief is op basis van nieuwe afstand
        updateCurrentSegment(distanceAlongClimb);
    }
}
```

`CALIBRATION_THRESHOLD_M` stel je in op 30–50 m. Door de minimumafstand van 200 m tussen punten voorkom je dat twee kalibratiepunten zo dicht bij elkaar liggen dat ze allebei binnen het threshold vallen tijdens dezelfde seconde.

---

**Betrokken bestanden:**

| Bestand | Wijziging |
|---------|-----------|
| `ClimbConstants.java` | Voeg `CALIBRATION_MIN_DISTANCE_M = 200` toe |
| `CalibrationPoint.java` | Nieuw domeinmodel |
| `StoredCalibrationPoint.java` | Nieuw persistentiemodel |
| `Segmenter.java` | Sla `ptIdx` per segment op; voeg selectielogica + `interpolateLatLon()` toe |
| `Climb.java` | Voeg `calibrationPoints` toe aan Builder en klasse |
| `StoredClimb.java` | Voeg `calibrationPoints` toe (nullable) |
| `ClimbPayloadBuilder.java` | Serialiseer kalibratiepunten als aparte byte-reeks (Plan D vereist) |
| Connect IQ watch-app | Implementeer kalibraticheck bij GPS-updates |

---

### Plan F: Instelbare klimdetectie-drempelwaarden per route

**Doel:** de gebruiker kan per route instellen hoe streng de klimdetectie is. Op dit moment zijn `MIN_CLIMB_LENGTH_M = 800`, `MIN_AVG_GRADIENT = 0.03` en `DOWNHILL_TOLERANCE_M = 20.0` globale constanten. Hierdoor worden op een vlakke route veel kleine heuvels opgepikt, en op een bergrit mogelijk twee aangrenzende klimmen samengevoegd tot één. Met instelbare drempelwaarden per route kan de gebruiker dit finetunen zonder opnieuw te synchroniseren met Strava.

**Waarom is dit nuttig in de praktijk?**

- Een vlakke fietsroute met veel rollercoaster-klimmetjes van 400 m: verhoog `MIN_CLIMB_LENGTH_M` naar 1.500 m om alleen echte klimmen te tonen.
- Een alpenroute waar een lange col met een vlak stuk ertussenin als twee klimmen wordt gezien: verhoog `DOWNHILL_TOLERANCE_M` van 20 naar 50 m zodat ze worden samengevoegd.
- Een rit met veel valse vlakten op 2%: verhoog `MIN_AVG_GRADIENT` van 3% naar 5% om alleen steile klimmen te detecteren.

---

**Aanpak**

Voeg een nieuw object `ClimbDetectionSettings` toe als onderdeel van `StoredRoute`. Bij het herberekenen van klimmen (na een sync of handmatige update) worden de instellingen van de route gebruikt in plaats van de globale constanten.

**Nieuw bestand `ClimbDetectionSettings.java`:**

```java
package nl.paree.climbpro.domain.climb;

public final class ClimbDetectionSettings {

    public static final int     DEFAULT_MIN_LENGTH_M      = 800;
    public static final double  DEFAULT_MIN_AVG_GRADIENT  = 0.03;
    public static final double  DEFAULT_DOWNHILL_TOLERANCE_M = 20.0;

    public final int    minLengthM;
    public final double minAvgGradient;
    public final double downhillToleranceM;

    public ClimbDetectionSettings(int minLengthM,
                                   double minAvgGradient,
                                   double downhillToleranceM) {
        this.minLengthM           = minLengthM;
        this.minAvgGradient       = minAvgGradient;
        this.downhillToleranceM   = downhillToleranceM;
    }

    public static ClimbDetectionSettings defaults() {
        return new ClimbDetectionSettings(
            DEFAULT_MIN_LENGTH_M,
            DEFAULT_MIN_AVG_GRADIENT,
            DEFAULT_DOWNHILL_TOLERANCE_M
        );
    }
}
```

**`ClimbDetector.detect()` krijgt een extra parameter:**

```java
// WAS:
public static List<Climb> detect(List<RoutePoint> points)

// WORDT:
public static List<Climb> detect(List<RoutePoint> points,
                                  ClimbDetectionSettings settings)
```

Alle verwijzingen naar `ClimbConstants.MIN_CLIMB_LENGTH_M`, `ClimbConstants.MIN_AVG_GRADIENT` en de hardcoded `DOWNHILL_TOLERANCE_M = 20.0` worden vervangen door `settings.minLengthM`, `settings.minAvgGradient` en `settings.downhillToleranceM`.

**`StoredRoute.java` uitbreiden:**

```java
// Nieuw veld — null bij bestaande routes, dan worden defaults gebruikt:
public StoredDetectionSettings detectionSettings;
```

Met een bijbehorend `StoredDetectionSettings.java` dat dezelfde drie velden bevat als `ClimbDetectionSettings`, maar als mutable POJO voor Jackson-serialisatie.

**`RouteDetailActivity` uitbreiden:**

Voeg een knop "Klimdetectie aanpassen" toe die een dialoog opent met drie invoervelden:

```
Minimale klimlengte:     [800] m
Minimale gemiddelde helling: [3.0] %
Afdaling tolerantie:    [20] m
```

Na opslaan roept de ViewModel `routeRepo.redetectClimbs(routeId, settings)` aan, die de route opnieuw doorloopt met de nieuwe instellingen en opslaat. Dit vervangt de bestaande `climbs`-lijst volledig.

**Betrokken bestanden:**

| Bestand | Wijziging |
|---------|-----------|
| `ClimbDetectionSettings.java` | Nieuw domeinobject |
| `StoredDetectionSettings.java` | Nieuw persistentieobject |
| `ClimbDetector.java` | Voeg `settings`-parameter toe; vervang hardcoded constanten |
| `ClimbConstants.java` | Behoud als fallback-defaults; verwijder uit `ClimbDetector` |
| `StoredRoute.java` | Voeg `detectionSettings` toe (nullable) |
| `RouteRepository.java` | Voeg `redetectClimbs(routeId, settings)` toe |
| `RouteDetailActivity.java` | UI voor het aanpassen van de instellingen |
| `RouteDetailViewModel.java` | `redetect()`-methode die repo aanroept |
| `RouteSyncWorker.java` | Geef de opgeslagen `detectionSettings` mee bij herberekening na sync |

---

### Plan G: Instelbare gradiëntkleurdrempels

**Doel:** de gebruiker kan zelf instellen bij welke gradiënt een segment van kleur wisselt. Op dit moment zijn de drempels in `GradientColor` hardcoded op 2%, 4%, 6%, 8% en 10%. Voor een beginnende fietser is 6% al een rode klim; voor een mountainbiker begint rood pas bij 15%. Door de kleurdrempels instelbaar te maken past de app zich aan het niveau van de gebruiker aan.

**Waarom is dit nuttig?**

De kleurcodering is de primaire informatie op het horloge. Als alle klimmen oranje/rood zijn omdat de drempels te laag zijn, verliest de kleurcodering zijn waarde. Omgekeerd: als een zware klimmer altijd geel ziet, is er geen zinvol onderscheid. Aanpasbare drempels maken de app bruikbaar voor een veel bredere doelgroep.

---

**Aanpak**

De huidige `GradientColor.CUTOFFS`-array is de enige plek die bepaalt welke kleurindex een segment krijgt. De aanpak is om die array vervangbaar te maken via een configuratieobject dat wordt opgeslagen in `SharedPreferences` (globaal, niet per route — kleurvoorkeur is een persoonlijke instelling).

**Nieuw bestand `GradientColorSettings.java`:**

```java
package nl.paree.climbpro.domain.segment;

public final class GradientColorSettings {

    // Standaard drempelwaarden als fracties (bijv. 0.04 = 4%)
    public static final double[] DEFAULTS = {0.02, 0.04, 0.06, 0.08, 0.10};

    // Minimale en maximale drempelwaarden die de UI toelaat
    public static final double MIN_CUTOFF = 0.01; // 1%
    public static final double MAX_CUTOFF = 0.20; // 20%

    public final double[] cutoffs; // altijd 5 waarden, oplopend

    public GradientColorSettings(double[] cutoffs) {
        if (cutoffs == null || cutoffs.length != 5) {
            throw new IllegalArgumentException("Exactly 5 cutoffs required");
        }
        this.cutoffs = cutoffs.clone();
    }

    public static GradientColorSettings defaults() {
        return new GradientColorSettings(DEFAULTS);
    }

    public int colorIndexFor(double gradient) {
        for (int i = 0; i < cutoffs.length; i++) {
            if (gradient < cutoffs[i]) return i;
        }
        return 5;
    }
}
```

**`GradientColor.forGradient()` aanpassen:**

```java
// Nieuwe overload die instellingen gebruikt:
public static int forGradient(double gradient, GradientColorSettings settings) {
    return settings.colorIndexFor(gradient);
}

// Bestaande methode blijft als fallback met default settings:
public static int forGradient(double gradient) {
    return forGradient(gradient, GradientColorSettings.defaults());
}
```

**`Segmenter.segment()` krijgt een extra parameter:**

```java
public static List<Segment> segment(List<RoutePoint> climbPoints,
                                     GradientColorSettings colorSettings)
```

Alle aanroepen van `GradientColor.forGradient(gradient)` in de segmentloop worden vervangen door `GradientColor.forGradient(gradient, colorSettings)`.

**Opslaan in `SharedPreferences`:**

De vijf drempelwaarden worden opgeslagen als kommagescheiden string (bijv. `"0.02,0.04,0.06,0.08,0.10"`) in `SharedPreferences` onder de sleutel `pref_gradient_cutoffs`. Bij het laden wordt de string geparsed; bij een ongeldige waarde worden de defaults gebruikt.

Voeg een helper toe in `SettingsViewModel`:

```java
public GradientColorSettings loadColorSettings() {
    String raw = prefs.getString("pref_gradient_cutoffs", null);
    if (raw == null) return GradientColorSettings.defaults();
    try {
        String[] parts = raw.split(",");
        double[] cutoffs = new double[5];
        for (int i = 0; i < 5; i++) cutoffs[i] = Double.parseDouble(parts[i]);
        return new GradientColorSettings(cutoffs);
    } catch (Exception e) {
        return GradientColorSettings.defaults();
    }
}
```

**UI in `SettingsActivity`:**

Voeg vijf SeekBars toe (of een tabel met vijf rijen) waarmee de gebruiker de vijf drempelwaarden instelt:

```
Kleur 1 → Kleur 2 (geel → donkergeel):    [4] %
Kleur 2 → Kleur 3 (donkergeel → oranje):  [6] %
Kleur 3 → Kleur 4 (oranje → donkeroranje):[8] %
Kleur 4 → Kleur 5 (donkeroranje → rood): [10] %
Kleur 5 → Kleur 6 (rood → donkerrood):   [12] %
```

Na het aanpassen: sla de nieuwe waarden op, en trigger een herberekening van alle segmenten voor alle opgeslagen routes via `RouteRepository.recolorAllRoutes(colorSettings)`. Dit is een lichte operatie — alleen de `colorIndex` per segment verandert, de rest van de segmentdata blijft intact.

**`RouteRepository` uitbreiden:**

```java
public void recolorAllRoutes(GradientColorSettings settings) throws IOException {
    for (RouteCatalogEntry entry : loadCatalog()) {
        StoredRoute route = loadRoute(entry.routeId);
        if (route.climbs == null) continue;
        for (StoredClimb climb : route.climbs) {
            if (climb.segments == null) continue;
            for (StoredSegment seg : climb.segments) {
                seg.colorIndex = GradientColor.forGradient(seg.gradient, settings);
            }
        }
        saveRoute(route); // alleen JSON bijwerken, geen GPX-herberekening
    }
}
```

Dit is efficiënter dan de routes opnieuw te segmenteren — alleen `colorIndex` wordt bijgewerkt.

**Payload-impact:** de `colorIndex` per segment verandert in waarde, maar niet in grootte. De binaire payload van Plan D (1 byte per `colorIndex`) past ongewijzigd. Geen aanpassing aan `ClimbPayloadBuilder` nodig.

**Betrokken bestanden:**

| Bestand | Wijziging |
|---------|-----------|
| `GradientColorSettings.java` | Nieuw configuratieobject |
| `GradientColor.java` | Voeg overload met `GradientColorSettings` toe |
| `Segmenter.java` | Voeg `colorSettings`-parameter toe; gebruik in segmentloop |
| `ClimbDetector.java` | Geef `colorSettings` door aan `Segmenter.segment()` |
| `RouteSyncWorker.java` | Laad `colorSettings` uit `SharedPreferences` en geef door |
| `SettingsActivity.java` | UI: vijf SeekBars voor drempelwaarden |
| `SettingsViewModel.java` | Laad/sla kleurdrempels op; trigger `recolorAllRoutes()` na wijziging |
| `RouteRepository.java` | Voeg `recolorAllRoutes(settings)` toe |

---

## Samenvatting: volgorde van uitvoering

| Stap | Bestand | Wijziging |
|------|---------|-----------|
| 1 | `ClimbConstants.java` | `SEGMENT_FRACTION` → `SEGMENT_COUNT = 16` |
| 2 | `Segmenter.java` | Bereken `segmentLength = totalLength / SEGMENT_COUNT`; sla `ptIdx` per segment op; voeg `interpolateLatLon()` + kalibratiepuntselectie toe |
| 3 | `ClimbConstants.java` | Voeg `CALIBRATION_MIN_DISTANCE_M = 200` toe |
| 4 | `CalibrationPoint.java` + `StoredCalibrationPoint.java` | Nieuwe bestanden aanmaken |
| 5 | `Climb.java` + `StoredClimb.java` | Voeg `calibrationPoints` toe |
| 6 | `PayloadBudget.java` | Corrigeer naar `4 * 1024` (FR255M werkelijke limiet) |
| 7 | `RouteRepository.java` | Voeg migratievlag toe voor bestaande routes |
| 8 | (sterk aanbevolen) Plan D — compacte binaire payload; zonder dit passen er maar 2–3 klimmen in 4 KB en is er geen ruimte voor kalibratiepunten |
| 9 | Plan E — kalibratiepunten per segmenteinde; bouwt voort op stap 2 t/m 5; vereist Plan D vanwege payload-grootte |
| 10 | (optioneel) Plan A, B of C naar keuze |

De UI (RecyclerView, ClimbProfileView, ClimbSegmentAdapter) hoeft **niet** aangepast te worden voor de kalibratiepunten — die zijn puur voor de watch-app logica en worden niet getoond in de Android UI.

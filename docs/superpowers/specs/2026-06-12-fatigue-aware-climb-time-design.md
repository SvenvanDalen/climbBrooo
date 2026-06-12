# Vermoeidheids-bewuste klimtijd — Ontwerp

**Datum:** 2026-06-12
**Scope:** Alleen telefoon (klim-detailscherm). Geen wijziging aan `protocol/schema.json` of de Connect IQ / Monkey C-kant.

## Probleem

De huidige `ClimbTimeEstimator` lost per klim de Critical-Power-formule `P = FTP + W'/t`
op met een **volle** anaerobe batterij (`W' = 20 kJ`), alsof de renner élke klim vers
begint. De gebruiker wil dat de geschatte klimsnelheid rekening houdt met de inspanning
van **de rest van de route**, in twee richtingen:

1. **Vermoeidheid vooraf** — je bent al moe van het stuk route tot aan de klim.
2. **Reserveren voor erna** — je moet na de klim nog door, dus je mag niet alles verbranden.

## Gekozen aanpak: globale W'-balans met gedeelde klim-agressiviteit (Aanpak B)

Alle klimmen op de route worden gereden op `FTP + x` watt (één gedeelde absolute
offset `x` boven de drempel). Op de niet-klim stukken rijdt de renner op een instelbare
rit-intensiteit (`% FTP`); daar herstelt of daalt de W'-balans. We simuleren de hele
route en zoeken via bisectie de hoogste `x` waarbij de W'-balans nergens onder een
kleine reserve-vloer zakt — dus met een buffer over de finish.

De getoonde klim gebruikt dan `FTP + x*`.

**Degeneratie-eigenschap:** een vrijwel-verse klim aan het begin met niets zwaars erna
levert `x·t_klim ≤ W'max`, d.w.z. exact de huidige formule `P = FTP + W'/t`. Aanpak B is
dus een nette generalisatie van het bestaande gedrag, niet een breuk ermee.

### Waarom dit beide eisen dekt

- **Vermoeidheid vooraf:** klimmen dieper in een zware route starten met een lagere
  W'-balans (door eerdere depletie), dus `x` wordt lager → trager. Automatisch.
- **Reserveren voor erna:** omdat de balans tot de finish ≥ reserve-vloer moet blijven,
  beperken latere zware stukken hoe hard je nú mag gaan. Automatisch.

## Architectuur

### Nieuwe en aangepaste units

| Unit | Laag | Verantwoordelijkheid |
|---|---|---|
| `RouteTile` (nieuw) | `domain.power` | Onveranderlijk waarde-object: `distanceMeters` (int), `gradient` (double, fractie), `surfaceType` (int), `climbIndex` (int; `-1` = niet-klim, `≥0` = welke klim). |
| `WPrimeBalance` (nieuw) | `domain.power` | W'-balans integrator. Per tile gesloten-vorm. Puur, los testbaar. |
| `RouteAwareClimbEstimator` (nieuw) | `domain.power` | Bisecteert `x`, simuleert de route met `WPrimeBalance`, geeft `ClimbTimeEstimate` voor de doelklim op `FTP+x*`. |
| `RouteEffortProfileBuilder` (nieuw) | `service` | `StoredRoute` → `List<RouteTile>`. |
| `ClimbTimeEstimator` (refactor) | `domain.power` | Extraheer `estimateAtFixedPower(...)`; bestaande `estimate()` en de nieuwe estimator delen die methode (DRY). |
| `RiderProfile` (uitbreiden) | `domain.power` | Veld `rideIntensityPct`; 4-arg constructor + bestaande 3-arg met default. |
| `RiderProfileRepository` | `data.rider` | Nieuwe pref `rider_ride_intensity_pct`. |
| `SettingsViewModel` / `SettingsActivity` / `activity_settings.xml` | `ui.settings` | Veld "Rit-intensiteit (% FTP)". |
| `ClimbDetailViewModel` | `ui.climbs` | Bouwt tiles + roept de route-aware estimator aan; fallback naar oude estimator. |

### Laag-discipline

`domain.power` blijft puur (geen afhankelijkheid op `data.route`). `RouteTile` is het
domein-invoertype; `RouteEffortProfileBuilder` leeft in `service` (mag `data` + `domain`
zien) en vertaalt `StoredRoute` naar tiles, net zoals `ClimbPayloadBuilder` dat doet.

## Dataflow

```
ClimbDetailViewModel.loadClimb(routeId, climbIndex)
  → routeRepo.loadRoute(routeId)            (StoredRoute r, al aanwezig)
  → RouteEffortProfileBuilder.build(r)      → List<RouteTile>
  → RouteAwareClimbEstimator.estimate(tiles, climbIndex, profile, intensityFraction)
                                            → ClimbTimeEstimate (doelklim, op FTP+x*)
  → bestaande UI: "Geschatte tijd: … · %.0f W"   (W = FTP + x*)
```

Draait op de bestaande achtergrond-executor in `ClimbDetailViewModel`. De UI-laag en
het `ClimbTimeEstimate`-type veranderen niet.

## Algoritme (`RouteAwareClimbEstimator`)

Gegeven: `tiles`, `targetClimbIndex`, `profile`, `intensityFraction`.

Afgeleid:
- `CP = profile.ftpWatts`
- `W'max = PowerConstants.W_PRIME`
- `mass = profile.totalMassKg()`
- `P_nonclimb = intensityFraction · CP`
- Per tile vooraf: `crr[i] = SurfaceRollingResistance.crr(tile.surfaceType)`

**Simulatie bij gegeven `x` →** `simulateMinBalance(x)`:
1. `WPrimeBalance bal = new WPrimeBalance(W'max, CP)`  (start vol).
2. `double minBalance = W'max`.
3. Voor elke tile in volgorde:
   - `power = tile.climbIndex >= 0 ? CP + x : P_nonclimb`
   - `v = PowerSpeedSolver.speedMetersPerSecond(power, mass, tile.gradient, crr)`
   - `dt = tile.distanceMeters / v`
   - `bal.applyInterval(power, dt)`
   - `minBalance = min(minBalance, bal.current())`
4. Return `minBalance`.

**Bisectie op `x`:**
- `x_lo = 0` (altijd haalbaar: klimmen op CP → geen depletie boven CP).
- `x_hi = PowerConstants.X_MAX_OFFSET_W` (= 600).
- Reserve-vloer: `reserve = PowerConstants.RESERVE_FRACTION · W'max`.
- Herhaal `PowerConstants.BISECTION_ITERATIONS` (= 40) keer:
  `mid = (x_lo + x_hi)/2`; als `simulateMinBalance(mid) ≥ reserve` dan `x_lo = mid`
  anders `x_hi = mid`.
- `x* = x_lo` (grootste geteste haalbare offset).

Monotoniciteit: depletie van een klim `≈ x · t_klim(x)`. Met `t_klim ≈ L/(CP+x)` is dat
`≈ L·x/(CP+x)`, stijgend in `x`. Dus `minBalance` daalt monotoon in `x` → bisectie geldig.

**Resultaat:** verzamel de tiles met `climbIndex == targetClimbIndex` (in volgorde),
en bereken hun per-segment tijden op vermogen `CP + x*` via
`ClimbTimeEstimator.estimateAtFixedPower(dist, grad, crr, mass, CP + x*)`. De
`ClimbTimeEstimate` krijgt `assumedPowerWatts = CP + x*`.

### W'-balans model (`WPrimeBalance.applyInterval(power, dt)`)

- **Depletie** (`power > CP`): `W' -= (power − CP) · dt`, daarna clamp `≥ 0`.
- **Herstel** (`power ≤ CP`): `W' = W'max − (W'max − W') · exp(−dt / τ)`,
  met `τ = PowerConstants.W_PRIME_TAU_SECONDS` (= 400 s). Asymptotisch naar `W'max`
  (clamp `≤ W'max` volgt vanzelf).

Dit is de gangbare Skiba-vereenvoudiging met één vaste hersteltijdconstante.

## Niet-klim tiles (`RouteEffortProfileBuilder`)

Invoer `StoredRoute r`; uitvoer dekt `[0, totalDistance]` in volgorde.

- **Klim-tiles:** voor elke klim `i` (in volgorde van `startDistance`), emit elk
  `StoredSegment` als tile: `distanceMeters = seg.distance`, `gradient = seg.gradient`,
  `surfaceType = seg.surfaceType`, `climbIndex = i`.
- **Niet-klim gaten:** ranges in `[0, totalDistance]` die geen enkele klim dekt. Voor
  elk gat `[a, b]`: loop over opeenvolgende route-punten uit `r.distances[]`/
  `r.elevations[]`, geknipt op `[a, b]`. Per deel-stuk: `distanceMeters = clip-lengte`,
  `gradient = (Δelevatie / Δhorizontaal)` (0 als `Δhorizontaal == 0`),
  `surfaceType` volgens precedentie hieronder, `climbIndex = -1`.
- **Oppervlak-precedentie** voor een niet-klim tile (op zijn startafstand `d`):
  1. een `StoredSurfaceSection` die `d` dekt → die `surfaceType`;
  2. anders een `StoredFlatSegment` die `d` dekt → die `surfaceType`;
  3. anders `SurfaceType.ASPHALT`.

**Robuustheid / fallback:** als `r.distances == null` of `r.elevations == null` of de
arrays korter zijn dan 2 punten, kan geen valide effort-profiel worden gebouwd →
`build()` retourneert `null`. `ClimbDetailViewModel` valt dan terug op de bestaande
`ClimbTimeEstimator.estimate(...)` (vers-per-klim gedrag). Ontbrekende elevatie binnen
een overigens valide route → gradient 0 voor dat stuk.

## Constants (nieuw, in `PowerConstants`)

```java
public static final double W_PRIME_TAU_SECONDS  = 400.0; // herstel-tijdconstante
public static final double RESERVE_FRACTION      = 0.10;  // buffer over de finish
public static final double X_MAX_OFFSET_W        = 600.0; // bisectie-bovengrens
public static final int    BISECTION_ITERATIONS  = 40;
```

In `RiderProfile`:
```java
public static final int RIDE_INTENSITY_MIN_PCT     = 40;
public static final int RIDE_INTENSITY_MAX_PCT     = 95;
public static final int DEFAULT_RIDE_INTENSITY_PCT  = 65;
```

## Instelling: rit-intensiteit

- `RiderProfile` krijgt veld `int rideIntensityPct`.
  - Nieuwe constructor `RiderProfile(ftp, riderKg, bikeKg, rideIntensityPct)`.
  - Bestaande `RiderProfile(ftp, riderKg, bikeKg)` blijft en delegeert met
    `DEFAULT_RIDE_INTENSITY_PCT` → bestaande tests/oproepen compileren ongewijzigd.
  - `isComplete()` ongewijzigd (intensiteit heeft altijd een geldige default).
  - Helper `rideIntensityFraction()` = `clamp(rideIntensityPct, MIN, MAX) / 100.0`.
- `RiderProfileRepository`: pref-key `rider_ride_intensity_pct` (int), load met default
  `DEFAULT_RIDE_INTENSITY_PCT`, save schrijft het mee.
- `SettingsViewModel.saveRiderProfile(...)`: extra parameter `rideIntensityPct`.
- `SettingsActivity` + `activity_settings.xml`: EditText "Rit-intensiteit (% FTP)"
  (number), getoond/ingelezen naast FTP/gewichten; geklemd op 40–95 bij opslaan.

## Bewuste modelkeuzes en grenzen

- **Uniforme `FTP+x` over alle klimmen:** een korte steile klim toont niet de extreme
  piek die hij geïsoleerd zou toelaten. Dat is de prijs voor een pacing die garandeert
  dat je de hele route overleeft.
- **Eén vaste hersteltijdconstante τ** (niet intensiteits-afhankelijk) — bewuste
  vereenvoudiging van Skiba's W'-herstel.
- **Reserve over de finish = 10% van W'max** (≈ 2 kJ) i.p.v. exact leeg; tunebaar via
  `RESERVE_FRACTION`.
- **Niet-klim power = constante rit-intensiteit**; afdalingen lopen tegen `MAX_SPEED_MPS`
  (bestaande clamp in `PowerSpeedSolver`), wat een korte tijd → weinig W'-herstel geeft.
- Geen wijziging aan `protocol/schema.json` of de watch; het wire-formaat en
  `ClimbTimeEstimate` blijven gelijk.

## Teststrategie (hoofdlijnen; details in het plan)

- `WPrimeBalanceTest`: depletie boven CP, exponentieel herstel onder CP, clamps `[0, max]`.
- `RouteAwareClimbEstimatorTest`: degeneratie (enkele klim begin route ≈ oude estimate);
  zelfde klim later op een zware route is trager / lager vermogen; hogere rit-intensiteit
  → minder herstel → trager op latere klim; reserve-vloer wordt gerespecteerd; monotone
  bisectie convergeert.
- `RouteEffortProfileBuilderTest`: klim-tiles 1-op-1 met segmenten; niet-klim gradiënten
  uit elevaties; oppervlak-precedentie; fallback `null` bij ontbrekende arrays.
- `RiderProfileTest` / `RiderProfileRepositoryTest`: default 65, round-trip, clamp.
- Bestaande `ClimbTimeEstimatorTest` blijft groen na de refactor.
```

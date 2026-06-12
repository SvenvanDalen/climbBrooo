# Ondergrond-stukken op de kaart — Design

**Datum:** 2026-06-12
**Status:** Goedgekeurd ontwerp, klaar voor implementatieplan

## Doel

De handmatig ingevoerde **Ondergrond-stukken** (`StoredSurfaceSection`) zichtbaar maken op de
bestaande kaart in het route-detailscherm, gekleurd per ondergrondtype, zodat de gebruiker in
één oogopslag ziet wáár op de route elk stuk ligt. Op een stuk tikken toont de naam + het type.

Dit betreft **uitsluitend** de via "Ondergrond-stukken" volledig zelf ingevoerde stukken
(`StoredSurfaceSection`, vrije start/eind-km). De automatisch gedetecteerde vlakke segmenten
(`StoredFlatSegment`) vallen buiten scope — die hebben al coördinaten en een zoom-actie via de
lijst.

## Probleem

Een `StoredSurfaceSection` slaat alleen `startDistance` en `endDistance` op (integer meters
vanaf routebegin) — **geen coördinaten**. De `StoredRoute` heeft wél parallelle arrays
`distances[]`, `lats[]`, `lons[]`. Elke start/eind-meter is dus af te leiden naar punten op de
routelijn. De kaart (`org.osmdroid`) bestaat al in `RouteDetailActivity` en tekent de route nu
als één blauwe `Polyline`.

## Scope

**In scope:**
- Gekleurde overlay-lijn per `StoredSurfaceSection`, getekend bovenop de blauwe route.
- Kleur per ondergrondtype.
- Op een overlay tikken toont naam + ondergrond-label (Toast).

**Uit scope (expliciet):**
- Gedetecteerde vlakke segmenten (`StoredFlatSegment`) op de kaart kleuren.
- Wijzigingen aan opslag, ViewModel-logica of het wire-payload naar de horloge.
- Bewerken/verwijderen vanaf de kaart (blijft via de "Ondergrond-stukken"-knop).
- Labels permanent op de kaart tekenen (alleen on-tap).

## Architectuur

Puur een **weergavelaag op de telefoon**. Geen nieuwe persistente data, geen schema- of
payload-wijziging. Datastroom blijft: `RouteDetailViewModel.route()` levert de `StoredRoute`
(inclusief `surfaceSections`) → `RouteDetailActivity.drawRoute(route)` tekent route + overlays.

### Component 1 — `SurfaceSectionGeometry` (nieuw, pure helper)

`android/app/src/main/java/nl/paree/climbpro/domain/route/SurfaceSectionGeometry.java`

Het hart van de feature: zet een meterbereik om naar routepunten. Puur Java zodat het met
gewone JUnit (geen Robolectric/Android) te testen is — daarom geeft het `double[]`-paren terug,
niet osmdroid `GeoPoint`.

```java
/**
 * Geeft de routepunten {lat, lon} die binnen het meterbereik [startM, endM] vallen.
 * - distances/lats/lons zijn parallelle arrays; distances is oplopend in meters.
 * - startM/endM worden geclamped op [0, routeLengte].
 * - Bevat alle routevertices waarvan de cumulatieve afstand in [start, end] ligt.
 * - Als er minder dan 2 vertices binnen het bereik vallen, worden de vertices die
 *   het dichtst bij start en eind liggen toegevoegd zodat er altijd ≥ 2 punten zijn
 *   (een tekenbare lijn), tenzij de route zelf < 2 punten heeft.
 * Retourneert een lege lijst als distances/lats/lons null of leeg zijn, of als de
 * arraylengtes niet kloppen.
 */
static List<double[]> pointsBetween(double[] distances, double[] lats, double[] lons,
                                    int startM, int endM)
```

Gedrag in detail:
- Null/leeg of inconsistente lengtes (`lats.length != lons.length` of `!= distances.length`)
  → lege lijst.
- Clamp: `start = clamp(startM, 0, last)`, `end = clamp(endM, start, last)` waar
  `last = distances[distances.length-1]`.
- Verzamel elke index `i` waarvoor `distances[i] >= start && distances[i] <= end`, in volgorde,
  als `new double[]{lats[i], lons[i]}`.
- Levert dit < 2 punten op (bijv. een kort stuk tussen twee vertices), voeg de vertex toe die
  het dichtst bij `start` ligt en die het dichtst bij `end` ligt (kan dezelfde zijn → dan blijft
  het 1 punt en tekent er niets, acceptabel voor een ontaard kort stuk).

### Component 2 — `SurfaceColorPalette` (nieuw)

`android/app/src/main/java/nl/paree/climbpro/ui/routes/SurfaceColorPalette.java`

Kleur per ondergrondtype, los van `SegmentColorPalette` (dat is voor klim-gradiënten) zodat de
twee betekenissen niet door elkaar lopen. Geïndexeerd op `SurfaceType`-constante (0..5).

| Index | Type        | Kleur     |
|-------|-------------|-----------|
| 0     | Asfalt      | `#616161` (donkergrijs) |
| 1     | Gravel      | `#A1887F` (zandbruin)   |
| 2     | Onverhard   | `#795548` (donkerbruin) |
| 3     | Kasseien    | `#7E57C2` (paars)       |
| 4     | Mixed       | `#26A69A` (teal)        |
| 5     | Onbekend    | `#9E9E9E` (grijs)       |

Allemaal duidelijk te onderscheiden van de blauwe routelijn (`Color.BLUE`). API:
`static int toColor(int surfaceType)` — clampt via `SurfaceType.fromInt` naar 0..5.

De kleuren worden gedefinieerd als rauwe `0xFFRRGGBB` int-literals (bijv. `0xFF616161`), **niet**
via `Color.parseColor`, zodat de palette zonder Android-stub (gewone JUnit) testbaar is — net als
de geometrie-helper.

### Component 3 — `RouteDetailActivity.drawRoute` (wijzigen)

Na het tekenen van de blauwe route-`Polyline` (en vóór `zoomToBoundingBox`/`invalidate`):

- Voor elke `StoredSurfaceSection s` in `route.surfaceSections` (als niet null):
  - `List<double[]> pts = SurfaceSectionGeometry.pointsBetween(route.distances, route.lats,
    route.lons, s.startDistance, s.endDistance);`
  - Sla over als `pts.size() < 2`.
  - Bouw een `Polyline`, zet punten om naar `GeoPoint`, kleur `SurfaceColorPalette.toColor(
    s.surfaceType)`, breedte `12f` (route is `5f`, dus de overlay valt op).
  - Zet een `Polyline.OnClickListener` die een Toast toont:
    `(naam != null ? naam : "(naamloos)") + " · " + SURFACE_LABELS_NL[SurfaceType.fromInt(
    s.surfaceType)]` en `return true`.
  - Voeg de overlay ná de route toe (`getOverlays().add(overlay)`) zodat hij bovenop ligt.

`SURFACE_LABELS_NL` bestaat al als constante in `RouteDetailActivity`. De bestaande
`getOverlays().clear()` aan het begin van `drawRoute` ruimt oude overlays op bij elke herteken.

## Error handling / edge cases

- **Ontbrekende routegeometrie** (`distances`/`lats`/`lons` null of leeg): helper geeft lege
  lijst → geen overlays, route tekent ongewijzigd. De bestaande null-check vooraan `drawRoute`
  dekt de route zelf al.
- **Stuk buiten routegrens**: geclamped in de helper.
- **Stuk korter dan één vertex-interval / ontaard (start≈end)**: < 2 punten → overgeslagen, geen
  crash.
- **Geen `surfaceSections`** (null of leeg): gedraagt zich exact als nu (alleen de route).
- **Overlappende stukken**: worden los getekend in lijstvolgorde; later toegevoegde liggen
  bovenop. Geen speciale samenvoeglogica (YAGNI).

## Testen

- **`SurfaceSectionGeometry`** — gewone JUnit (geen Robolectric):
  - punten binnen bereik worden geretourneerd, in volgorde;
  - clamp op routegrenzen (start < 0, end > lengte);
  - null/leeg/inconsistente arrays → lege lijst;
  - kort stuk tussen twee vertices → fallback geeft ≥ 2 punten zolang de route ≥ 2 vertices heeft;
  - lat/lon van de juiste indices.
- **`SurfaceColorPalette`** — gewone JUnit: elke index 0..5 geeft de verwachte `0xFFRRGGBB`-int;
  out-of-range clampt naar Onbekend.
- **`RouteDetailActivity.drawRoute`** — geen geautomatiseerde test (UI/osmdroid-overlay);
  handmatig geverifieerd: open route met ≥ 1 ondergrond-stuk → gekleurd stuk verschijnt op de
  juiste plek; tik erop → Toast met naam + type; route zonder stukken tekent ongewijzigd.

## Bestanden

- Nieuw: `android/app/src/main/java/nl/paree/climbpro/domain/route/SurfaceSectionGeometry.java`
- Nieuw: `android/app/src/main/java/nl/paree/climbpro/ui/routes/SurfaceColorPalette.java`
- Wijzigen: `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteDetailActivity.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/domain/route/SurfaceSectionGeometryTest.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/ui/routes/SurfaceColorPaletteTest.java`
</content>

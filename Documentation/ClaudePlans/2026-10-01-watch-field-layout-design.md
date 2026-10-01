# Horloge-velden instelbaar vanaf de telefoon — design

Datum: 2026-10-01 · Branch: `feat/watch-field-layout`

## Doel

De gebruiker kiest in de Android-app per vak welke waarde de ClimbPro-datafield
(`garmin/`) toont op de pagina **HUIDIGE KLIM**. Zonder keuze blijft alles exact
zoals nu.

Buiten scope: de next-climb-preview, de klimsamenvatting, `garmin-surface`,
`garmin-onboard`, `garmin-widget`, en de bestaande Garmin Connect-instellingen
(darkTheme, largeTextMode, colorMode, climbAlertDistinctTone) — die blijven waar ze zijn.

## Vakken

| Index | Vak            | Positie (huidig)            | Huidige inhoud                    |
|-------|----------------|-----------------------------|-----------------------------------|
| 0     | links          | `x=32`, `y=h*0.72`, links   | rest-afstand                      |
| 1     | midden         | `x=w/2`, `y=h*0.72`         | rest-hoogtemeters                 |
| 2     | rechts         | `x=w-32`, `y=h*0.72`, rechts| stijging huidig segment           |
| 3     | regel 4        | `x=w/2`, `y=h*0.80`, XTINY  | intervalblok, anders VAM          |
| 4     | onderste regel | `x=w/2`, `y=h*0.88`         | vs PR / vs plan, anders ETA       |

Grote-tekstmodus (#82) verbergt, net als nu, vak 1 en vak 3 — behalve dat een
intervalblok in vak 3 zichtbaar blijft (huidig gedrag, #180).

## Metriekcodes (wire + Java + Monkey C, één tabel)

| Code | Naam          | Weergave                                                     |
|------|---------------|--------------------------------------------------------------|
| 0    | REM_DIST      | rest-afstand klim (`formatDist`)                             |
| 1    | REM_ELEV      | rest-hoogtemeters `123m↑`                                    |
| 2    | CUR_GRAD      | stijging huidig segment `7.4%`                               |
| 3    | AVG_GRAD      | gemiddelde stijging klim `ø6.1%` (uit `ag`)                  |
| 4    | VAM           | middenvakken: `VAM 900/1100`; zijvakken: alleen gemiddelde   |
| 5    | ETA           | `ETA 12:34`                                                  |
| 6    | GHOST         | `vs PR` / `vs plan` delta met bestaande kleuren; anders `--` |
| 7    | BLOCK         | intervalblok-regel met zonekleur; zonder blok `--`           |
| 8    | SPEED         | km/u, 1 decimaal                                             |
| 9    | HEART_RATE    | `142bpm`                                                     |
| 10   | POWER         | `252W`                                                       |
| 11   | CADENCE       | `88rpm`                                                      |
| 12   | ELAPSED       | timertijd `h:mm:ss`                                          |
| 13   | EMPTY         | niets                                                        |
| 14   | AUTO_ROW4     | huidig gedrag regel 4: BLOCK als de klim een blok heeft, anders VAM |
| 15   | AUTO_BOTTOM   | huidig gedrag onderste regel: GHOST als er een referentie is, anders ETA |

Ontbrekende sensorwaarde (geen HR-/vermogens-/cadansmeter, `null` in `Activity.Info`)
→ `--`. Default-layout: `[0, 1, 2, 14, 15]` (= het huidige scherm).

## Wire-formaat

Optionele top-level sleutel `lay` in het bestaande v3-datafieldbericht:

```json
"lay": [0, 1, 2, 14, 15]
```

- Precies 5 integers, elk `0..15`. Schema: `minItems`/`maxItems` 5, `minimum` 0, `maximum` 15.
- Weggelaten als de gebruiker de default heeft (geen extra bytes voor wie niets wijzigt).
- Watch: ontbrekend, verkeerde lengte of geen array → volledige default; een
  individueel onbekend of niet-integer element → default voor dát vak.
- Geen versie-bump: `lay` is optioneel en oudere watch-builds negeren onbekende sleutels.
- Werkt in route- én radiusmodus (zelfde bericht).
- Wordt via `active_payload` in `Storage` bewaard → overleeft herstart van de datafield.

## Telefoon

- **Domein** `domain/watch/WatchFieldLayout` — de codes als constanten, default-array,
  `normalize(int[])` (lengte/bereik → default), `isDefault()`. Geen Android-afhankelijkheden.
- **Opslag** `data/watch/WatchFieldLayoutStore` — `SharedPreferences`, één string
  `"0,1,2,14,15"`. Kapotte waarde → default. (Instelling, geen routedata — de
  JSON-bestandenregel geldt voor routes.)
- **Payload** `ClimbPayloadBuilder` voegt `lay` toe als de layout niet default is.
- **UI** nieuw scherm **Horloge-velden**, bereikbaar vanuit `SettingsActivity`:
  5 dropdowns (vaknaam + Nederlandse metriekenamen), knop **Standaard herstellen**,
  knop **Opslaan**. Opslaan → store schrijven → actief bericht opnieuw naar de
  datafield sturen via de bestaande sync-route. Niet verbonden → Toast
  "Wordt meegestuurd bij de volgende sync"; de waarde gaat mee met de eerstvolgende sync.

## Horloge (`garmin/`)

- `ClimbData.layout` (array van 5, default bij init en bij elk bericht zonder `lay`).
- `CommListener.parseLayout(msg)` — pure functie, alle guards hierboven.
- `compute()` leest daarnaast `currentHeartRate` en `currentCadence` (met `has`-check),
  zoals nu al voor snelheid en vermogen.
- `drawActiveClimb` tekent per vak via `drawSlot(dc, data, ci, slotIdx, code, ...)`;
  de tekstopbouw zit in een pure `metricText(code, ..., wide)` zodat tests hem kunnen
  aanroepen. GHOST en BLOCK houden hun eigen tekenfuncties (kleur).
- Geen extra redraws: rendert in de bestaande `onUpdate`.

## Protocol-lockstep (verplicht samen, CLAUDE.md)

`protocol/schema.json`, `protocol/schema.md`, een voorbeeld in `protocol/examples/`
met `lay`, `ClimbPayloadBuilder`, `CommListener.mc`, plus `Documentation/ARCHITECTURE.md`
en `README.md`.

## Testen

- JVM: `ProtocolRoundTripTest` (voorbeeld + live builderoutput met `lay` valideren
  tegen schema), `WatchFieldLayoutTest` (normalize/default/serialisatie),
  `ClimbPayloadBuilder`-test (`lay` alleen aanwezig als niet-default).
- Monkey C: tests voor `parseLayout` (missend, verkeerde lengte, onbekende code) en
  `metricText` (`--` bij ontbrekende sensor). Geschreven maar **niet** in de simulator
  gedraaid (gebruikersvoorkeur); `MonkeyCSourceGuard` draait wel mee in `./gradlew test`.

## Foutgevallen

- Bericht zonder `lay` (oude telefoon-app) → default-scherm.
- Telefoon met nieuwe app, horloge met oude datafield → `lay` genegeerd, default-scherm.
- Kapotte opgeslagen voorkeur → default.

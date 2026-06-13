# Climb Logbook — Klim-records & historie

**Datum:** 2026-06-13
**Status:** Ontwerp (ter review)
**Scope:** Alleen de Android-app. Geen wijziging aan de watch, het wire-protocol of `protocol/schema.json`.

## Samenvatting

Voor elke bekende klim toont de app je eerdere pogingen uit je Strava-ritten, je
snelste tijd (PR), en een trend over tijd. Alle berekening gebeurt phone-side door
gereden Strava-activiteiten te matchen tegen de klimgeometrie die de app al bezit.
De Garmin-watch en het sync-payloadformaat blijven volledig ongewijzigd.

## Motivatie

De app weet alles van klims als *plan* (route-detail, segmenten, pacing) maar niets
van hoe je ze daadwerkelijk reed. Strava-auth en route-geometrie zijn al aanwezig;
door ritten op te halen en tegen bekende klims te leggen ontstaat klim-gerichte
historie zonder de watch te raken.

## Niet-doelen (v1)

- Geen segment-voor-segment vergelijking tussen pogingen.
- Geen Strava-segment-leaderboards of vergelijking met andere atleten.
- Geen automatische achtergrond-sync van ritten (v1 = handmatige knop).
- Geen zware grafieken; een kleine trend-sparkline volstaat.
- Geen wijziging aan opgeslagen routes, klims of het watch-protocol.

## Dataflow

1. **Ophalen** — een nieuwe Strava-call `listActivities()` haalt ritten op. De
   **eerste sync pakt alle ritten van de afgelopen 12 maanden** (`after` =
   nu − 1 jaar), gepagineerd. Per relevante rit haalt `getActivityStreams()` de
   streams `latlng` en `time` op (en optioneel `altitude`/`watts` indien
   beschikbaar). Daarna incrementeel: alleen ritten nieuwer dan de laatste sync.
2. **Matchen** — `ClimbAttemptMatcher` legt elke activity-track langs elke bekende
   klim. Per klim: vind het GPS-sample dichtbij de klim-start en dichtbij het
   klim-eind (hergebruik `NearestPointFinder`), en valideer dat de tussen die twee
   afgelegde afstand ≈ de klimlengte is. Mismatch (kruisende weg, gedeeltelijk,
   omgekeerd) wordt verworpen of als partieel gemarkeerd en telt niet als PR.
3. **Afleiden** — uit de twee timestamps volgt de **klimtijd**; uit de streams
   gemiddelde snelheid, en VAM/vermogen indien aanwezig.
4. **Opslaan** — resultaten in `climb_attempts.json` (zelfde JSON-file-aanpak als
   routes onder `getFilesDir()`), gekoppeld via een stabiele `ClimbIdentity`.
5. **Tonen** — de UI leest dat bestand; geen herberekening bij elk openen.

## Klim-identiteit

Klims worden nu per route opgeslagen (`StoredClimb` binnen `StoredRoute`). Voor
records moet "dezelfde klim" herkend worden los van de route. `ClimbIdentity` leidt
een stabiele sleutel af uit afgeronde start-lat/lon (coördinaat-bucket) plus een
lengte-bucket. Zo valt dezelfde klim die in meerdere routes voorkomt samen op één
record, zonder schema-wijziging aan bestaande opslag.

## Componenten (allemaal nieuw, afgebakend)

| Component | Laag | Verantwoordelijkheid | Afhankelijkheden |
|---|---|---|---|
| `StravaActivitiesRepository` | data/strava | Ophalen + cachen van activities en hun streams; incrementeel sinds laatste sync; dedupe op activity-id. | `StravaApiClient` (uitgebreid), `StravaAuthRepository` |
| `ClimbAttemptMatcher` | domain/matching | Pure functie: activity-track + klimgeometrie → `ClimbAttempt` of niets. Lengte-validatie. | `NearestPointFinder` |
| `ClimbIdentity` | domain/climb | Stabiele sleutel uit start-coördinaat + lengte. | — |
| `ClimbAttempt` | domain/climb | Immutable value object: identity, datum, klimtijd, gem. snelheid, optioneel VAM/watt, partieel-vlag, bron-activity-id. | — |
| `ClimbAttemptRepository` | data/route | `climb_attempts.json` lezen/schrijven; PR afleiden per identity. | Jackson |
| `ClimbLogbookActivity` + `ClimbLogbookViewModel` | ui/climbs | Overzicht van alle bekende klims met PR en aantal pogingen. | repositories |
| Uitbreiding `ClimbDetailViewModel` / `ClimbDetailActivity` | ui/climbs | Historie-blok: PR, lijst pogingen, trend-sparkline. | `ClimbAttemptRepository` |

### Strava API-uitbreiding

`StravaApiClient` krijgt erbij:

- `GET athlete/activities?page&per_page&after` → lijst van activity-samenvattingen
  (`after` = epoch van 12 maanden geleden bij eerste sync; laatste sync-tijd daarna).
- `GET activities/{id}/streams?keys=latlng,time,altitude,watts&key_by_type=true`
  → streams voor matching.

## UI

- **Klim-detail (`ClimbDetailActivity`)**: nieuw "Historie"-blok onder het bestaande
  profiel/segment-blok. Bovenaan de PR-tijd, daaronder pogingen (datum, tijd,
  Δ t.o.v. PR) en een kleine trend-sparkline.
- **Logboek (`ClimbLogbookActivity`)**: nieuw scherm vanuit het hoofdmenu; alle
  bekende klims gesorteerd op laatst gereden / meeste pogingen, elk met PR en aantal
  pogingen. Tik door → klim-detail.
- **Sync-knop** "Ritten ophalen uit Strava" met dezelfde status/retry-feedback als de
  bestaande route-sync. Achtergrond-ophalen via WorkManager kan later; v1 is
  handmatig.

## Edge cases

- **Gedeeltelijke/omgekeerde rit over de klim** → lengte-validatie verwerpt of
  markeert als partieel; telt niet als PR.
- **Ontbrekende `time`-stream** → geen klimtijd af te leiden; poging overslaan.
- **Dubbele import van dezelfde rit** → dedupe op Strava activity-id.
- **Klim verwijderd of hernoemd** → records overleven, want gekoppeld op
  `ClimbIdentity`, niet op route-id.
- **Strava rate limits** → eerste sync begrensd tot de laatste 12 maanden en
  gepagineerd; daarna incrementeel (alleen nieuwer dan laatste sync) + retry met
  dezelfde semantiek als bestaande sync.
- **GPS-drift in de activity** → `NearestPointFinder` + afstandsdrempel vangen jitter
  rond start/eind op.

## Testing

- Unit-tests op `ClimbAttemptMatcher` met synthetische tracks: volledige klim,
  gedeeltelijk, kruisende weg die de start raakt maar niet doorloopt, en drift.
- `ClimbIdentity`-tests: dezelfde klim in twee routes → identieke sleutel; net
  verschillende klims → verschillende sleutel.
- Round-trip test op `climb_attempts.json` (schrijven → lezen → gelijk).
- `ClimbAttemptRepository`-test: PR-afleiding kiest de snelste niet-partiële poging.
- Geen watch/protocol-tests nodig — het wire-format blijft ongewijzigd.

## Risico's

- **Match-betrouwbaarheid** hangt af van GPS-kwaliteit en de afstands-/lengte-
  drempels. Mitigatie: drempels als constanten, gedekt door synthetische tests.
- **Strava-quota** bij veel historische ritten. Mitigatie: incrementeel ophalen en
  een begrensd aantal ritten per sync.

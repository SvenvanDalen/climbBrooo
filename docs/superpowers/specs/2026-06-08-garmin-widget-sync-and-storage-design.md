# Garmin Widget: Phone Sync + Watch Storage — Design

## Goal

De ClimbPro widget op de Forerunner 255 kan routes ophalen van de Android-companion app en routes/klimmen opslaan op de watch zelf. Bij openen probeert de widget automatisch te verbinden met de telefoon. Opgeslagen items zijn altijd beschikbaar, ook zonder telefoon.

---

## Schermflow

### 1. SyncView (nieuw)
Getoond direct bij openen van de widget. Stuurt een `LIST_ROUTES` request naar de telefoon.

- Toont: "Verbinden met telefoon..."
- Bij ontvangst van `ROUTE_LIST` response → switch naar RouteListView
- Bij timeout (~10s) of geen verbinding → switch naar RouteListView met alleen opgeslagen items, "Telefoon" sectie toont "Geen verbinding"

### 2. RouteListView (herschrijving van RouteView)
Hoofdscherm. Twee secties gescheiden door een visuele divider:

**Sectie 1 — Telefoon:**
- Gevuld vanuit de `ROUTE_LIST` response
- Toont routenaam + aantal klimmen per item
- Bij geen verbinding: "Geen verbinding" placeholder

**Sectie 2 — Op watch:**
- Gevuld vanuit `Application.Storage` keys `saved_route_ids` en `saved_climb_ids`
- Toont opgeslagen routes én losse klimmen
- Altijd beschikbaar, ook offline

Navigatie: UP/DOWN scrollen door beide secties, SELECT opent item, BACK sluit widget.

### 3. ClimbListView (uitbreiding)
Toont klimmen van de geselecteerde route.

- Telefoon-route geselecteerd → stuurt `LOAD_ROUTE` request → ontvangt v3 payload → overschrijft `ClimbData` in memory
- Opgeslagen route geselecteerd → laadt direct uit `Application.Storage`
- Onderaan lijst: "Sla route op" (groen) als route nog niet is opgeslagen
- Onderaan lijst: "Verwijder route" (rood) als route al is opgeslagen
- SELECT op klim → ClimbDetailView

### 4. ClimbDetailView (uitbreiding)
Toont het klimprofiel (bestaande ProfileDrawer). Alleen klimniveau — route-acties zitten in ClimbListView.

- Onderaan: "Sla klim op" (groen) als deze klim nog niet individueel is opgeslagen
- Onderaan: "Verwijder klim" (rood) als deze klim individueel is opgeslagen

### 5. Navigatieknoppenmapping (FR255)
| Knop | Actie |
|---|---|
| UP | Scroll omhoog / vorige pagina |
| DOWN | Scroll omlaag / volgende pagina |
| SELECT | Bevestig / open geselecteerd item |
| BACK | Terug naar vorig scherm |

---

## Communicatieprotocol

### Watch → Telefoon (via `Comm.transmit()`)

```json
{ "type": "LIST_ROUTES" }
```
Verstuurd bij openen van de widget (in `SyncView.onShow()`).

```json
{ "type": "LOAD_ROUTE", "id": "routeId123" }
```
Verstuurd wanneer gebruiker een telefoon-route selecteert.

### Telefoon → Watch (via bestaand `sendMessage()`)

```json
{
  "type": "ROUTE_LIST",
  "routes": [
    { "id": "abc123", "name": "Mont Ventoux", "climbCount": 3 },
    { "id": "def456", "name": "Alpe d'Huez",  "climbCount": 1 }
  ]
}
```

Voor `LOAD_ROUTE`: de telefoon antwoordt met het **bestaande v3 payload** formaat. Geen nieuw formaat. De watch verwerkt het via de bestaande `PhoneMessageCallback.onMessage()`.

### Dispatch in CommListener
`PhoneMessageCallback.onMessage()` krijgt een dispatcher op het `type` veld:
- `type == null` → bestaand gedrag (v3 route payload)
- `type == "ROUTE_LIST"` → vul de route-index in `ClimbData`

---

## Opslag op de watch (`Application.Storage`)

| Key | Type | Inhoud |
|---|---|---|
| `"saved_route_ids"` | Array\<String\> | IDs van opgeslagen routes |
| `"route_{id}"` | Dictionary | Volledige v3 payload van de route |
| `"saved_climb_ids"` | Array\<String\> | Keys van losse klimmen (`"{routeId}_{idx}"`) |
| `"climb_{routeId}_{idx}"` | Dictionary | Data van één klim (subset van v3 payload) |

**Opslaan route:** Sla v3 payload op onder `route_{id}`, voeg `id` toe aan `saved_route_ids`.

**Opslaan losse klim:** Sla climbDict op onder `climb_{routeId}_{idx}`, voeg key toe aan `saved_climb_ids`.

**Verwijderen:** Verwijder data-key, verwijder entry uit de index-array.

**Geheugenbudget:** Een route is ~3–5KB. De FR255 geeft ~32KB per app → max ~6–8 volledige routes. Bij opslaan controleren of er ruimte is; toon melding als storage vol is.

---

## Garmin-side bestandswijzigingen

### Nieuw
| Bestand | Doel |
|---|---|
| `garmin-widget/source/SyncView.mc` | Laadscherm dat LIST_ROUTES verstuurt |
| `garmin-widget/source/StorageManager.mc` | Lees/schrijf/verwijder routes en klimmen in Application.Storage |
| `garmin-widget/source/PhoneRouteIndex.mc` | Houdt de lijst van telefoon-routes bij (naam, id, climbCount) |

### Gewijzigd
| Bestand | Wijziging |
|---|---|
| `garmin-widget/source/ClimbWidgetApp.mc` | `getInitialView()` → SyncView als startscherm |
| `garmin-widget/source/CommListener.mc` | Dispatcher op `type` veld; verwerk `ROUTE_LIST` respons |
| `garmin-widget/source/RouteView.mc` | Herschreven als RouteListView met twee secties |
| `garmin-widget/source/ClimbListView.mc` | "Sla route op" knop onderaan |
| `garmin-widget/source/ClimbDetailView.mc` | "Sla/verwijder klim" knop onderaan |

---

## Android-side wijzigingen

### Nieuw
| Bestand | Doel |
|---|---|
| `app/src/main/java/.../sync/WatchRequestHandler.java` | Verwerkt inkomende watch-requests (`LIST_ROUTES`, `LOAD_ROUTE`) |

**`WatchRequestHandler`-interface:**
```java
// Ontvangt een Dictionary van de watch, stuurt passende data terug
void handleRequest(Map<String, Object> request, IQDevice device);
```

- `LIST_ROUTES` → leest `catalog.json` → bouwt lijst van `{id, name, climbCount}` → stuurt `ROUTE_LIST` bericht
- `LOAD_ROUTE` → leest `routes/{id}.json` → serialiseert via bestaande v3 serializer → stuurt v3 payload

### Gewijzigd
| Bestand | Wijziging |
|---|---|
| `app/src/main/java/.../sync/SyncManager.java` | Registreer `receiveMessage` callback → dispatch naar `WatchRequestHandler` |

**Wat niet verandert:** v3 serialisatieformaat, WorkManager periodieksync, route-opslag op telefoon.

---

## Edge cases

| Situatie | Gedrag |
|---|---|
| Geen telefoon bij openen | SyncView timeout → RouteListView met alleen "Op watch" sectie |
| Telefoon verbinding valt weg tijdens laden | `LOAD_ROUTE` timeout → toon "Verbinding verloren", blijf op huidig scherm |
| Storage vol bij opslaan | Toon "Opslag vol — verwijder eerst een route" |
| Route al opgeslagen, opnieuw laden | Overschrijf opgeslagen versie (versheid boven duplicaten) |
| Losse klim opgeslagen, bijbehorende route verwijderd | Losse klim blijft beschikbaar (onafhankelijk opgeslagen) |
| Twee routes geladen tegelijk | Niet mogelijk — laden overschrijft altijd de huidige `ClimbData` in memory |

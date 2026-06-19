# Strava-sync robuust maken + automatische UI-verversing + instelbare sorteervolgorde — Implementatieplan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Zorg dat een Strava-sync de routes altijd ophaalt en opslaat (ook zonder verbonden horloge), dat de routelijst automatisch ververst zodra een sync klaar is, en dat de gebruiker zelf de sorteervolgorde van de lijst kan instellen.

**Architecture:** De huidige `RouteSyncWorker` blokkeert de *hele* sync — inclusief het ophalen van Strava-routes — achter de horlogeverbinding; daardoor verschijnt er "niks" als het horloge niet verbonden is. We splitsen de orkestratie af in een pure, testbare `SyncOrchestrator`: eerst de Strava-pull (offline-first, los van het horloge), die direct via WorkManager-progress de UI laat verversen; daarna een *opportunistische* payload-send naar het horloge die mag falen zonder de sync te laten mislukken. De UI observeert de WorkManager-job en herlaadt de catalogus zodra de pull klaar is. Sorteren wordt een pure helper (`RouteSorting`) met een door de gebruiker instelbare, in `SharedPreferences` bewaarde modus.

**Tech Stack:** Java 17, Android (minSdk 26), WorkManager 2.9.1, Retrofit 2.11 + Jackson, AndroidX Lifecycle/LiveData (MVVM), JUnit 4.13.2, Mockito 5.12, Robolectric 4.13. Build met Gradle Groovy DSL.

---

## Achtergrond: waarom sync nu faalt (root cause)

In `android/app/src/main/java/nl/paree/climbpro/service/RouteSyncWorker.java` (regels 63–72) wacht `doWork()` eerst maximaal 5 seconden op een horlogeverbinding en doet `return Result.retry()` als het horloge níet verbonden is — **vóórdat** de Strava-pull (stap 1, regel 75–82) ooit draait. Gevolg: zonder verbonden horloge worden er nooit routes gedownload of opgeslagen → "niks verschijnt". Daarnaast enqueuet `RouteListViewModel.triggerSync()` alleen een WorkManager-job; de UI herlaadt pas bij de volgende `onResume()`, niet automatisch bij een geslaagde sync.

Dit plan lost beide op en voegt instelbare sortering toe.

---

## Bestandsoverzicht

**Nieuw:**
- `android/app/src/main/java/nl/paree/climbpro/service/SyncOrchestrator.java` — pure orkestratie (pull → progress → opportunistische send). Geen Android-framework, volledig unit-testbaar.
- `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteSorting.java` — pure sorteer-helper + sorteermodus-constanten.
- `android/app/src/test/java/nl/paree/climbpro/service/SyncOrchestratorTest.java`
- `android/app/src/test/java/nl/paree/climbpro/data/strava/StravaRoutesRepositoryTest.java`
- `android/app/src/test/java/nl/paree/climbpro/ui/routes/RouteSortingTest.java`

**Gewijzigd:**
- `.../data/strava/StravaRoutesRepository.java` — injecteerbare `StravaApiClient`; `syncRoutes()` geeft aantal nieuwe/gewijzigde routes terug.
- `.../service/RouteSyncWorker.java` — gebruikt `SyncOrchestrator`; pull eerst, send opportunistisch; rapporteert via progress/output-data.
- `.../service/SyncScheduler.java` — unieke work-naam voor handmatige sync + `LiveData<List<WorkInfo>>`-accessor.
- `.../ui/routes/RouteListViewModel.java` — sorteermodus (lezen/zetten/persisteren), gecombineerd met bestaand surface-filter.
- `.../ui/routes/RouteListActivity.java` — observeert de sync-job (verversen + feedback) en biedt een "Sorteer"-menu.
- `android/app/src/main/res/menu/route_list_menu.xml` — menu-item "Sorteer".

---

## Task 1: `StravaRoutesRepository` testbaar maken + aantal gewijzigde routes teruggeven

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/data/strava/StravaRoutesRepository.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/data/strava/StravaRoutesRepositoryTest.java`

- [ ] **Step 1: Schrijf de falende test**

Maak `android/app/src/test/java/nl/paree/climbpro/data/strava/StravaRoutesRepositoryTest.java`:

```java
package nl.paree.climbpro.data.strava;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.domain.climb.ClimbConstants;

import okhttp3.MediaType;
import okhttp3.ResponseBody;
import retrofit2.Call;
import retrofit2.Response;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Collections;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
public class StravaRoutesRepositoryTest {

    private static final String GPX =
            "<?xml version=\"1.0\"?>"
          + "<gpx><trk><trkseg>"
          + "<trkpt lat=\"51.0\" lon=\"5.0\"><ele>10</ele></trkpt>"
          + "<trkpt lat=\"51.001\" lon=\"5.001\"><ele>12</ele></trkpt>"
          + "</trkseg></trk></gpx>";

    private Application app;
    private RouteRepository routeRepo;
    private StravaAuthRepository auth;
    private StravaApiClient api;

    @Before
    public void setUp() throws Exception {
        app = ApplicationProvider.getApplicationContext();
        // Sla RouteRepository-migratie (die alle routes wist) over.
        SharedPreferences prefs = app.getSharedPreferences("route_repo", Context.MODE_PRIVATE);
        prefs.edit().putInt("segment_version", ClimbConstants.SEGMENT_VERSION).commit();

        routeRepo = new RouteRepository(app);
        auth = mock(StravaAuthRepository.class);
        when(auth.getAccessToken()).thenReturn("tok");
        api = mock(StravaApiClient.class);
    }

    @SuppressWarnings("unchecked")
    private void stubOneRoute() throws Exception {
        StravaRouteDto dto = new StravaRouteDto();
        dto.id = 123L;
        dto.name = "Test Route";
        dto.distance = 1000f;
        dto.updatedAt = "2026-01-01T00:00:00Z";

        Call<List<StravaRouteDto>> page1 = mock(Call.class);
        when(page1.execute()).thenReturn(Response.success(Collections.singletonList(dto)));
        Call<List<StravaRouteDto>> page2 = mock(Call.class);
        when(page2.execute()).thenReturn(Response.success(Collections.<StravaRouteDto>emptyList()));
        when(api.listRoutes(anyString(), eq(1), anyInt())).thenReturn(page1);
        when(api.listRoutes(anyString(), eq(2), anyInt())).thenReturn(page2);

        Call<ResponseBody> gpx = mock(Call.class);
        when(gpx.execute()).thenReturn(Response.success(
                ResponseBody.create(GPX, MediaType.parse("application/gpx+xml"))));
        when(api.exportGpx(anyString(), eq(123L))).thenReturn(gpx);
    }

    @Test
    public void syncRoutes_newRoute_returnsOneAndPersists() throws Exception {
        stubOneRoute();
        StravaRoutesRepository repo = new StravaRoutesRepository(auth, routeRepo, api);

        int changed = repo.syncRoutes();

        assertEquals(1, changed);
        assertEquals(1, routeRepo.loadCatalog().size());
    }

    @Test
    public void syncRoutes_unchangedRoute_returnsZero() throws Exception {
        stubOneRoute();
        StravaRoutesRepository repo = new StravaRoutesRepository(auth, routeRepo, api);
        repo.syncRoutes(); // eerste keer: opgeslagen

        stubOneRoute(); // zelfde dto → zelfde sourceHash
        int changed = repo.syncRoutes();

        assertEquals(0, changed);
        assertEquals(1, routeRepo.loadCatalog().size());
    }
}
```

- [ ] **Step 2: Draai de test en bevestig dat hij faalt**

Run (vanuit `android/`): `./gradlew :app:testDebugUnitTest --tests nl.paree.climbpro.data.strava.StravaRoutesRepositoryTest`
Expected: FAIL — de constructor `StravaRoutesRepository(auth, routeRepo, api)` bestaat nog niet en `syncRoutes()` is `void` (compileerfout / "cannot find symbol").

- [ ] **Step 3: Pas `StravaRoutesRepository` aan**

In `android/app/src/main/java/nl/paree/climbpro/data/strava/StravaRoutesRepository.java`:

Vervang het constructor-blok (huidige regels 46–50):

```java
    public StravaRoutesRepository(StravaAuthRepository auth, RouteRepository routeRepo) {
        this(auth, routeRepo, buildRetrofit().create(StravaApiClient.class));
    }

    /** Test-injecteerbare variant — geef een (mock) StravaApiClient mee. */
    StravaRoutesRepository(StravaAuthRepository auth, RouteRepository routeRepo, StravaApiClient api) {
        this.auth      = auth;
        this.routeRepo = routeRepo;
        this.api       = api;
    }
```

Vervang de signatuur en body-staart van `syncRoutes()` (huidige regels 58–74) zodat hij een teller teruggeeft:

```java
    public int syncRoutes() throws IOException {
        String token = "Bearer " + auth.getAccessToken();
        List<StravaRouteDto> routes = new ArrayList<>();
        int page = 1;
        while (true) {
            Response<List<StravaRouteDto>> resp =
                    api.listRoutes(token, page, 50).execute();
            if (!resp.isSuccessful() || resp.body() == null || resp.body().isEmpty()) break;
            routes.addAll(resp.body());
            page++;
        }
        Log.i(TAG, "Found " + routes.size() + " Strava routes");

        int changed = 0;
        for (StravaRouteDto dto : routes) {
            if (processRoute(token, dto)) changed++;
        }
        Log.i(TAG, "Strava sync: " + changed + " route(s) created/updated");
        return changed;
    }
```

Wijzig `processRoute` zodat hij `boolean` teruggeeft (huidige regels 76–141). Wijzig de signatuur en de twee vroege returns en voeg een `return true;` toe na een geslaagde opslag:

- Signatuur: `private boolean processRoute(String token, StravaRouteDto dto) {`
- "unchanged, skipping"-tak: `return false;` (was `return;`)
- GPX-download-faaltak (`Failed to download GPX`): `return false;` (was `return;`)
- Direct ná `routeRepo.saveRoute(stored, simplified, climbs);` + de surface-wiring (dus net vóór de `} catch (GpxParseException e) {`): voeg `return true;` toe.
- In de twee `catch`-blokken (`GpxParseException`, `IOException`): voeg `return false;` toe als laatste regel van elk blok.

(Laat alle overige logica — hashing, surface-wiring, naam-behoud — ongewijzigd.)

- [ ] **Step 4: Draai de test en bevestig dat hij slaagt**

Run (vanuit `android/`): `./gradlew :app:testDebugUnitTest --tests nl.paree.climbpro.data.strava.StravaRoutesRepositoryTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/data/strava/StravaRoutesRepository.java android/app/src/test/java/nl/paree/climbpro/data/strava/StravaRoutesRepositoryTest.java
git commit -m "feat(android): StravaRoutesRepository injectable + report changed count"
```

---

## Task 2: `SyncOrchestrator` — pull eerst, send opportunistisch (kern-bugfix)

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/service/SyncOrchestrator.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/service/SyncOrchestratorTest.java`

- [ ] **Step 1: Schrijf de falende test**

Maak `android/app/src/test/java/nl/paree/climbpro/service/SyncOrchestratorTest.java`:

```java
package nl.paree.climbpro.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public class SyncOrchestratorTest {

    private static final byte[] PAYLOAD = {1, 2, 3};

    /** REGRESSIETEST: zonder verbonden horloge moet de Strava-pull tóch draaien en gerapporteerd worden. */
    @Test
    public void watchUnavailable_pullStillRunsAndReports() {
        AtomicInteger reported = new AtomicInteger(-1);
        SyncOrchestrator orch = new SyncOrchestrator(
                /* stravaAuthorised */ true,
                /* routeSource */ () -> 3,
                /* watch */ new FakeWatch(false, false),
                /* payloadJob */ new FakePayloadJob(PAYLOAD),
                /* connectTimeoutMs */ 0, /* sendTimeoutMs */ 0);

        SyncOrchestrator.Result r = orch.run((changed, ok) -> reported.set(changed));

        assertTrue(r.pullSucceeded);
        assertEquals(3, r.routesChanged);
        assertEquals(3, reported.get());     // progress is gemeld vóór de send-poging
        assertFalse(r.watchAvailable);
        assertFalse(r.sendAttempted);
    }

    @Test
    public void watchAvailable_buildsSendsAndMarksSent() {
        FakePayloadJob job = new FakePayloadJob(PAYLOAD);
        SyncOrchestrator orch = new SyncOrchestrator(
                true, () -> 1, new FakeWatch(true, true), job, 0, 0);

        SyncOrchestrator.Result r = orch.run((c, ok) -> {});

        assertTrue(r.sendAttempted);
        assertTrue(r.sendSucceeded);
        assertTrue(job.sentCalled);
    }

    @Test
    public void pullThrows_pullFailsButSendStillAttempted() {
        SyncOrchestrator orch = new SyncOrchestrator(
                true,
                () -> { throw new IOException("network down"); },
                new FakeWatch(true, true),
                new FakePayloadJob(PAYLOAD), 0, 0);

        SyncOrchestrator.Result r = orch.run((c, ok) -> {});

        assertFalse(r.pullSucceeded);
        assertTrue(r.sendAttempted);
    }

    @Test
    public void nothingToSend_buildReturnsNull_noSend() {
        SyncOrchestrator orch = new SyncOrchestrator(
                true, () -> 0, new FakeWatch(true, true),
                new FakePayloadJob(null), 0, 0);

        SyncOrchestrator.Result r = orch.run((c, ok) -> {});

        assertTrue(r.watchAvailable);
        assertFalse(r.sendAttempted);
    }

    // --- fakes ---

    private static final class FakeWatch implements SyncOrchestrator.WatchSender {
        private final boolean connected;
        private final boolean sendOk;
        FakeWatch(boolean connected, boolean sendOk) { this.connected = connected; this.sendOk = sendOk; }
        public boolean awaitConnected(long timeoutMs) { return connected; }
        public boolean send(byte[] payload, long timeoutMs) { return sendOk; }
    }

    private static final class FakePayloadJob implements SyncOrchestrator.PayloadJob {
        private final byte[] payload;
        boolean sentCalled = false;
        FakePayloadJob(byte[] payload) { this.payload = payload; }
        public byte[] build() { return payload; }
        public void onSent() { sentCalled = true; }
    }
}
```

- [ ] **Step 2: Draai de test en bevestig dat hij faalt**

Run (vanuit `android/`): `./gradlew :app:testDebugUnitTest --tests nl.paree.climbpro.service.SyncOrchestratorTest`
Expected: FAIL — `SyncOrchestrator` bestaat nog niet ("cannot find symbol").

- [ ] **Step 3: Schrijf `SyncOrchestrator`**

Maak `android/app/src/main/java/nl/paree/climbpro/service/SyncOrchestrator.java`:

```java
package nl.paree.climbpro.service;

import java.io.IOException;

/**
 * Pure, framework-vrije orkestratie van één sync-ronde.
 *
 * Volgorde (offline-first):
 *   1. Strava-pull (indien geautoriseerd) — onafhankelijk van het horloge.
 *   2. Meld de pull-uitkomst via {@link ProgressListener} zodat de UI direct kan verversen.
 *   3. Opportunistische payload-send naar het horloge: mag falen/overslaan zonder
 *      de pull ongedaan te maken.
 *
 * Bevat geen Android-types, zodat het volledig unit-testbaar is.
 */
public final class SyncOrchestrator {

    /** Levert routes aan (bv. {@code StravaRoutesRepository::syncRoutes}). */
    public interface RouteSource {
        /** @return aantal nieuw aangemaakte of gewijzigde routes (0 indien geen). */
        int syncRoutes() throws IOException;
    }

    /** Verbindt met en verstuurt naar het horloge. */
    public interface WatchSender {
        boolean awaitConnected(long timeoutMs);
        boolean send(byte[] payload, long timeoutMs) throws IOException;
    }

    /** Bouwt de payload voor de actieve modus en commit na verzending. */
    public interface PayloadJob {
        /** @return payload-bytes, of {@code null} als er niets te versturen is. */
        byte[] build() throws IOException;
        /** Aangeroepen na een geslaagde verzending (bv. markSynced). */
        void onSent() throws IOException;
    }

    /** Wordt aangeroepen zodra de pull klaar is (vóór de send). */
    public interface ProgressListener {
        void onPullComplete(int routesChanged, boolean success);
    }

    public static final class Result {
        public final boolean pullAttempted;
        public final boolean pullSucceeded;
        public final int     routesChanged;
        public final boolean watchAvailable;
        public final boolean sendAttempted;
        public final boolean sendSucceeded;

        Result(boolean pullAttempted, boolean pullSucceeded, int routesChanged,
               boolean watchAvailable, boolean sendAttempted, boolean sendSucceeded) {
            this.pullAttempted  = pullAttempted;
            this.pullSucceeded  = pullSucceeded;
            this.routesChanged  = routesChanged;
            this.watchAvailable = watchAvailable;
            this.sendAttempted  = sendAttempted;
            this.sendSucceeded  = sendSucceeded;
        }
    }

    private final boolean     stravaAuthorised;
    private final RouteSource routeSource;
    private final WatchSender watch;
    private final PayloadJob  payloadJob;
    private final long        connectTimeoutMs;
    private final long        sendTimeoutMs;

    public SyncOrchestrator(boolean stravaAuthorised, RouteSource routeSource,
                            WatchSender watch, PayloadJob payloadJob,
                            long connectTimeoutMs, long sendTimeoutMs) {
        this.stravaAuthorised = stravaAuthorised;
        this.routeSource      = routeSource;
        this.watch            = watch;
        this.payloadJob       = payloadJob;
        this.connectTimeoutMs = connectTimeoutMs;
        this.sendTimeoutMs    = sendTimeoutMs;
    }

    public Result run(ProgressListener progress) {
        boolean pullAttempted = stravaAuthorised;
        boolean pullSucceeded = false;
        int     changed       = 0;

        if (stravaAuthorised) {
            try {
                changed = routeSource.syncRoutes();
                pullSucceeded = true;
            } catch (IOException e) {
                pullSucceeded = false; // niet fataal — we proberen alsnog te versturen
            }
        }
        if (progress != null) progress.onPullComplete(changed, pullSucceeded);

        boolean watchAvailable = watch.awaitConnected(connectTimeoutMs);
        if (!watchAvailable) {
            return new Result(pullAttempted, pullSucceeded, changed, false, false, false);
        }

        byte[] payload;
        try {
            payload = payloadJob.build();
        } catch (IOException e) {
            return new Result(pullAttempted, pullSucceeded, changed, true, false, false);
        }
        if (payload == null) {
            return new Result(pullAttempted, pullSucceeded, changed, true, false, false);
        }

        boolean sent;
        try {
            sent = watch.send(payload, sendTimeoutMs);
        } catch (IOException e) {
            sent = false;
        }
        if (sent) {
            try { payloadJob.onSent(); } catch (IOException ignored) { /* niet fataal */ }
        }
        return new Result(pullAttempted, pullSucceeded, changed, true, true, sent);
    }
}
```

- [ ] **Step 4: Draai de test en bevestig dat hij slaagt**

Run (vanuit `android/`): `./gradlew :app:testDebugUnitTest --tests nl.paree.climbpro.service.SyncOrchestratorTest`
Expected: PASS (4 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/service/SyncOrchestrator.java android/app/src/test/java/nl/paree/climbpro/service/SyncOrchestratorTest.java
git commit -m "feat(android): SyncOrchestrator — pull first, opportunistic watch send"
```

---

## Task 3: `RouteSyncWorker` op de orchestrator zetten + progress/output rapporteren

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/service/RouteSyncWorker.java`

> Geen aparte unit-test: de worker is dunne glue rond `ConnectIqClient`/`ClimbProApplication` (Garmin-SDK), die niet betrouwbaar in JVM-tests te construeren is. De logica is al getest in `SyncOrchestratorTest`. Verificatie hier is een geslaagde build.

- [ ] **Step 1: Voeg output-/progress-sleutels toe**

In `RouteSyncWorker.java`, voeg bij de bestaande `public static final String`-constanten (rond regel 35–41) toe:

```java
    public  static final String KEY_PULL_DONE  = "pull_done";
    public  static final String KEY_CHANGED    = "routes_changed";
    public  static final String KEY_WATCH_SENT = "watch_sent";
```

- [ ] **Step 2: Vervang `doWork()`**

Vervang de volledige methode `doWork()` (regels 49–149) door:

```java
    @NonNull
    @Override
    public Result doWork() {
        Context ctx = getApplicationContext();

        RouteRepository      routeRepo      = new RouteRepository(ctx);
        SyncStateRepository  syncStateRepo  = new SyncStateRepository(ctx);
        StravaAuthRepository authRepo        = new StravaAuthRepository(ctx);
        ObjectMapper         mapper          = new ObjectMapper();
        ClimbPayloadBuilder  payloadBuilder  = new ClimbPayloadBuilder(mapper);
        ConnectIqClient      ciqClient       =
                ((nl.paree.climbpro.ClimbProApplication) ctx).connectIqClient();
        SharedPreferences    prefs           = PreferenceManager.getDefaultSharedPreferences(ctx);

        boolean authorised = authRepo.isAuthorised();

        SyncOrchestrator.RouteSource pull = () ->
                authorised
                        ? new StravaRoutesRepository(authRepo, routeRepo).syncRoutes()
                        : 0;

        SyncOrchestrator.WatchSender sender = new SyncOrchestrator.WatchSender() {
            @Override public boolean awaitConnected(long timeoutMs) {
                long deadline = System.currentTimeMillis() + timeoutMs;
                while (!ciqClient.isConnected() && System.currentTimeMillis() < deadline) {
                    try { Thread.sleep(250); } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                return ciqClient.isConnected();
            }
            @Override public boolean send(byte[] payload, long ms) {
                return ciqClient.sendPayloadBlocking(payload, ms);
            }
        };

        SyncOrchestrator.PayloadJob job = buildPayloadJob(
                prefs, routeRepo, syncStateRepo, payloadBuilder);

        SyncOrchestrator orchestrator = new SyncOrchestrator(
                authorised, pull, sender, job,
                /* connectTimeoutMs */ 5_000, /* sendTimeoutMs */ 10_000);

        SyncOrchestrator.Result r = orchestrator.run((changed, ok) ->
                setProgressAsync(new androidx.work.Data.Builder()
                        .putBoolean(KEY_PULL_DONE, true)
                        .putInt(KEY_CHANGED, changed)
                        .build()));

        androidx.work.Data output = new androidx.work.Data.Builder()
                .putBoolean(KEY_PULL_DONE, true)
                .putInt(KEY_CHANGED, r.routesChanged)
                .putBoolean(KEY_WATCH_SENT, r.sendSucceeded)
                .build();

        boolean shouldRetry = (r.pullAttempted && !r.pullSucceeded)
                || (r.sendAttempted && !r.sendSucceeded);
        if (shouldRetry) {
            Log.w(TAG, "Sync incomplete — will retry (pull=" + r.pullSucceeded
                    + ", sendAttempted=" + r.sendAttempted + ", sent=" + r.sendSucceeded + ")");
            return Result.retry();
        }
        return Result.success(output);
    }

    /**
     * Bouwt de juiste {@link SyncOrchestrator.PayloadJob} voor de actieve modus.
     * {@link SyncOrchestrator.PayloadJob#build()} geeft {@code null} terug als er
     * niets te versturen is (geen route geselecteerd, of onveranderd).
     */
    private SyncOrchestrator.PayloadJob buildPayloadJob(
            SharedPreferences prefs, RouteRepository routeRepo,
            SyncStateRepository syncStateRepo, ClimbPayloadBuilder payloadBuilder) {

        String mode = prefs.getString(PREF_MODE, MODE_ROUTE);

        if (MODE_RADIUS.equals(mode)) {
            return new SyncOrchestrator.PayloadJob() {
                @Override public byte[] build() throws IOException {
                    double lat = Double.longBitsToDouble(
                            prefs.getLong(PREF_LAST_LAT, Double.doubleToLongBits(0)));
                    double lon = Double.longBitsToDouble(
                            prefs.getLong(PREF_LAST_LON, Double.doubleToLongBits(0)));
                    double radiusM = prefs.getInt(PREF_RADIUS_M, DEFAULT_RADIUS_M);
                    RadiusModeAssembler assembler =
                            new RadiusModeAssembler(routeRepo, payloadBuilder);
                    byte[] payload = assembler.assemble(lat, lon, radiusM);
                    if (assembler.wasTruncated()) {
                        Log.w(TAG, "Radius payload was truncated — some climbs omitted");
                    }
                    return payload;
                }
                @Override public void onSent() { /* radius-modus heeft geen per-route syncstate */ }
            };
        }

        return new SyncOrchestrator.PayloadJob() {
            @Override public byte[] build() throws IOException {
                String routeId = prefs.getString(PREF_ROUTE_ID, null);
                if (routeId == null) {
                    Log.i(TAG, "No active route selected — nothing to send");
                    return null;
                }
                SyncState state = syncStateRepo.get(routeId);
                StoredRoute route = routeRepo.loadRoute(routeId);
                if (SyncState.Status.SYNCED.equals(state.status)
                        && route.sourceHash.equals(state.lastSyncedHash)) {
                    Log.i(TAG, "Route " + routeId + " unchanged, no re-sync needed");
                    return null;
                }
                byte[] payload = payloadBuilder.buildRoutePayload(route);
                if (payload.length > PayloadBudget.MAX_BYTES) {
                    Log.e(TAG, "Payload exceeds budget: " + payload.length + " bytes — skipping send");
                    return null;
                }
                return payload;
            }
            @Override public void onSent() throws IOException {
                String routeId = prefs.getString(PREF_ROUTE_ID, null);
                if (routeId != null) {
                    StoredRoute route = routeRepo.loadRoute(routeId);
                    syncStateRepo.markSynced(routeId, route.sourceHash);
                }
            }
        };
    }
```

- [ ] **Step 3: Bouw de module en bevestig dat hij compileert**

Run (vanuit `android/`): `./gradlew :app:compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL. (Controleer dat `IOException`, `SharedPreferences`, `PreferenceManager` nog geïmporteerd zijn — dat waren ze al in dit bestand.)

- [ ] **Step 4: Draai de volledige unit-testsuite**

Run (vanuit `android/`): `./gradlew :app:testDebugUnitTest`
Expected: PASS — alle bestaande tests + de nieuwe blijven groen.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/service/RouteSyncWorker.java
git commit -m "fix(android): sync pulls Strava routes regardless of watch connection"
```

---

## Task 4: Handmatige sync uniek maken + WorkInfo observeerbaar

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/service/SyncScheduler.java`

> Verificatie: build. (WorkManager-instantiatie vereist een geïnitialiseerde `WorkManager`; we testen het gedrag end-to-end via de UI in Task 6, niet met een losse unit-test.)

- [ ] **Step 1: Voeg unieke work-naam, `ExistingWorkPolicy` en LiveData-accessor toe**

Vervang in `SyncScheduler.java` de imports en `triggerImmediateSync` zodat het bestand er zo uitziet:

```java
package nl.paree.climbpro.service;

import android.content.Context;

import androidx.lifecycle.LiveData;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;

import java.util.List;
import java.util.concurrent.TimeUnit;

public final class SyncScheduler {

    private static final String PERIODIC_TAG = "climbpro_periodic_sync";
    public  static final String UNIQUE_MANUAL_SYNC = "climbpro_manual_sync";
    private static final long   INTERVAL_HOURS = 6;

    private SyncScheduler() {}

    public static void schedulePeriodicSync(Context context) {
        Constraints constraints = new Constraints.Builder()
                .setRequiresCharging(true)
                .setRequiredNetworkType(NetworkType.UNMETERED)
                .build();

        PeriodicWorkRequest work = new PeriodicWorkRequest.Builder(
                RouteSyncWorker.class, INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints)
                .build();

        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_TAG,
                ExistingPeriodicWorkPolicy.KEEP,
                work);
    }

    /** Trigger an immediate sync (e.g. from the "Sync now" button). */
    public static void triggerImmediateSync(Context context) {
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(RouteSyncWorker.class)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_MANUAL_SYNC,
                ExistingWorkPolicy.REPLACE,
                work);
    }

    /** Observeerbare status van de laatste handmatige sync (voor UI-verversing/feedback). */
    public static LiveData<List<WorkInfo>> manualSyncInfo(Context context) {
        return WorkManager.getInstance(context)
                .getWorkInfosForUniqueWorkLiveData(UNIQUE_MANUAL_SYNC);
    }
}
```

- [ ] **Step 2: Bouw en bevestig dat het compileert**

Run (vanuit `android/`): `./gradlew :app:compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/service/SyncScheduler.java
git commit -m "feat(android): unique manual sync work + observable WorkInfo"
```

---

## Task 5: Instelbare sorteervolgorde — pure helper

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteSorting.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/ui/routes/RouteSortingTest.java`

- [ ] **Step 1: Schrijf de falende test**

Maak `android/app/src/test/java/nl/paree/climbpro/ui/routes/RouteSortingTest.java`:

```java
package nl.paree.climbpro.ui.routes;

import static org.junit.Assert.assertEquals;

import nl.paree.climbpro.data.route.RouteCatalogEntry;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RouteSortingTest {

    private RouteCatalogEntry entry(String id, String name, long importedAt) {
        RouteCatalogEntry e = new RouteCatalogEntry();
        e.routeId = id;
        e.name = name;
        e.importedAtMs = importedAt;
        return e;
    }

    private List<String> ids(List<RouteCatalogEntry> in) {
        List<String> out = new ArrayList<>();
        for (RouteCatalogEntry e : in) out.add(e.routeId);
        return out;
    }

    private List<RouteCatalogEntry> sample() {
        return new ArrayList<>(Arrays.asList(
                entry("b", "Bravo", 200L),
                entry("a", "Alpha", 100L),
                entry("c", "Charlie", 300L)));
    }

    @Test
    public void importAsc_newestAtBottom() {
        assertEquals(Arrays.asList("a", "b", "c"),
                ids(RouteSorting.sort(sample(), RouteSorting.SORT_IMPORT_ASC)));
    }

    @Test
    public void importDesc_newestAtTop() {
        assertEquals(Arrays.asList("c", "b", "a"),
                ids(RouteSorting.sort(sample(), RouteSorting.SORT_IMPORT_DESC)));
    }

    @Test
    public void nameAsc_alphabetical() {
        assertEquals(Arrays.asList("a", "b", "c"),
                ids(RouteSorting.sort(sample(), RouteSorting.SORT_NAME_ASC)));
    }

    @Test
    public void nameAsc_prefersUserDisplayName() {
        List<RouteCatalogEntry> in = sample();
        in.get(0).userDisplayName = "Aaa"; // "b" krijgt displaynaam die vooraan sorteert
        assertEquals("b", ids(RouteSorting.sort(in, RouteSorting.SORT_NAME_ASC)).get(0));
    }

    @Test
    public void unknownMode_fallsBackToImportAsc() {
        assertEquals(Arrays.asList("a", "b", "c"),
                ids(RouteSorting.sort(sample(), 999)));
    }
}
```

- [ ] **Step 2: Draai de test en bevestig dat hij faalt**

Run (vanuit `android/`): `./gradlew :app:testDebugUnitTest --tests nl.paree.climbpro.ui.routes.RouteSortingTest`
Expected: FAIL — `RouteSorting` bestaat nog niet.

- [ ] **Step 3: Schrijf `RouteSorting`**

Maak `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteSorting.java`:

```java
package nl.paree.climbpro.ui.routes;

import nl.paree.climbpro.data.route.RouteCatalogEntry;

import java.util.ArrayList;
import java.util.List;

/**
 * Pure sorteer-helper voor de routelijst. De modus is door de gebruiker
 * instelbaar en wordt bewaard in SharedPreferences (zie RouteListViewModel).
 */
public final class RouteSorting {

    /** Oplopend op importtijd: nieuwste routes onderaan. (Standaard.) */
    public static final int SORT_IMPORT_ASC  = 0;
    /** Aflopend op importtijd: nieuwste routes bovenaan. */
    public static final int SORT_IMPORT_DESC = 1;
    /** Alfabetisch op (display)naam, hoofdletterongevoelig. */
    public static final int SORT_NAME_ASC    = 2;

    private RouteSorting() {}

    /** Geeft een nieuwe, gesorteerde lijst terug; de invoerlijst blijft ongewijzigd. */
    public static List<RouteCatalogEntry> sort(List<RouteCatalogEntry> in, int mode) {
        List<RouteCatalogEntry> out = new ArrayList<>(in);
        switch (mode) {
            case SORT_IMPORT_DESC:
                out.sort((a, b) -> Long.compare(b.importedAtMs, a.importedAtMs));
                break;
            case SORT_NAME_ASC:
                out.sort((a, b) -> displayName(a).compareToIgnoreCase(displayName(b)));
                break;
            case SORT_IMPORT_ASC:
            default:
                out.sort((a, b) -> Long.compare(a.importedAtMs, b.importedAtMs));
                break;
        }
        return out;
    }

    private static String displayName(RouteCatalogEntry e) {
        if (e.userDisplayName != null) return e.userDisplayName;
        return e.name != null ? e.name : "";
    }
}
```

- [ ] **Step 4: Draai de test en bevestig dat hij slaagt**

Run (vanuit `android/`): `./gradlew :app:testDebugUnitTest --tests nl.paree.climbpro.ui.routes.RouteSortingTest`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteSorting.java android/app/src/test/java/nl/paree/climbpro/ui/routes/RouteSortingTest.java
git commit -m "feat(android): RouteSorting helper with import/name sort modes"
```

---

## Task 6: `RouteListViewModel` — sorteermodus lezen/zetten/persisteren

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteListViewModel.java`

> Verificatie: build + de volledige suite. De sorteer-logica zelf is al getest in `RouteSortingTest`; hier draait het om bedrading en persistentie.

- [ ] **Step 1: Voeg sorteer-state, persistentie en gecombineerde view toe**

Wijzig `RouteListViewModel.java` zo:

Voeg onder de bestaande imports toe:

```java
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
```

Voeg bij de velden (na `activeSurfaceFilter`, rond regel 32) toe:

```java
    private static final String PREF_SORT_MODE = "route_sort_mode";

    /** Sorteermodus uit {@link RouteSorting}; standaard nieuwste-onderaan. */
    private volatile int activeSortMode = RouteSorting.SORT_IMPORT_ASC;
```

Laad de bewaarde modus in de constructor — vervang de constructor-body (regels 34–39) door:

```java
    public RouteListViewModel(@NonNull Application app) {
        super(app);
        routeRepo = new RouteRepository(app);
        authRepo  = new StravaAuthRepository(app);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(app);
        activeSortMode = prefs.getInt(PREF_SORT_MODE, RouteSorting.SORT_IMPORT_ASC);
        loadRoutes();
    }
```

Voeg de getter/setter toe (bv. direct na `setSurfaceFilter`):

```java
    public int getSortMode() { return activeSortMode; }

    public void setSortMode(int sortMode) {
        activeSortMode = sortMode;
        PreferenceManager.getDefaultSharedPreferences(getApplication())
                .edit().putInt(PREF_SORT_MODE, sortMode).apply();
        List<RouteCatalogEntry> all = allRoutes.getValue();
        if (all != null) {
            routes.postValue(applyView(all, activeSurfaceFilter, sortMode));
        }
    }
```

Vervang in `loadRoutes()` de regel `routes.postValue(applyFilter(all, activeSurfaceFilter));` door:

```java
            routes.postValue(applyView(all, activeSurfaceFilter, activeSortMode));
```

Vervang in `setSurfaceFilter(...)` de regel `routes.postValue(applyFilter(all, surfaceType));` door:

```java
            routes.postValue(applyView(all, surfaceType, activeSortMode));
```

Vervang de helper `applyFilter` (regels 77–85) door een gecombineerde filter+sort-helper:

```java
    /** Past het surface-filter toe en sorteert daarna volgens {@code sortMode}. */
    private static List<RouteCatalogEntry> applyView(
            List<RouteCatalogEntry> all, int surfaceType, int sortMode) {
        List<RouteCatalogEntry> filtered;
        if (surfaceType == -1) {
            filtered = new ArrayList<>(all);
        } else {
            filtered = new ArrayList<>();
            for (RouteCatalogEntry e : all) {
                if (hasSurfaceType(e, surfaceType)) filtered.add(e);
            }
        }
        return RouteSorting.sort(filtered, sortMode);
    }
```

(Laat `hasSurfaceType` en de rest ongewijzigd.)

- [ ] **Step 2: Bouw en draai de suite**

Run (vanuit `android/`): `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, alle tests groen.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteListViewModel.java
git commit -m "feat(android): persisted, user-selectable route sort mode in ViewModel"
```

---

## Task 7: `RouteListActivity` — automatische verversing + feedback + sorteermenu

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteListActivity.java`
- Modify: `android/app/src/main/res/menu/route_list_menu.xml`

> Verificatie: build + handmatige run (Task 8). Activity-glue rond WorkManager-LiveData testen we end-to-end, niet met een losse unit-test.

- [ ] **Step 1: Voeg het sorteer-menu-item toe**

Voeg in `android/app/src/main/res/menu/route_list_menu.xml` vóór `action_settings` toe:

```xml
    <item
        android:id="@+id/action_sort"
        android:title="Sorteer"
        app:showAsAction="never"/>
```

- [ ] **Step 2: Observeer de sync-job (verversen + feedback)**

Voeg in `RouteListActivity.java` aan het einde van `onCreate(...)` (na `binding.fab.setOnClickListener(...)`, vóór de afsluitende `}`) toe:

```java
        nl.paree.climbpro.service.SyncScheduler.manualSyncInfo(this).observe(this, infos -> {
            if (infos == null || infos.isEmpty()) return;
            androidx.work.WorkInfo info = infos.get(infos.size() - 1);

            boolean pullDone =
                    info.getProgress().getBoolean(
                            nl.paree.climbpro.service.RouteSyncWorker.KEY_PULL_DONE, false)
                 || info.getOutputData().getBoolean(
                            nl.paree.climbpro.service.RouteSyncWorker.KEY_PULL_DONE, false);
            if (pullDone) {
                viewModel.loadRoutes(); // nieuwe routes verschijnen direct (onderaan bij standaard-sortering)
            }

            if (info.getState().isFinished()) {
                if (info.getState() == androidx.work.WorkInfo.State.SUCCEEDED) {
                    int changed = info.getOutputData().getInt(
                            nl.paree.climbpro.service.RouteSyncWorker.KEY_CHANGED, 0);
                    viewModel.loadRoutes();
                    Toast.makeText(this,
                            changed > 0
                                    ? ("Sync klaar: " + changed + " nieuwe/gewijzigde route(s)")
                                    : "Sync klaar — geen wijzigingen",
                            Toast.LENGTH_SHORT).show();
                } else if (info.getState() == androidx.work.WorkInfo.State.FAILED) {
                    Toast.makeText(this, "Sync mislukt", Toast.LENGTH_SHORT).show();
                }
            }
        });
```

- [ ] **Step 3: Handel het sorteer-menu af**

Voeg in `onOptionsItemSelected(...)` een tak toe vóór de afsluitende `return super.onOptionsItemSelected(item);`:

```java
        } else if (id == R.id.action_sort) {
            showSortDialog();
            return true;
```

En voeg de methode `showSortDialog()` toe (bv. direct na `showImportDialog()`):

```java
    private void showSortDialog() {
        final String[] labels = {
                "Importdatum (nieuwste onderaan)",
                "Importdatum (nieuwste bovenaan)",
                "Naam (A–Z)"
        };
        int current = viewModel.getSortMode();
        new AlertDialog.Builder(this)
                .setTitle("Sorteer routes")
                .setSingleChoiceItems(labels, current, (d, which) -> {
                    viewModel.setSortMode(which);
                    d.dismiss();
                })
                .show();
    }
```

> De volgorde van `labels` komt overeen met de constanten in `RouteSorting`:
> index 0 = `SORT_IMPORT_ASC`, 1 = `SORT_IMPORT_DESC`, 2 = `SORT_NAME_ASC`.

- [ ] **Step 4: Bouw de debug-APK**

Run (vanuit `android/`): `./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/ui/routes/RouteListActivity.java android/app/src/main/res/menu/route_list_menu.xml
git commit -m "feat(android): auto-refresh route list on sync + sort menu"
```

---

## Task 8: End-to-end verificatie (handmatig)

**Files:** geen — verificatie van het geheel.

- [ ] **Step 1: Draai de volledige unit-testsuite**

Run (vanuit `android/`): `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL, alle tests groen.

- [ ] **Step 2: Installeer en test handmatig (Strava ingelogd, géén horloge verbonden)**

Installeer de debug-APK op een toestel/emulator. Log in op Strava. Zorg dat het Garmin-horloge **niet** verbonden is. Druk op "Sync now" (of FAB → "Sync from Strava").

Expected:
- Binnen enkele seconden verschijnt een toast "Sync klaar: N nieuwe/gewijzigde route(s)".
- De nieuwe routes verschijnen **automatisch** in de lijst, zonder de activity te verlaten — bij standaard-sortering onderaan.

- [ ] **Step 3: Test de sorteer-instelling**

Menu → "Sorteer" → kies elke optie. Expected: de lijst herordent direct; na het sluiten en heropenen van de app blijft de gekozen volgorde behouden.

- [ ] **Step 4: Commit (alleen indien iets aangepast moest worden)**

```bash
git add -A
git commit -m "test(android): verify offline-first Strava sync, auto-refresh, sort"
```

---

## Task 9: Documentatie bijwerken

**Files:**
- Modify: `Documentation/ARCHITECTURE.md`
- Modify: `README.md` (indien aanwezig en relevant)

Per de projectregels: werk `Documentation/ARCHITECTURE.md` bij wanneer de architectuur materieel verandert.

- [ ] **Step 1: Beschrijf de nieuwe sync-flow**

Voeg in `Documentation/ARCHITECTURE.md` (sync-sectie) een korte beschrijving toe:
- `RouteSyncWorker` delegeert naar `SyncOrchestrator`.
- Volgorde: **Strava-pull eerst** (offline-first, los van het horloge) → progress-melding → **opportunistische** payload-send (mag falen/overslaan zonder de sync te laten mislukken).
- De UI observeert `SyncScheduler.manualSyncInfo(...)` en herlaadt de catalogus zodra de pull klaar is.
- Routelijst-sortering is door de gebruiker instelbaar (`RouteSorting`, bewaard in `SharedPreferences` onder `route_sort_mode`).

- [ ] **Step 2: Commit**

```bash
git add Documentation/ARCHITECTURE.md README.md
git commit -m "docs: describe offline-first sync flow, auto-refresh and sort"
```

---

## Self-review (uitgevoerd bij het schrijven van dit plan)

**Spec-dekking:**
- "syncen vanaf Strava beter laten werken / doet hij niet goed" → Task 2 + Task 3 verplaatsen de Strava-pull vóór de horlogeverbinding (root cause van "niks verschijnt"); Task 1 geeft betrouwbare telling.
- "wanneer goed gesynct → UI laat nieuwe bestanden zien" → Task 4 (observeerbare job) + Task 7 (auto-verversing + feedback).
- "onderaan" / "ik wil dit zelf kunnen instellen" → Task 5/6/7: instelbare sorteervolgorde, standaard nieuwste-onderaan.

**Placeholder-scan:** geen TBD/TODO; alle stappen bevatten volledige code of exacte commando's.

**Type-consistentie:** `RouteSorting.SORT_IMPORT_ASC/DESC/NAME_ASC` consistent gebruikt in helper, ViewModel en Activity-dialooglabels (index-volgorde komt overeen). `RouteSyncWorker.KEY_PULL_DONE/KEY_CHANGED/KEY_WATCH_SENT` consistent tussen worker (set) en Activity (read). `SyncOrchestrator`-interfaces (`RouteSource`, `WatchSender`, `PayloadJob`, `ProgressListener`) en `Result`-velden consistent tussen klasse, test en worker-bedrading. `syncRoutes()` → `int` consistent in repository, test en worker-`RouteSource`.

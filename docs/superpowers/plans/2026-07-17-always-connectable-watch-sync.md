# Always-Connectable Watch Sync Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The watch widget can always reach the phone app (route list, route load, set-active) — even when the ClimbPro Android app is closed, killed, or the phone has just rebooted.

**Architecture:** Today, watch → phone messages are only delivered while the app process is alive: `ConnectIqClient` registers a runtime listener (`registerForAppEvents(device, app, listener)`) whose underlying broadcast receiver dies with the process. The vendored CIQ Mobile SDK (`ciq-mobile-sdk.aar`) ships an unused second delivery path: an exported `IQGarminBindingService` plus `ConnectIQ.registerAppToUseBinderService(appId)`. Once registered, Garmin Connect Mobile (GCM) delivers every watch message by **binding into our app's process** — and Android starts a dead process to serve a bind. The GCM-side registration is stored per *package* (not per process) and is not removed by `ConnectIQ.shutdown()` (verified in the AAR bytecode), so it survives process death and app updates. We (1) register binder-service delivery on the phone, (2) add a watch-side LIST_ROUTES retry so the very first message — which wakes the dead process but arrives before the SDK finishes initialising — is retransmitted a few seconds later, and (3) keep the registration fresh across reboots with an unconstrained lightweight WorkManager job plus a BOOT_COMPLETED receiver.

**Tech Stack:** Java (Android, WorkManager), Monkey C (Connect IQ widget), vendored CIQ Mobile SDK AAR.

## Global Constraints

- Android language: **Java** (`final` fields, POJOs) — no Kotlin.
- Watch target: **Forerunner 255 Music** (`fr255m` device profile).
- Widget CIQ app id: `fedcba9876543210fedcba9876543210` (`ConnectIqAppId.VALUE`, duplicated in `garmin-widget/manifest.xml`).
- **No wire-format change** in this plan: LIST_ROUTES / ROUTE_LIST / HELLO messages are unchanged, so `protocol/schema.json`, `protocol/examples/` and the Monkey C parsers stay untouched.
- The locked decision "Background sync: WorkManager periodic with charging + unmetered constraints" governs *route sync* (`RouteSyncWorker`). The new rebind worker is deliberately unconstrained — it does no network or heavy work; document this in ARCHITECTURE.md (Task 4).
- Java tests: `cd android; .\gradlew.bat test --tests "<FQCN>"` (Windows PowerShell).
- Monkey C tests: `pwsh -File tools/run-monkeyc-tests.ps1` (starts simulator, runs all four suites; currently 157 passing).
- When architecture changes materially, update `Documentation/ARCHITECTURE.md` and `README.md` in the same change.
- Commit style (from git history): `fix(android): …`, `feat(watch): …`, etc. End commit messages with `Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>`.

## Verified SDK facts this plan relies on

Confirmed by disassembling `android/app/libs/ciq-mobile-sdk.aar` (javap):

1. The AAR's manifest declares `com.garmin.android.connectiq.IQGarminBindingService` (exported, action `com.garmin.android.connectiq.GARMIN_BINDING_SERVICE_ACTION`) — merged into our app manifest automatically. GCM binds to it and calls `ICompanionAppService.transferData(json)`.
2. `transferData`'s INCOMING_MESSAGE branch dispatches to `ConnectIQ.getInstance().getApplicationEventListener()` — the listener set via the **one-arg** `ConnectIQ.registerForAppEvents(IQApplicationEventListener)`. If that listener is null the message is **silently dropped** (logged "Application event listener is not set.", returns SUCCESS). This is why the watch-side retry (Task 2) exists.
3. `registerAppToUseBinderService(appId)` requires an initialised+bound SDK (`verifyInitialized`), calls GCM's AIDL `registerAppWithBindingService(appId, packageName, "com.garmin.android.connectiq.GARMIN_BINDING_SERVICE_ACTION")`, sets `mUsingBinderService = true`, and unregisters the legacy broadcast receiver. On an old GCM lacking the AIDL method the call throws `ServiceUnavailableException` **before** touching the receiver → the legacy path stays intact (our fallback).
4. `shutdown()` does **not** call `unregisterAppWithBindingService` → GCM keeps routing to our package after `forceRebind()`/process death.
5. SEND_MESSAGE_STATUS and DEVICE_STATUS also flow through `transferData` and are routed to the SDK's internal `IQMessageReceiver` listener containers, so `sendMessage` callbacks, blocking sends, and `registerForDeviceEvents` keep working unchanged in binder mode.
6. `transferData` calls the **no-arg** `ConnectIQ.getInstance()`, which NPEs if no instance was ever created in this process. Safe for us: `ClimbProApplication.onCreate()` constructs `ConnectIqClient`, whose constructor calls `ConnectIQ.getInstance(context, WIRELESS)` synchronously — and `Application.onCreate` always runs before a bind is served.

Cold-start timeline after this plan: watch sends LIST_ROUTES → GCM binds `IQGarminBindingService` → Android starts our process → `Application.onCreate` (constructs client, `forceRebind()` schedules connect at +1 s) → first message dropped (listener not yet set) → SDK initialised ≈ +2–4 s, binder listener registered, HELLO sent → widget retransmits LIST_ROUTES at +3 s / +6 s (Task 2) and independently re-requests on HELLO → phone answers → route list appears well inside the widget's 10 s window.

## File Structure

| File | Responsibility |
|---|---|
| `android/app/src/main/java/nl/paree/climbpro/connectiq/ConnectIqClient.java` (modify) | Register binder-service delivery; global listener filtered to the widget app id; broadcast fallback |
| `android/app/src/test/java/nl/paree/climbpro/connectiq/ConnectIqClientBinderRoutingTest.java` (create) | Unit test for the app-id routing filter |
| `garmin-widget/source/SyncRetryPolicy.mc` (create) | Pure retry decision table (testable, no UI/timer deps) |
| `garmin-widget/source/SyncView.mc` (modify) | 1 Hz tick loop driving retransmit / give-up via the policy |
| `garmin-widget/test/SyncRetryPolicyTest.mc` (create) | Simulator tests for the policy |
| `android/app/src/main/java/nl/paree/climbpro/service/CiqRebindWorker.java` (create) | Lightweight worker: wake process, wait for CIQ connect (which re-registers), exit |
| `android/app/src/main/java/nl/paree/climbpro/service/RebindScheduler.java` (create) | Unconstrained periodic + one-shot scheduling for the rebind worker |
| `android/app/src/main/java/nl/paree/climbpro/BootCompletedReceiver.java` (create) | Immediate rebind after reboot |
| `android/app/src/test/java/nl/paree/climbpro/service/CiqRebindWorkerTest.java` (create) | Unit test for the connect-wait loop |
| `android/app/src/main/AndroidManifest.xml` (modify) | BOOT permission + receiver |
| `android/app/src/main/java/nl/paree/climbpro/ClimbProApplication.java` (modify) | Schedule periodic rebind |
| `Documentation/ARCHITECTURE.md`, `README.md`, `CLAUDE.md` (modify) | Document background delivery; refresh test counts |

---

### Task 1: Binder-service delivery in ConnectIqClient (phone)

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/connectiq/ConnectIqClient.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/connectiq/ConnectIqClientBinderRoutingTest.java`

**Interfaces:**
- Consumes: `ConnectIqAppId.VALUE` (existing constant, `connectiq/ConnectIqAppId.java`); vendored SDK methods `ConnectIQ.registerForAppEvents(IQApplicationEventListener)` and `ConnectIQ.registerAppToUseBinderService(String)`.
- Produces: package-private `static boolean ConnectIqClient.isWidgetApp(String applicationId)` (used by the test); no signature changes to any public method — `WatchRequestHandler`, workers, and view models are untouched.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/nl/paree/climbpro/connectiq/ConnectIqClientBinderRoutingTest.java`:

```java
package nl.paree.climbpro.connectiq;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Pure unit test for the binder-delivery routing filter. Does NOT construct a
 * ConnectIqClient (that would init the SDK singleton) — it only calls the
 * static helper, so no Garmin SDK / Android runtime is required.
 *
 * Background: with binder-service delivery there is ONE global application-event
 * listener for the whole app, receiving messages for any of our CIQ app ids.
 * Only the widget (sync counterpart) ever transmits to the phone, so everything
 * else must be ignored. GCM has been observed reporting app ids in upper case
 * and with dashes — the filter must normalise both.
 */
public class ConnectIqClientBinderRoutingTest {

    @Test
    public void isWidgetApp_matchesExactWidgetId() {
        assertTrue(ConnectIqClient.isWidgetApp(ConnectIqAppId.VALUE));
    }

    @Test
    public void isWidgetApp_matchesUppercaseAndDashedForms() {
        assertTrue(ConnectIqClient.isWidgetApp(ConnectIqAppId.VALUE.toUpperCase()));
        assertTrue(ConnectIqClient.isWidgetApp(
                "fedcba98-7654-3210-fedc-ba9876543210"));
    }

    @Test
    public void isWidgetApp_rejectsOtherAppIdsAndNull() {
        assertFalse(ConnectIqClient.isWidgetApp(ConnectIqAppId.DATAFIELD));
        assertFalse(ConnectIqClient.isWidgetApp(ConnectIqAppId.SURFACE_FIELD));
        assertFalse(ConnectIqClient.isWidgetApp(ConnectIqAppId.ONBOARD));
        assertFalse(ConnectIqClient.isWidgetApp(null));
        assertFalse(ConnectIqClient.isWidgetApp(""));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android; .\gradlew.bat test --tests "nl.paree.climbpro.connectiq.ConnectIqClientBinderRoutingTest"`
Expected: FAIL — compilation error, `isWidgetApp` not defined.

- [ ] **Step 3: Implement binder delivery in ConnectIqClient**

Three edits in `ConnectIqClient.java`.

**Edit A** — add the field, the filter helper, and the registration method. Insert after the `helloSent` field declaration (below the `private volatile boolean helloSent;` line):

```java
    /** True once GCM accepted binder-service delivery for this session. */
    private volatile boolean binderDelivery;
```

Insert the following two members after the `helloMessage()` static factory:

```java
    /**
     * True when {@code applicationId} identifies the widget (the only watch app
     * that transmits to the phone). GCM reports app ids in varying case and
     * sometimes with dashes; normalise before comparing.
     */
    static boolean isWidgetApp(String applicationId) {
        return applicationId != null
                && applicationId.replace("-", "").equalsIgnoreCase(ConnectIqAppId.VALUE);
    }

    /**
     * Ask GCM to deliver watch messages through the SDK's IQGarminBindingService
     * instead of the runtime broadcast receiver. GCM then binds into this app's
     * process for every incoming message — starting the process if it is dead —
     * so the watch can reach us while the app is closed. The registration is
     * stored GCM-side per package: it survives our process death and app
     * updates, and SDK shutdown() does not remove it. On an old GCM without the
     * AIDL call this throws before touching the broadcast receiver, so returning
     * false leaves the legacy per-device path fully usable as fallback.
     */
    private boolean tryRegisterBinderDelivery() {
        try {
            // Listener first: never leave a window where GCM binder-delivers
            // into a null listener (the SDK silently drops those messages).
            connectIQ.registerForAppEvents((dev, app, data, status) -> {
                if (app != null && isWidgetApp(app.getApplicationId())) {
                    dispatchIncoming(data);
                }
            });
            connectIQ.registerAppToUseBinderService(ConnectIqAppId.VALUE);
            Log.i(TAG, "Binder-service delivery registered — watch can wake this app");
            return true;
        } catch (InvalidStateException | ServiceUnavailableException e) {
            Log.w(TAG, "Binder-service delivery unavailable — using broadcast path", e);
            return false;
        }
    }
```

**Edit B** — in `handleSdkReady()`, replace the initial app-event registration. The current code:

```java
            connectIQ.registerForAppEvents(device, iqApp,
                    (dev, app, data, status) -> dispatchIncoming(data));
```

becomes:

```java
            binderDelivery = tryRegisterBinderDelivery();
            if (!binderDelivery) {
                connectIQ.registerForAppEvents(device, iqApp,
                        (dev, app, data, status) -> dispatchIncoming(data));
            }
```

**Edit C** — in the `registerForDeviceEvents` reconnect callback inside `handleSdkReady()`, guard the re-registration (binder registrations live in GCM and need no refresh on reconnect; calling the per-device overload in binder mode would add dead listeners). The current block:

```java
                if (nowConnected) {
                    // Re-register in case the CIQ session was reset during the disconnect.
                    try {
                        connectIQ.registerForAppEvents(device, iqApp,
                                (d, a, data, st) -> dispatchIncoming(data));
                    } catch (InvalidStateException | ServiceUnavailableException e) {
                        Log.w(TAG, "Re-register app events after reconnect failed", e);
                    }
                    maybeSendHello();
                }
```

becomes:

```java
                if (nowConnected) {
                    // Re-register in case the CIQ session was reset during the
                    // disconnect. Binder-mode registrations live GCM-side and
                    // need no refresh.
                    if (!binderDelivery) {
                        try {
                            connectIQ.registerForAppEvents(device, iqApp,
                                    (d, a, data, st) -> dispatchIncoming(data));
                        } catch (InvalidStateException | ServiceUnavailableException e) {
                            Log.w(TAG, "Re-register app events after reconnect failed", e);
                        }
                    }
                    maybeSendHello();
                }
```

Also reset the flag alongside the other session state in `forceRebind()` and `disconnect()` — in both methods, next to `helloSent = false;` / `device = null;`, add:

```java
        binderDelivery = false;
```

(`forceRebind()` already sets `helloSent = false`; `disconnect()` does not touch `helloSent` — there, add only the `binderDelivery = false;` line after `device = null;`.)

- [ ] **Step 4: Run tests to verify they pass**

Run: `cd android; .\gradlew.bat test --tests "nl.paree.climbpro.connectiq.ConnectIqClientBinderRoutingTest"`
Expected: PASS (3 tests).

Then run the full Java suite to catch regressions (existing `ConnectIqClientHelloTest` etc. must stay green):

Run: `cd android; .\gradlew.bat test`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/connectiq/ConnectIqClient.java android/app/src/test/java/nl/paree/climbpro/connectiq/ConnectIqClientBinderRoutingTest.java
git commit -m "feat(android): binder-service CIQ delivery so the watch can wake a closed app

GCM now delivers watch messages by binding into IQGarminBindingService
(vendored in the CIQ SDK aar), which starts a dead process. Registration
is GCM-side per package and survives process death; legacy broadcast
path kept as fallback for old GCM versions.

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Widget-side LIST_ROUTES retry (watch)

The first LIST_ROUTES after a cold wake is dropped inside the SDK (see "Verified SDK facts" #2) while the phone process boots and initialises the SDK (~2–4 s). Retransmit at 3 s and 6 s inside the widget's existing 10 s wait window. The decision logic goes into a pure, simulator-testable policy class; `SyncView` only drives a 1 Hz timer.

**Files:**
- Create: `garmin-widget/source/SyncRetryPolicy.mc`
- Modify: `garmin-widget/source/SyncView.mc`
- Test: `garmin-widget/test/SyncRetryPolicyTest.mc`

**Interfaces:**
- Consumes: nothing new — `Comm.transmit`, `CommListener`, `App.getApp().phoneRouteIndex.received` all exist.
- Produces: `SyncRetryPolicy.actionForTick(tick, received)` → returns symbol `:wait`, `:retransmit`, or `:giveUp`. `tick` is a 1-based integer (seconds since `onShow`), `received` is a Boolean.

- [ ] **Step 1: Write the failing test**

Create `garmin-widget/test/SyncRetryPolicyTest.mc`:

```monkeyc
using Toybox.Test;

// SyncRetryPolicy: pure decision table for SyncView's 1 Hz wait loop.

(:test)
function syncRetry_retransmitsAt3And6(logger) {
    Test.assertEqual(SyncRetryPolicy.actionForTick(3, false), :retransmit);
    Test.assertEqual(SyncRetryPolicy.actionForTick(6, false), :retransmit);
    return true;
}

(:test)
function syncRetry_waitsOnOtherTicksBeforeTimeout(logger) {
    Test.assertEqual(SyncRetryPolicy.actionForTick(1, false), :wait);
    Test.assertEqual(SyncRetryPolicy.actionForTick(2, false), :wait);
    Test.assertEqual(SyncRetryPolicy.actionForTick(4, false), :wait);
    Test.assertEqual(SyncRetryPolicy.actionForTick(5, false), :wait);
    Test.assertEqual(SyncRetryPolicy.actionForTick(7, false), :wait);
    Test.assertEqual(SyncRetryPolicy.actionForTick(9, false), :wait);
    return true;
}

(:test)
function syncRetry_givesUpFromTick10(logger) {
    Test.assertEqual(SyncRetryPolicy.actionForTick(10, false), :giveUp);
    Test.assertEqual(SyncRetryPolicy.actionForTick(11, false), :giveUp);
    return true;
}

(:test)
function syncRetry_neverActsOnceReceived(logger) {
    Test.assertEqual(SyncRetryPolicy.actionForTick(3, true), :wait);
    Test.assertEqual(SyncRetryPolicy.actionForTick(6, true), :wait);
    Test.assertEqual(SyncRetryPolicy.actionForTick(10, true), :wait);
    return true;
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `pwsh -File tools/run-monkeyc-tests.ps1`
Expected: garmin-widget build FAILS with an undefined-symbol error for `SyncRetryPolicy` (script exits non-zero).

- [ ] **Step 3: Implement SyncRetryPolicy**

Create `garmin-widget/source/SyncRetryPolicy.mc`:

```monkeyc
// Pure decision table for SyncView's 1 Hz wait loop. The first LIST_ROUTES can
// wake a phone whose ClimbPro process was dead; that wake takes a few seconds
// during which the request itself is dropped inside the phone SDK. A retransmit
// at 3 s and 6 s lands after the phone finished (re)connecting; from 10 s the
// widget gives up and falls back to the cached route list (same timeout as the
// old single-shot timer).
class SyncRetryPolicy {

    // Returns :wait, :retransmit or :giveUp for a 1-based tick (seconds shown).
    static function actionForTick(tick, received) {
        if (received) { return :wait; }  // onUpdate handles the view switch
        if (tick >= 10) { return :giveUp; }
        if (tick == 3 || tick == 6) { return :retransmit; }
        return :wait;
    }
}
```

- [ ] **Step 4: Rewire SyncView onto a 1 Hz repeating timer**

In `garmin-widget/source/SyncView.mc`, replace the `SyncView` class (leave `SyncDelegate` untouched):

```monkeyc
class SyncView extends Ui.View {

    hidden var timer;
    hidden var tick = 0;
    hidden var switched = false;

    function initialize() { View.initialize(); }

    function onShow() {
        App.getApp().phoneRouteIndex.received = false;
        Comm.transmit({ "type" => "LIST_ROUTES" }, null, new CommListener());
        tick = 0;
        timer = new Timer.Timer();
        timer.start(method(:onTick), 1000, true);
    }

    function onHide() {
        if (timer != null) { timer.stop(); timer = null; }
    }

    function onTick() as Void {
        tick++;
        var index = App.getApp().phoneRouteIndex;
        var received = (index != null && index.received);
        var action = SyncRetryPolicy.actionForTick(tick, received);
        if (action == :retransmit) {
            Comm.transmit({ "type" => "LIST_ROUTES" }, null, new CommListener());
        } else if (action == :giveUp && !switched) {
            switched = true;
            Ui.switchToView(new RouteListView(), new RouteListDelegate(), Ui.SLIDE_LEFT);
        }
        Ui.requestUpdate();
    }

    function onUpdate(dc) {
        dc.setColor(Gfx.COLOR_BLACK, Gfx.COLOR_BLACK);
        dc.clear();

        if (!switched) {
            var phoneIndex = App.getApp().phoneRouteIndex;
            if (phoneIndex != null && phoneIndex.received) {
                switched = true;
                if (timer != null) { timer.stop(); timer = null; }
                Ui.switchToView(new RouteListView(), new RouteListDelegate(), Ui.SLIDE_LEFT);
                return;
            }
        }

        var w = dc.getWidth();
        var h = dc.getHeight();
        dc.setColor(Gfx.COLOR_WHITE, Gfx.COLOR_TRANSPARENT);
        dc.drawText(w / 2, h / 2, Gfx.FONT_SMALL,
            "Connecting...",
            Gfx.TEXT_JUSTIFY_CENTER | Gfx.TEXT_JUSTIFY_VCENTER);
    }
}
```

(The old `onTimeout` single-shot method is deleted; give-up now flows through `onTick`. `onUpdate` is byte-for-byte the existing implementation.)

- [ ] **Step 5: Run all Monkey C suites to verify they pass**

Run: `pwsh -File tools/run-monkeyc-tests.ps1`
Expected: all four suites PASS; garmin-widget count rises from 33 to 37 (total 161). No failures in the other modules.

- [ ] **Step 6: Commit**

```bash
git add garmin-widget/source/SyncRetryPolicy.mc garmin-widget/source/SyncView.mc garmin-widget/test/SyncRetryPolicyTest.mc
git commit -m "feat(watch-widget): retransmit LIST_ROUTES at 3s/6s to cover phone cold wake

The first request wakes a dead phone process but is dropped inside the
phone SDK before its listener exists; the retries land after connect.
Give-up timeout stays at 10s (cached route list fallback).

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: Rebind worker + boot receiver (phone)

Binder registration happens whenever the app process connects. To guarantee it also exists after a phone reboot (or a GCM update losing state) *without the user opening the app*, schedule a lightweight unconstrained periodic worker — starting the worker starts the process, `Application.onCreate` triggers the connect, and connecting re-registers. A BOOT_COMPLETED receiver removes the up-to-6-hour post-reboot gap. (WorkManager persists periodic jobs across reboots on its own; the receiver is only for immediacy.)

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/service/CiqRebindWorker.java`
- Create: `android/app/src/main/java/nl/paree/climbpro/service/RebindScheduler.java`
- Create: `android/app/src/main/java/nl/paree/climbpro/BootCompletedReceiver.java`
- Modify: `android/app/src/main/AndroidManifest.xml`
- Modify: `android/app/src/main/java/nl/paree/climbpro/ClimbProApplication.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/service/CiqRebindWorkerTest.java`

**Interfaces:**
- Consumes: `ClimbProApplication.connectIqClient()` (existing app-scoped client), `ConnectIqClient.isConnected()`.
- Produces: `CiqRebindWorker` (a `androidx.work.Worker`); package-private `static boolean CiqRebindWorker.awaitConnected(java.util.function.BooleanSupplier connected, long timeoutMs, long pollMs)`; `RebindScheduler.schedulePeriodicRebind(Context)` and `RebindScheduler.triggerImmediateRebind(Context)`.

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/nl/paree/climbpro/service/CiqRebindWorkerTest.java`:

```java
package nl.paree.climbpro.service;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Pure unit test for the connect-wait loop. Does not construct the Worker
 * (that needs a WorkManager context) — only the static helper.
 */
public class CiqRebindWorkerTest {

    @Test
    public void awaitConnected_immediateWhenAlreadyConnected() {
        assertTrue(CiqRebindWorker.awaitConnected(() -> true, 1_000, 1));
    }

    @Test
    public void awaitConnected_succeedsWhenConnectionComesUpDuringWait() {
        AtomicInteger polls = new AtomicInteger();
        assertTrue(CiqRebindWorker.awaitConnected(
                () -> polls.incrementAndGet() > 3, 1_000, 1));
    }

    @Test
    public void awaitConnected_falseOnTimeout() {
        assertFalse(CiqRebindWorker.awaitConnected(() -> false, 50, 10));
    }

    @Test
    public void awaitConnected_falseOnInterrupt() {
        Thread.currentThread().interrupt();
        try {
            assertFalse(CiqRebindWorker.awaitConnected(() -> false, 10_000, 10));
        } finally {
            // clear the flag so later tests on this thread aren't poisoned
            Thread.interrupted();
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `cd android; .\gradlew.bat test --tests "nl.paree.climbpro.service.CiqRebindWorkerTest"`
Expected: FAIL — compilation error, `CiqRebindWorker` not defined.

- [ ] **Step 3: Implement CiqRebindWorker**

Create `android/app/src/main/java/nl/paree/climbpro/service/CiqRebindWorker.java`:

```java
package nl.paree.climbpro.service;

import android.util.Log;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.function.BooleanSupplier;

import nl.paree.climbpro.ClimbProApplication;
import nl.paree.climbpro.connectiq.ConnectIqClient;

/**
 * Lightweight keep-registered worker. Starting this worker starts the app
 * process; {@code ClimbProApplication.onCreate} then kicks off the Connect IQ
 * (re)connect, and connecting re-registers binder-service delivery with Garmin
 * Connect Mobile — the registration that lets the watch wake this app while it
 * is closed. This worker only keeps the process alive long enough for that to
 * finish. It does no sync work (RouteSyncWorker owns that) and is deliberately
 * unconstrained: no network or charging requirement, because it must also run
 * on battery, offline, and shortly after boot.
 */
public final class CiqRebindWorker extends Worker {

    private static final String TAG = "CiqRebindWorker";
    private static final long CONNECT_TIMEOUT_MS = 30_000;
    private static final long POLL_MS = 250;

    public CiqRebindWorker(@NonNull Context context, @NonNull WorkerParameters params) {
        super(context, params);
    }

    @NonNull
    @Override
    public Result doWork() {
        ConnectIqClient client =
                ((ClimbProApplication) getApplicationContext()).connectIqClient();
        boolean connected = awaitConnected(client::isConnected, CONNECT_TIMEOUT_MS, POLL_MS);
        Log.i(TAG, "Rebind pass done, connected=" + connected);
        // Best effort: the watch may simply be out of range or GCM not up yet.
        // The next periodic run tries again — never retry-loop here.
        return Result.success();
    }

    /** Poll {@code connected} every {@code pollMs} until true or {@code timeoutMs} elapsed. */
    static boolean awaitConnected(BooleanSupplier connected, long timeoutMs, long pollMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (!connected.getAsBoolean()) {
            if (System.currentTimeMillis() >= deadline) {
                return false;
            }
            try {
                Thread.sleep(pollMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `cd android; .\gradlew.bat test --tests "nl.paree.climbpro.service.CiqRebindWorkerTest"`
Expected: PASS (4 tests).

- [ ] **Step 5: Add RebindScheduler and BootCompletedReceiver, wire manifest + Application**

Create `android/app/src/main/java/nl/paree/climbpro/service/RebindScheduler.java`:

```java
package nl.paree.climbpro.service;

import android.content.Context;

import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;

import java.util.concurrent.TimeUnit;

/**
 * Schedules {@link CiqRebindWorker}. Unlike {@link SyncScheduler} this has NO
 * charging/network constraints — its whole point is to run unconditionally so
 * the GCM binder-service registration stays fresh (post-reboot, post-GCM-update)
 * even when the user never opens the app.
 */
public final class RebindScheduler {

    private static final String PERIODIC_TAG   = "climbpro_periodic_rebind";
    private static final String UNIQUE_BOOT    = "climbpro_boot_rebind";
    private static final long   INTERVAL_HOURS = 6;

    private RebindScheduler() {}

    public static void schedulePeriodicRebind(Context context) {
        PeriodicWorkRequest work = new PeriodicWorkRequest.Builder(
                CiqRebindWorker.class, INTERVAL_HOURS, TimeUnit.HOURS)
                .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_TAG,
                ExistingPeriodicWorkPolicy.KEEP,
                work);
    }

    /** Immediate one-shot rebind (used right after boot). */
    public static void triggerImmediateRebind(Context context) {
        OneTimeWorkRequest work = new OneTimeWorkRequest.Builder(CiqRebindWorker.class)
                .build();
        WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_BOOT,
                ExistingWorkPolicy.REPLACE,
                work);
    }
}
```

Create `android/app/src/main/java/nl/paree/climbpro/BootCompletedReceiver.java`:

```java
package nl.paree.climbpro;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import nl.paree.climbpro.service.RebindScheduler;

/**
 * Re-registers Connect IQ binder-service delivery right after boot so the watch
 * can reach the app without the user opening it first. WorkManager replays the
 * periodic rebind on its own after reboot; this receiver only removes the
 * up-to-6-hour gap. BOOT_COMPLETED is a protected system broadcast, so
 * exported="false" is safe (the system is exempt from export checks — same
 * pattern as WorkManager's own RescheduleReceiver).
 */
public final class BootCompletedReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            RebindScheduler.triggerImmediateRebind(context);
        }
    }
}
```

In `android/app/src/main/AndroidManifest.xml`, add the permission after the existing `POST_NOTIFICATIONS` line:

```xml
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

and the receiver inside `<application>`, after the FileProvider `</provider>`:

```xml
        <!-- Refresh CIQ binder-service registration immediately after reboot. -->
        <receiver
            android:name=".BootCompletedReceiver"
            android:exported="false">
            <intent-filter>
                <action android:name="android.intent.action.BOOT_COMPLETED" />
            </intent-filter>
        </receiver>
```

In `android/app/src/main/java/nl/paree/climbpro/ClimbProApplication.java`, add the periodic schedule at the end of `onCreate()` (after `ciqClient.forceRebind();`):

```java
        // Keep the GCM binder-service registration fresh even if the user never
        // opens the app (see RebindScheduler / CiqRebindWorker).
        nl.paree.climbpro.service.RebindScheduler.schedulePeriodicRebind(this);
```

- [ ] **Step 6: Run the full Java suite and assemble**

Run: `cd android; .\gradlew.bat test`
Expected: BUILD SUCCESSFUL (all existing tests + 3 binder-routing + 4 rebind tests).

Run: `cd android; .\gradlew.bat assembleDebug`
Expected: BUILD SUCCESSFUL (catches manifest merge errors).

- [ ] **Step 7: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/service/CiqRebindWorker.java android/app/src/main/java/nl/paree/climbpro/service/RebindScheduler.java android/app/src/main/java/nl/paree/climbpro/BootCompletedReceiver.java android/app/src/test/java/nl/paree/climbpro/service/CiqRebindWorkerTest.java android/app/src/main/AndroidManifest.xml android/app/src/main/java/nl/paree/climbpro/ClimbProApplication.java
git commit -m "feat(android): unconstrained rebind worker + boot receiver keep CIQ wake-registration fresh

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: Documentation

**Files:**
- Modify: `Documentation/ARCHITECTURE.md`
- Modify: `README.md`
- Modify: `CLAUDE.md` (Monkey C test count only)

**Interfaces:**
- Consumes: final test counts from the Task 2 simulator run.
- Produces: nothing consumed by other tasks — this is the closing docs task.

- [ ] **Step 1: Document background delivery in ARCHITECTURE.md**

In `Documentation/ARCHITECTURE.md`, find the phone↔watch sync / Communications section and add a subsection (adapt heading level to the surrounding document):

```markdown
### Background message delivery (watch can wake the phone app)

Watch → phone requests do not require the ClimbPro Android app to be running.
On every successful SDK connect, `ConnectIqClient` registers **binder-service
delivery** with Garmin Connect Mobile (`registerAppToUseBinderService`): GCM
then delivers each watch message by binding into the app's
`IQGarminBindingService` (declared by the vendored CIQ SDK AAR), which starts
the app process if it is dead. The registration is stored GCM-side per package,
survives process death and app updates, and is not removed by SDK `shutdown()`.
On GCM versions without this AIDL call, the client falls back to the legacy
per-device broadcast registration (foreground-only, pre-existing behaviour).

Two support mechanisms close the remaining gaps:

- **Cold-wake race** — the message that wakes a dead process is dropped inside
  the SDK before the listener exists (~2–4 s init). The widget's sync screen
  therefore retransmits `LIST_ROUTES` at 3 s and 6 s (`SyncRetryPolicy`), and
  the phone's `HELLO` on connect independently triggers a re-request.
- **Reboot / GCM restart** — `CiqRebindWorker` (scheduled by `RebindScheduler`:
  periodic 6 h + one-shot from `BootCompletedReceiver`) starts the app process
  so the connect path re-registers. It is deliberately **unconstrained**
  WorkManager work: the "charging + unmetered" rule applies to route *sync*
  (`RouteSyncWorker`), not to this no-network, sub-30-second registration
  refresh.
```

- [ ] **Step 2: Update README.md**

In `README.md`, in the section describing phone↔watch sync (or the feature list), add one line:

```markdown
- **Watch werkt ook als de telefoon-app dicht is** — Garmin Connect Mobile wekt
  de ClimbPro-app op zodra het horloge een verzoek stuurt (binder-service
  delivery); de routelijst op het horloge werkt dus altijd, ook na een reboot.
```

(Match the README's actual language/tone — if the surrounding section is English, translate accordingly.)

- [ ] **Step 3: Update the Monkey C test count in CLAUDE.md**

In `CLAUDE.md`, update the sentence `Current status: **157 tests pass** (garmin 71, garmin-widget 33, garmin-surface 26, garmin-onboard 27)` with the real numbers printed by the Task 2 run of `pwsh -File tools/run-monkeyc-tests.ps1` (expected: 161 total, garmin-widget 37), and append `widget sync-retry policy` to the edge-case coverage list in that same sentence.

- [ ] **Step 4: Commit**

```bash
git add Documentation/ARCHITECTURE.md README.md CLAUDE.md
git commit -m "docs: document background CIQ delivery (watch wakes closed phone app)

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Manual end-to-end verification (after all tasks)

Automated tests cannot exercise GCM's process-wake. On the real hardware:

1. `cd android; .\gradlew.bat assembleDebug` and install on the phone; open the app once (registers binder delivery — check logcat for "Binder-service delivery registered").
2. Swipe ClimbPro away from recents (process killed). Verify with `adb shell pidof nl.paree.climbpro` → empty.
3. On the FR255M, open the ClimbPro widget → "Connecting…" must resolve to the live phone route list (not the cached fallback) within ~10 s. `pidof` now returns a pid.
4. Reboot the phone, do **not** open ClimbPro, wait ~1 min, repeat step 3.
5. Regression: with the app open in the foreground, the route list must appear near-instantly as before.

If step 3 fails but step 5 works, capture `adb logcat -s ConnectIqClient IQGarminBindingService` and check whether `transferData` fired (delivery works, app-side race) or not (GCM registration missing).

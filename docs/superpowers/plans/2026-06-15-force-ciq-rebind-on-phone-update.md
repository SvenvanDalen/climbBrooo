# Force Connect IQ Rebind on Phone-Only Update — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the phone⇄watch connection survive a phone-only app update, so the watch's "Telefoon" route list never silently stays empty after you upload a new Android build without re-sideloading the watch apps.

**Architecture:** The UUID is *already* hardcoded and matches on both sides (`ConnectIqAppId.VALUE` == `garmin-widget/manifest.xml` id == `fedcba9876543210fedcba9876543210`), so the bug is **not** a UUID problem. The real cause (verified 2026-06-13) is a stale Garmin Connect Mobile (GCM) message binding: reinstalling only the Android app leaves GCM routing the watch's `LIST_ROUTES` to the dead old process. Fix it on startup by **forcing the SDK to shut down and re-initialize** (clearing GCM's stale binding so the fresh process rebinds), then **sending a one-time `HELLO`** control message to the widget right after connecting. The widget treats `HELLO` as "phone (re)connected" and re-requests its route list — closing the loop automatically if the widget is open.

**Tech Stack:** Java (Android, MVVM), Garmin Connect IQ Mobile SDK (`.aar`), Monkey C (watch widget), JUnit 4 + Mockito for unit tests.

---

## Background facts (read before starting)

- **No UUID change is needed.** `android/app/src/main/java/nl/paree/climbpro/connectiq/ConnectIqAppId.java:17` already hardcodes the widget UUID and `garmin-widget/manifest.xml:7` already matches it. Do **not** introduce a new UUID; that would not fix the symptom and would *break* the existing watch builds.
- **`HELLO` is a control message, not a wire-payload change.** `protocol/schema.json` governs only the v3 climb payload; control messages (`LIST_ROUTES`, `ROUTE_LIST`, `ACTIVE_SET`) live in code on both sides, not in the schema. So **do not edit `schema.json`** for this work. You add `HELLO` exactly like the existing control messages: in the phone sender and the Monkey C `CommListener` (per the protocol-pojos memory note).
- **Verification reality:** `ConnectIqClient` is a thin wrapper over the SDK singleton (`ConnectIQ.getInstance(...)`) and is **not** unit-tested in this repo (see `android/app/src/test/java/nl/paree/climbpro/connectiq/` — only `ConnectIqAppIdTest`, `PayloadCodecTest`, `WatchRequestHandlerTest` exist). There is **no Monkey C test harness** and `monkeyc` may not be installed. Therefore:
  - Pure logic (the `HELLO` message factory) gets a real unit test (Task 1).
  - SDK-wrapper glue (`forceRebind`, `HELLO` send) is verified **on-device via logcat** per `Documentation/CONNECTION.md` §7 (Task 2/3).
  - The Monkey C change is verified **by review + on-device** (Task 4).
  Be honest about this in commit messages — do not claim a unit test covers the SDK glue.

## File Structure

| File | Change | Responsibility |
|---|---|---|
| `android/app/src/main/java/nl/paree/climbpro/connectiq/ConnectIqClient.java` | Modify | Add `helloMessage()` factory, `forceRebind()`, one-time HELLO send on connect |
| `android/app/src/test/java/nl/paree/climbpro/connectiq/ConnectIqClientHelloTest.java` | Create | Unit-test the `HELLO` message factory |
| `android/app/src/main/java/nl/paree/climbpro/ClimbProApplication.java` | Modify | Call `forceRebind()` (not `connect()`) at startup |
| `garmin-widget/source/CommListener.mc` | Modify | Handle incoming `HELLO` → re-request `LIST_ROUTES` |
| `Documentation/CONNECTION.md` | Modify | Document the rebind+HELLO flow and add it to the troubleshooting table |

---

### Task 1: HELLO control-message factory (TDD)

A tiny, pure, package-private static factory for the `HELLO` control message. This is the one piece with real unit-test coverage; later tasks reuse it.

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/connectiq/ConnectIqClient.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/connectiq/ConnectIqClientHelloTest.java`

- [ ] **Step 1: Write the failing test**

Create `android/app/src/test/java/nl/paree/climbpro/connectiq/ConnectIqClientHelloTest.java`:

```java
package nl.paree.climbpro.connectiq;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Map;

/**
 * Pure unit test for the HELLO control-message factory. Does NOT construct a
 * ConnectIqClient (that would init the SDK singleton) — it only calls the static
 * factory, so no Garmin SDK / Android runtime is required.
 */
public class ConnectIqClientHelloTest {

    @Test
    public void helloMessage_hasOnlyHelloType() {
        Map<String, Object> m = ConnectIqClient.helloMessage();
        assertEquals("HELLO", m.get("type"));
        assertEquals(1, m.size());
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests nl.paree.climbpro.connectiq.ConnectIqClientHelloTest`
Expected: FAIL to compile — `cannot find symbol: method helloMessage()`.

- [ ] **Step 3: Add the factory (minimal implementation)**

In `ConnectIqClient.java`, the imports already include `java.util.LinkedHashMap` is NOT present — add it next to the existing `import java.util.Map;` (line 19):

```java
import java.util.LinkedHashMap;
import java.util.Map;
```

Add the constant and factory inside the class, just below the `TAG` field (around line 37):

```java
    /** Control message the phone sends to the widget right after (re)connecting. */
    static final String MSG_TYPE_HELLO = "HELLO";

    /**
     * The {@code HELLO} control message. Sent once per connection to prime the GCM
     * message binding to this (possibly freshly reinstalled) app process and to let
     * the widget refresh its route list. Package-private + static so it is unit
     * testable without initialising the SDK.
     */
    static Map<String, Object> helloMessage() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", MSG_TYPE_HELLO);
        return m;
    }
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests nl.paree.climbpro.connectiq.ConnectIqClientHelloTest`
Expected: PASS (1 test).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/connectiq/ConnectIqClient.java android/app/src/test/java/nl/paree/climbpro/connectiq/ConnectIqClientHelloTest.java
git commit -m "feat(ciq): add HELLO control-message factory"
```

---

### Task 2: forceRebind() + one-time HELLO on connect

Add the core fix to `ConnectIqClient`: a `forceRebind()` that shuts the SDK down (clearing GCM's stale binding) then reconnects after a short settle delay, and a `maybeSendHello()` that fires the `HELLO` exactly once per connection. **This is SDK-wrapper glue — verified on-device, not by a unit test.**

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/connectiq/ConnectIqClient.java`

- [ ] **Step 1: Add the `helloSent` flag**

Below the existing `private volatile boolean connected;` (line 49) add:

```java
    private volatile boolean helloSent;
```

- [ ] **Step 2: Reset `helloSent` when a new connect cycle starts**

In `connect()`, just after the `stateLd.postValue(ConnectIqState.CONNECTING);` line (line 74), reset the flag so a fresh connection re-sends HELLO:

```java
        helloSent = false;
```

- [ ] **Step 3: Add `maybeSendHello()` and call it everywhere we become CONNECTED**

Add this private method (place it just above `dispatchIncoming`, around line 136):

```java
    /** Send the HELLO control message once per connection, when first connected. */
    private void maybeSendHello() {
        if (!connected || device == null || helloSent) {
            return;
        }
        helloSent = true;
        Log.i(TAG, "Connected — sending HELLO to widget to prime GCM binding");
        sendMessage(helloMessage());
    }
```

In `handleSdkReady()`, immediately after the block that sets the initial connected state (right after `stateLd.postValue(connected ? ConnectIqState.CONNECTED : ConnectIqState.DISCONNECTED);` at line 127):

```java
            maybeSendHello();
```

In the `registerForDeviceEvents` callback, inside the `if (nowConnected) { ... }` block (after the re-register try/catch, around line 119, still inside the `if`):

```java
                    maybeSendHello();
```

- [ ] **Step 4: Add `forceRebind()`**

Add this public method just above the existing `disconnect()` method (around line 239):

```java
    /**
     * Force the SDK to fully shut down and re-initialise, then reconnect. Use this at
     * app startup so a phone-only app update can't leave Garmin Connect Mobile routing
     * the watch's LIST_ROUTES to the dead previous process (the "watch route list is
     * empty after updating only the phone" bug). The shutdown clears GCM's stale
     * message binding; the delayed reconnect lets the teardown settle before re-init.
     */
    public void forceRebind() {
        Log.i(TAG, "forceRebind: shutting down SDK to clear any stale GCM binding");
        try {
            connectIQ.shutdown(context);
        } catch (Exception e) {
            // Expected on a cold start where the SDK was never initialised.
            Log.w(TAG, "forceRebind: shutdown threw (likely not yet initialised): " + e);
        }
        connected = false;
        device = null;
        helloSent = false;
        stateLd.postValue(ConnectIqState.DISCONNECTED);
        new Handler(Looper.getMainLooper()).postDelayed(this::connect, 1_000);
    }
```

(`Handler` and `Looper` are already imported — they are used by `onSdkShutDown`.)

- [ ] **Step 5: Verify the module still compiles and existing tests pass**

Run: `cd android && ./gradlew :app:testDebugUnitTest --tests "nl.paree.climbpro.connectiq.*"`
Expected: PASS — `ConnectIqClientHelloTest`, `ConnectIqAppIdTest`, `PayloadCodecTest`, `WatchRequestHandlerTest` all green. (No new unit test here; this step only confirms the glue compiles and breaks nothing.)

- [ ] **Step 6: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/connectiq/ConnectIqClient.java
git commit -m "feat(ciq): forceRebind() on startup + one-time HELLO to widget"
```

---

### Task 3: Call forceRebind() at app startup

Switch the app's startup from the plain idempotent `connect()` to `forceRebind()`, so every cold start (including the one right after a phone-only update) rebinds GCM to the fresh process.

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/ClimbProApplication.java:29`

- [ ] **Step 1: Replace the startup call**

In `ClimbProApplication.onCreate()`, change line 29 from:

```java
        ciqClient.connect();
```

to:

```java
        // Force a clean GCM rebind on startup so a phone-only app update can't leave
        // the watch talking to a dead process. See ConnectIqClient#forceRebind.
        ciqClient.forceRebind();
```

- [ ] **Step 2: Build the debug APK to confirm it compiles**

Run: `cd android && ./gradlew :app:assembleDebug`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: On-device verification (the real test for Tasks 2–3)**

Prerequisites: FR255 paired in Garmin Connect, the **widget** (`ClimbWidgetApp`) already sideloaded on the watch, ≥1 route on the phone.

```bash
cd android
./gradlew :app:installDebug
adb logcat -s ConnectIqClient WatchRequestHandler
```

Expected log sequence on app launch:
1. `forceRebind: shutting down SDK to clear any stale GCM binding`
2. `Using device: <name>` and state reaches CONNECTED
3. `Connected — sending HELLO to widget to prime GCM binding`

Then **simulate the bug**: with the watch widget closed, rebuild and `:app:installDebug` again (phone-only update). Open the widget on the watch and confirm `Sent ROUTE_LIST with N routes` appears and the "Telefoon" list fills — *without* re-sideloading the watch app. (Before this change, that step is exactly what silently failed.)

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/ClimbProApplication.java
git commit -m "fix(ciq): force GCM rebind on startup to survive phone-only updates"
```

---

### Task 4: Widget handles HELLO → re-request route list

When the phone reconnects (e.g. right after an update) and sends `HELLO`, the open widget should re-issue `LIST_ROUTES` so its route list refreshes without the user reopening the sync screen. **No Monkey C test harness exists — verify by review + on-device.**

**Files:**
- Modify: `garmin-widget/source/CommListener.mc`

- [ ] **Step 1: Add HELLO to the type dispatch**

In `PhoneMessageCallback.onMessage`, extend the `if (msgType instanceof Toybox.Lang.String)` block. Change the existing dispatch (lines 34–41) from:

```monkeyc
            if (msgType.equals("ROUTE_LIST")) {
                handleRouteList(msg);
            } else if (msgType.equals("ACTIVE_SET")) {
                App.getApp().activeAck = msg;
            } else {
                Sys.println("CommListener: unknown type: " + msgType);
            }
            return;
```

to:

```monkeyc
            if (msgType.equals("ROUTE_LIST")) {
                handleRouteList(msg);
            } else if (msgType.equals("ACTIVE_SET")) {
                App.getApp().activeAck = msg;
            } else if (msgType.equals("HELLO")) {
                handleHello();
            } else {
                Sys.println("CommListener: unknown type: " + msgType);
            }
            return;
```

- [ ] **Step 2: Add the `handleHello` helper**

Add this `hidden function` next to the other helpers (e.g. just above `handleRouteList`, around line 77):

```monkeyc
    // Phone (re)connected — possibly a fresh install. Re-request the route list so the
    // widget's "Telefoon" section refreshes without the user reopening the sync screen.
    hidden function handleHello() {
        Sys.println("CommListener: HELLO from phone, re-requesting route list");
        App.getApp().phoneRouteIndex.received = false;
        Comm.transmit({ "type" => "LIST_ROUTES" }, null, new CommListener());
    }
```

(`Comm`, `App`, and `Sys` are all already imported at the top of the file — lines 1–3.)

- [ ] **Step 3: Review-verify the change**

Re-read the diff and confirm:
- The `HELLO` branch sits *before* the `else` (so it isn't logged as "unknown type").
- `phoneRouteIndex.received = false` matches the reset `SyncView.onShow` does before transmitting (`SyncView.mc:15`), so a fresh response flips it back to `true`.
- No new imports are required.

- [ ] **Step 4: On-device verification (if `monkeyc` + simulator available)**

If the Connect IQ SDK is installed, rebuild and sideload the widget:

```bash
cd garmin-widget
monkeyc -o ClimbWidget.prg -f monkey.jungle -y <developer_key>
```

Open the widget, then trigger a phone reconnect (relaunch the Android app). Expected on the watch console: `CommListener: HELLO from phone, re-requesting route list`, followed by the route list repopulating. If `monkeyc` is unavailable, record that verification was review-only in the commit body.

- [ ] **Step 5: Commit**

```bash
git add garmin-widget/source/CommListener.mc
git commit -m "feat(widget): re-request route list on HELLO from phone"
```

---

### Task 5: Document the rebind + HELLO flow

Keep `Documentation/CONNECTION.md` truthful about how the connection now self-heals after a phone-only update (per the repo rule: wire/connection-behaviour changes update the docs in the same change).

**Files:**
- Modify: `Documentation/CONNECTION.md`

- [ ] **Step 1: Add a HELLO row to the message-types table**

In §4 "Berichttypen (protocol)", add a row after the `ROUTE_LIST` row:

```markdown
| telefoon → watch | `{ "type": "HELLO" }` | telefoon meldt zich na (her)verbinden; widget vraagt opnieuw `LIST_ROUTES` |
```

- [ ] **Step 2: Document forceRebind in the lifecycle section**

At the end of §3 "De verbindingslevenscyclus (Android-zijde)", add:

```markdown
> **Telefoon-only update:** `ClimbProApplication.onCreate()` roept bij opstart
> `forceRebind()` aan (niet het kale `connect()`). Dat doet eerst een
> `ConnectIQ.shutdown()` en daarna opnieuw `initialize()`, zodat een stale
> Connect-IQ-binding in Garmin Connect Mobile — die na een *alleen-telefoon*
> app-update naar het oude, dode proces blijft wijzen — wordt opgeruimd en het
> nieuwe proces schoon herbindt. Zodra de verbinding `CONNECTED` is, stuurt de
> telefoon éénmalig een `HELLO`; de widget vraagt daarop opnieuw `LIST_ROUTES`.
```

- [ ] **Step 3: Update the troubleshooting table**

In §7 "Probleemoplossing", change the empty-list guidance. Replace the row:

```markdown
| Widget toont "Geen verbinding" | UUID-mismatch: `ConnectIqAppId.VALUE` ≠ `garmin-widget/manifest.xml` id |
```

with:

```markdown
| Widget toont "Geen verbinding" | UUID-mismatch: `ConnectIqAppId.VALUE` ≠ `garmin-widget/manifest.xml` id |
| "Telefoon"-lijst leeg ná alleen-telefoon update | Stale GCM-binding. Sinds `forceRebind()` bij opstart zou dit vanzelf moeten herstellen; lukt het niet, herstart Garmin Connect Mobile + ClimbPro. Géén UUID-probleem. |
```

- [ ] **Step 4: Commit**

```bash
git add Documentation/CONNECTION.md
git commit -m "docs: document forceRebind + HELLO self-heal for phone-only updates"
```

---

## Self-Review

- **Spec coverage:** The user's goal ("connection always works after a phone-only update", "with a hardcoded UUID") is met — the UUID is confirmed already hardcoded/matching (no change, with rationale), and the actual stale-binding cause is fixed by `forceRebind()` (Tasks 2–3) plus the `HELLO`→re-request loop (Tasks 1, 4). Docs updated (Task 5).
- **Type/name consistency:** `helloMessage()` (Task 1) is the single source used by `maybeSendHello()` (Task 2). `MSG_TYPE_HELLO` = `"HELLO"` matches the Monkey C `msgType.equals("HELLO")` (Task 4) and the docs row (Task 5). `forceRebind()` is defined in Task 2 and called in Task 3. `phoneRouteIndex.received` reset matches `SyncView.mc:15`.
- **No placeholders:** every code step shows the exact code and exact gradle/adb command with expected output.
- **Honesty about verification:** unit test only where logic is pure (Task 1); SDK glue and Monkey C verified on-device/review, stated explicitly — matching this repo's lack of a `ConnectIqClient` unit test and Monkey C harness.
- **No schema churn:** `HELLO` is a control message; `protocol/schema.json` is intentionally untouched (confirmed it contains no message-type strings).

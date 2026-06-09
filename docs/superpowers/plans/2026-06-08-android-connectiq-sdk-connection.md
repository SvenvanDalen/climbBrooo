# Android ⇄ Garmin Connection (Connect IQ Mobile SDK Integration) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the stub `ConnectIqClient` with a real Garmin Connect IQ Mobile SDK integration so the Android app actually discovers, connects to, sends to, and receives from the ClimbPro **widget** on the Forerunner 255 Music.

**Architecture:** The Garmin CIQ Mobile SDK AAR (already on disk at `C:\Users\svenv\Downloads\connectiq-sdk.aar`) is linked into the Android module. `ConnectIqClient` wraps the `ConnectIQ` singleton: async `initialize` → device discovery → register for device + app events. It becomes app-scoped (held by `ClimbProApplication`) so `RouteSyncWorker` reuses one connected instance instead of constructing throwaway clients. The watch expects a **Dictionary** (not raw bytes), so the canonical send unit is `Map<String,Object>`; `sendPayload(byte[])` decodes JSON → `Map` first. The phone's app UUID is set to the **widget's** UUID so the two endpoints match.

**Tech Stack:** Java 8, Garmin Connect IQ Mobile SDK (`com.garmin.android.connectiq`), Jackson, JUnit 4 + Mockito 5.

---

## Root-cause summary (why it doesn't connect today)

Investigated via systematic-debugging. Three independent blockers, all on the Android side:

1. **`ConnectIqClient` is a stub.** Every method is a no-op; `connect()` just logs and sets state to `ERROR`. The real `com.garmin.android.connectiq.ConnectIQ` is never used. The AAR was never linked (`// implementation files('libs/ciq-mobile-sdk.aar')` is commented out; `android/app/libs/` does not exist).
2. **App UUID mismatch.** Phone `APP_ID = "a3421399-b234-4d5e-b9c0-7b5a63e84d45"` (dashed) matches *neither* watch app. The sync counterpart per `docs/superpowers/specs/2026-06-08-garmin-widget-sync-and-storage-design.md` is the **widget** `ClimbWidgetApp`, whose manifest id is `fedcba9876543210fedcba9876543210`.
3. **Wire-format mismatch.** The watch (`PhoneMessageCallback.onMessage`) rejects anything that is not a `Toybox.Lang.Dictionary`. The Android side builds `byte[]` JSON and `sendMessage(Map)` re-serializes to bytes — sending bytes makes the watch receive a `ByteArray` and drop the message.

Plus two correctness hazards this plan must handle:
- **Android 11+ package visibility:** with `targetSdk 34`, binding to the Garmin Connect Mobile service silently fails unless a `<queries>` entry for `com.garmin.android.apps.connectmobile` is declared.
- **Async + WorkManager:** the SDK is callback-based and `RouteSyncWorker` constructs a fresh `ConnectIqClient` then calls `sendPayload` immediately — there is no connected device yet. The worker must reuse the app-scoped client and wait for connection.

## File Structure

| File | Responsibility | Action |
|---|---|---|
| `android/app/libs/ciq-mobile-sdk.aar` | The Garmin Mobile SDK binary | Create (copy) |
| `android/app/build.gradle` | Link the AAR | Modify (line ~174) |
| `android/app/src/main/AndroidManifest.xml` | Declare GCM package visibility | Modify |
| `.../connectiq/ConnectIqAppId.java` | Single source of truth for the app UUID | Create |
| `.../connectiq/PayloadCodec.java` | JSON bytes → `Map<String,Object>` (Dictionary-safe) | Create |
| `.../connectiq/ConnectIqClient.java` | Real SDK wrapper (init, discovery, send, receive, state) | Rewrite |
| `.../ClimbProApplication.java` | Owns the app-scoped client; calls `connect()` | Modify |
| `.../service/RouteSyncWorker.java` | Reuse app-scoped client; blocking send | Modify |
| `.../connectiq/ConnectIqAppIdTest.java` | Guards UUID format + widget contract | Create |
| `.../connectiq/PayloadCodecTest.java` | Guards JSON→Map integer typing | Create |

**Why these seams:** `ConnectIqClient`'s constructor calls `ConnectIQ.getInstance(...)`, which requires Android + a bound Garmin service — it cannot run in JVM unit tests. So all *unit-testable* logic (UUID contract, payload decoding) lives in `ConnectIqAppId` and `PayloadCodec`, which have **no** Android/SDK imports. `ConnectIqClient` itself is verified by compilation + the on-device checklist in Task 8.

---

### Task 1: Link the Garmin Mobile SDK AAR

**Files:**
- Create: `android/app/libs/ciq-mobile-sdk.aar` (copy of `C:\Users\svenv\Downloads\connectiq-sdk.aar`)
- Modify: `android/app/build.gradle:174`

- [ ] **Step 1: Copy the AAR into the module**

PowerShell:
```powershell
New-Item -ItemType Directory -Force android\app\libs
Copy-Item "C:\Users\svenv\Downloads\connectiq-sdk.aar" "android\app\libs\ciq-mobile-sdk.aar"
```

- [ ] **Step 2: Verify it is the real Mobile SDK (contains `ConnectIQ.class`)**

Bash:
```bash
unzip -l android/app/libs/ciq-mobile-sdk.aar | grep classes.jar
cd /tmp && unzip -o -q "$OLDPWD/android/app/libs/ciq-mobile-sdk.aar" classes.jar -d ciqv && unzip -l ciqv/classes.jar | grep "connectiq/ConnectIQ.class"
```
Expected: a line `com/garmin/android/connectiq/ConnectIQ.class`.

- [ ] **Step 3: Enable the dependency**

In `android/app/build.gradle`, replace the commented line:
```groovy
    // Garmin Connect IQ Mobile SDK is added manually as a .aar — see SETUP.md.
    // implementation files('libs/ciq-mobile-sdk.aar')
```
with:
```groovy
    // Garmin Connect IQ Mobile SDK (binary AAR committed under app/libs/).
    implementation files('libs/ciq-mobile-sdk.aar')
```

- [ ] **Step 4: Verify the SDK resolves on the compile classpath**

Run (from `android/`): `./gradlew :app:dependencies --configuration debugCompileClasspath`
Expected: output lists `ciq-mobile-sdk.aar` (or `files(...)` entry) with no resolution error.

- [ ] **Step 5: Commit**

```bash
git add android/app/libs/ciq-mobile-sdk.aar android/app/build.gradle
git commit -m "build(android): link Garmin Connect IQ Mobile SDK aar"
```

---

### Task 2: Single source of truth for the app UUID

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/connectiq/ConnectIqAppId.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/connectiq/ConnectIqAppIdTest.java`

The phone must use the **widget** UUID `fedcba9876543210fedcba9876543210`. Using the widget's *existing* id means no watch rebuild is needed. (For a production release you'd regenerate both sides to a fresh UUID; out of scope here — note added in code comment.)

- [ ] **Step 1: Write the failing test**

```java
package nl.paree.climbpro.connectiq;

import org.junit.Test;
import static org.junit.Assert.*;

public class ConnectIqAppIdTest {

    /** Must match garmin-widget/manifest.xml <iq:application id="..."> exactly. */
    @Test
    public void value_matchesWidgetManifestId() {
        assertEquals("fedcba9876543210fedcba9876543210", ConnectIqAppId.VALUE);
    }

    /** Connect IQ app ids are 32 lowercase hex chars, no dashes. */
    @Test
    public void value_isThirtyTwoLowerHexNoDashes() {
        assertTrue("got: " + ConnectIqAppId.VALUE,
                ConnectIqAppId.VALUE.matches("[0-9a-f]{32}"));
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run (from `android/`): `./gradlew :app:testDebugUnitTest --tests nl.paree.climbpro.connectiq.ConnectIqAppIdTest`
Expected: FAIL — `ConnectIqAppId` does not exist (compile error).

- [ ] **Step 3: Create the class**

```java
package nl.paree.climbpro.connectiq;

/**
 * The Connect IQ application UUID the phone communicates with.
 *
 * MUST equal the {@code id} attribute of the SYNC counterpart watch app.
 * Per docs/superpowers/specs/2026-06-08-garmin-widget-sync-and-storage-design.md
 * the counterpart is the WIDGET (ClimbWidgetApp), not the datafield.
 *
 * Contract: this value is duplicated in garmin-widget/manifest.xml — change
 * both together. (For a public release, regenerate both to a fresh UUID.)
 */
public final class ConnectIqAppId {
    private ConnectIqAppId() {}

    public static final String VALUE = "fedcba9876543210fedcba9876543210";
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests nl.paree.climbpro.connectiq.ConnectIqAppIdTest`
Expected: PASS (2 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/connectiq/ConnectIqAppId.java \
        android/app/src/test/java/nl/paree/climbpro/connectiq/ConnectIqAppIdTest.java
git commit -m "feat(android): add ConnectIqAppId pinned to widget UUID"
```

---

### Task 3: `PayloadCodec` — decode JSON bytes to a Dictionary-safe Map

**Files:**
- Create: `android/app/src/main/java/nl/paree/climbpro/connectiq/PayloadCodec.java`
- Test: `android/app/src/test/java/nl/paree/climbpro/connectiq/PayloadCodecTest.java`

The watch rejects non-Dictionary payloads. The SDK serializes a Java `Map` → Monkey C `Dictionary` and a Java `Integer` → `Toybox.Lang.Number`. `ClimbPayloadBuilder` emits `byte[]` JSON, so we decode it back to a `Map` whose numeric leaves are `Integer`/`Long` (never `Double` for whole numbers) before handing it to the SDK.

- [ ] **Step 1: Write the failing test**

```java
package nl.paree.climbpro.connectiq;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class PayloadCodecTest {

    @Test
    public void decode_topLevelKeysPreserved() throws Exception {
        byte[] json = "{\"v\":3,\"mode\":\"route\",\"routeId\":\"abc\"}"
                .getBytes(StandardCharsets.UTF_8);
        Map<String, Object> m = PayloadCodec.decode(json);
        assertEquals("route", m.get("mode"));
        assertEquals("abc", m.get("routeId"));
    }

    /** Whole numbers must decode to Integer/Long, not Double — the watch
     *  checks `instanceof Toybox.Lang.Number` and reads them as ints. */
    @Test
    public void decode_wholeNumbersAreIntegral() throws Exception {
        byte[] json = "{\"v\":3,\"sd\":800,\"slat\":5212345}"
                .getBytes(StandardCharsets.UTF_8);
        Map<String, Object> m = PayloadCodec.decode(json);
        assertTrue(m.get("v") instanceof Integer || m.get("v") instanceof Long);
        assertTrue(m.get("sd") instanceof Integer || m.get("sd") instanceof Long);
        assertTrue(m.get("slat") instanceof Integer || m.get("slat") instanceof Long);
    }

    @Test
    public void decode_nestedClimbsArrayPreserved() throws Exception {
        byte[] json = "{\"climbs\":[{\"sd\":0,\"segs\":[100,5,30,1]}]}"
                .getBytes(StandardCharsets.UTF_8);
        Map<String, Object> m = PayloadCodec.decode(json);
        List<?> climbs = (List<?>) m.get("climbs");
        assertEquals(1, climbs.size());
        Map<?, ?> climb = (Map<?, ?>) climbs.get(0);
        List<?> segs = (List<?>) climb.get("segs");
        assertEquals(4, segs.size());
        assertTrue(segs.get(0) instanceof Integer || segs.get(0) instanceof Long);
    }
}
```

- [ ] **Step 2: Run it to verify it fails**

Run: `./gradlew :app:testDebugUnitTest --tests nl.paree.climbpro.connectiq.PayloadCodecTest`
Expected: FAIL — `PayloadCodec` does not exist.

- [ ] **Step 3: Create the class**

```java
package nl.paree.climbpro.connectiq;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;

/**
 * Decodes the JSON payload produced by {@link nl.paree.climbpro.service.ClimbPayloadBuilder}
 * into a {@code Map<String,Object>} for the Connect IQ SDK to serialize as a Dictionary.
 *
 * Jackson maps JSON integers to Integer/Long (not Double), which the watch reads as
 * Toybox.Lang.Number. The watch rejects any message that is not a Dictionary, so we
 * MUST send a Map — never the raw byte[].
 */
public final class PayloadCodec {
    private PayloadCodec() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE =
            new TypeReference<Map<String, Object>>() {};

    public static Map<String, Object> decode(byte[] json) throws IOException {
        return MAPPER.readValue(json, MAP_TYPE);
    }
}
```

- [ ] **Step 4: Run it to verify it passes**

Run: `./gradlew :app:testDebugUnitTest --tests nl.paree.climbpro.connectiq.PayloadCodecTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/connectiq/PayloadCodec.java \
        android/app/src/test/java/nl/paree/climbpro/connectiq/PayloadCodecTest.java
git commit -m "feat(android): add PayloadCodec for Dictionary-safe JSON decode"
```

---

### Task 4: Declare Garmin Connect Mobile package visibility (Android 11+)

**Files:**
- Modify: `android/app/src/main/AndroidManifest.xml`

With `targetSdk 34`, the SDK's bind to `com.garmin.android.apps.connectmobile` returns `GCM_NOT_INSTALLED`/service error unless the package is declared visible.

- [ ] **Step 1: Add the `<queries>` element**

In `AndroidManifest.xml`, immediately after the last `<uses-permission .../>` line and before `<application`, insert:
```xml
    <!-- Required on Android 11+ (targetSdk 30+) so the CIQ SDK can bind to
         the Garmin Connect Mobile service. Without this, initialize() reports
         GCM_NOT_INSTALLED even when Garmin Connect is installed. -->
    <queries>
        <package android:name="com.garmin.android.apps.connectmobile" />
    </queries>
```

- [ ] **Step 2: Verify the manifest still merges**

Run (from `android/`): `./gradlew :app:processDebugMainManifest`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/AndroidManifest.xml
git commit -m "fix(android): declare Garmin Connect package visibility for CIQ bind"
```

---

### Task 5: Rewrite `ConnectIqClient` against the real SDK

**Files:**
- Rewrite: `android/app/src/main/java/nl/paree/climbpro/connectiq/ConnectIqClient.java`

Verified real SDK signatures (from `javap` on the AAR):
- `ConnectIQ.getInstance(Context, IQConnectType)` → `IQConnectType.WIRELESS` for a real BT-paired watch.
- `void initialize(Context, boolean autoUI, ConnectIQListener)` → callbacks `onSdkReady()`, `onInitializeError(IQSdkErrorStatus)`, `onSdkShutDown()`.
- `List<IQDevice> getConnectedDevices()` throws `InvalidStateException, ServiceUnavailableException`.
- `void registerForDeviceEvents(IQDevice, IQDeviceEventListener)` → `onDeviceStatusChanged(IQDevice, IQDevice.IQDeviceStatus)`.
- `void registerForAppEvents(IQDevice, IQApp, IQApplicationEventListener)` → `onMessageReceived(IQDevice, IQApp, List<Object>, IQMessageStatus)`.
- `void sendMessage(IQDevice, IQApp, Object, IQSendMessageListener)` → `onMessageStatus(IQDevice, IQApp, IQMessageStatus)`.
- `void shutdown(Context)`.
- `IQDevice.IQDeviceStatus` ∈ {NOT_PAIRED, NOT_CONNECTED, CONNECTED, UNKNOWN}; `IQMessageStatus.SUCCESS`.

Design notes baked into the code below:
- `connect()` is async and idempotent; state flows DISCONNECTED → CONNECTING → CONNECTED / ERROR.
- The canonical send is `sendMessage(Map)`. `sendPayload(byte[])` decodes via `PayloadCodec` then delegates — fixing the Dictionary mismatch.
- A `sendPayloadBlocking(byte[], timeoutMs)` is added for `RouteSyncWorker` (callback → `CountDownLatch`).
- Incoming app messages are routed to `WatchRequestHandler.handleMessage(Map)`.
- The class stays `final` (Mockito 5 inline mock-maker mocks it for `WatchRequestHandlerTest`).

- [ ] **Step 1: Replace the entire file**

```java
package nl.paree.climbpro.connectiq;

import android.content.Context;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.garmin.android.connectiq.ConnectIQ;
import com.garmin.android.connectiq.IQApp;
import com.garmin.android.connectiq.IQDevice;
import com.garmin.android.connectiq.exception.InvalidStateException;
import com.garmin.android.connectiq.exception.ServiceUnavailableException;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Wrapper around the Garmin Connect IQ Mobile SDK.
 *
 * Lifecycle: {@link #connect()} initialises the SDK (async). On {@code onSdkReady}
 * it discovers connected devices, picks the first one, and registers for device +
 * app events for the ClimbPro widget ({@link ConnectIqAppId#VALUE}). State is
 * exposed via {@link #state()}.
 *
 * The watch only accepts a Dictionary, so every send goes out as a Map; byte[]
 * payloads are decoded with {@link PayloadCodec} first.
 */
public final class ConnectIqClient {

    private static final String TAG = "ConnectIqClient";

    private final Context context;
    private final ConnectIQ connectIQ;
    private final IQApp iqApp = new IQApp(ConnectIqAppId.VALUE);

    private final MutableLiveData<ConnectIqState> stateLd =
            new MutableLiveData<>(ConnectIqState.DISCONNECTED);

    private volatile IQDevice device;
    private volatile boolean connected;
    private WatchRequestHandler requestHandler;

    public ConnectIqClient(Context context) {
        this.context = context.getApplicationContext();
        this.connectIQ = ConnectIQ.getInstance(this.context, ConnectIQ.IQConnectType.WIRELESS);
    }

    public LiveData<ConnectIqState> state() { return stateLd; }

    public boolean isConnected() { return connected && device != null; }

    public void setWatchRequestHandler(WatchRequestHandler requestHandler) {
        this.requestHandler = requestHandler;
    }

    /** Discover the paired Forerunner 255 Music and connect. Idempotent-ish. */
    public void connect() {
        stateLd.postValue(ConnectIqState.CONNECTING);
        connectIQ.initialize(context, /* autoUI= */ true, new ConnectIQ.ConnectIQListener() {
            @Override public void onSdkReady() { handleSdkReady(); }

            @Override public void onInitializeError(ConnectIQ.IQSdkErrorStatus status) {
                Log.e(TAG, "CIQ initialize error: " + status);
                connected = false;
                stateLd.postValue(ConnectIqState.ERROR);
            }

            @Override public void onSdkShutDown() {
                connected = false;
                device = null;
                stateLd.postValue(ConnectIqState.DISCONNECTED);
            }
        });
    }

    private void handleSdkReady() {
        try {
            List<IQDevice> devices = connectIQ.getConnectedDevices();
            if (devices == null || devices.isEmpty()) {
                Log.w(TAG, "No connected Garmin devices found");
                connected = false;
                stateLd.postValue(ConnectIqState.ERROR);
                return;
            }
            device = devices.get(0);
            Log.i(TAG, "Using device: " + device.getFriendlyName());

            connectIQ.registerForDeviceEvents(device, (dev, status) -> {
                boolean nowConnected = status == IQDevice.IQDeviceStatus.CONNECTED;
                connected = nowConnected;
                stateLd.postValue(nowConnected
                        ? ConnectIqState.CONNECTED : ConnectIqState.DISCONNECTED);
            });

            connectIQ.registerForAppEvents(device, iqApp,
                    (dev, app, data, status) -> dispatchIncoming(data));

            connected = device.getStatus() == IQDevice.IQDeviceStatus.CONNECTED;
            stateLd.postValue(connected
                    ? ConnectIqState.CONNECTED : ConnectIqState.DISCONNECTED);

        } catch (InvalidStateException | ServiceUnavailableException e) {
            Log.e(TAG, "handleSdkReady failed", e);
            connected = false;
            stateLd.postValue(ConnectIqState.ERROR);
        }
    }

    @SuppressWarnings("unchecked")
    private void dispatchIncoming(List<Object> data) {
        if (requestHandler == null || data == null || data.isEmpty()) return;
        Object first = data.get(0);
        if (first instanceof Map) {
            requestHandler.handleMessage((Map<String, Object>) first);
        } else {
            Log.w(TAG, "Incoming message is not a Map: "
                    + (first == null ? "null" : first.getClass()));
        }
    }

    /** Fire-and-forget send of a Map (serialised to a Dictionary by the SDK). */
    public boolean sendMessage(Map<String, Object> message) {
        if (!isConnected()) {
            Log.w(TAG, "sendMessage: not connected");
            return false;
        }
        try {
            stateLd.postValue(ConnectIqState.SENDING);
            connectIQ.sendMessage(device, iqApp, message, (dev, app, status) -> {
                if (status != ConnectIQ.IQMessageStatus.SUCCESS) {
                    Log.e(TAG, "sendMessage status: " + status);
                }
                stateLd.postValue(ConnectIqState.CONNECTED);
            });
            return true;
        } catch (InvalidStateException | ServiceUnavailableException e) {
            Log.e(TAG, "sendMessage failed", e);
            stateLd.postValue(ConnectIqState.ERROR);
            return false;
        }
    }

    /** Decode JSON payload to a Map and send it (Dictionary on the watch). */
    public boolean sendPayload(byte[] payload) {
        try {
            return sendMessage(PayloadCodec.decode(payload));
        } catch (IOException e) {
            Log.e(TAG, "sendPayload: cannot parse payload JSON", e);
            return false;
        }
    }

    /**
     * Blocking send for use on WorkManager background threads. Returns true only
     * when the watch acknowledges SUCCESS within {@code timeoutMs}.
     */
    public boolean sendPayloadBlocking(byte[] payload, long timeoutMs) {
        if (!isConnected()) {
            Log.w(TAG, "sendPayloadBlocking: not connected");
            return false;
        }
        final Map<String, Object> message;
        try {
            message = PayloadCodec.decode(payload);
        } catch (IOException e) {
            Log.e(TAG, "sendPayloadBlocking: cannot parse payload JSON", e);
            return false;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<ConnectIQ.IQMessageStatus> result = new AtomicReference<>();
        try {
            connectIQ.sendMessage(device, iqApp, message, (dev, app, status) -> {
                result.set(status);
                latch.countDown();
            });
        } catch (InvalidStateException | ServiceUnavailableException e) {
            Log.e(TAG, "sendPayloadBlocking failed", e);
            return false;
        }
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "sendPayloadBlocking: timed out");
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return result.get() == ConnectIQ.IQMessageStatus.SUCCESS;
    }

    public void disconnect() {
        try {
            connectIQ.shutdown(context);
        } catch (Exception e) {
            Log.w(TAG, "shutdown failed", e);
        }
        connected = false;
        device = null;
        stateLd.postValue(ConnectIqState.DISCONNECTED);
    }
}
```

- [ ] **Step 2: Verify it compiles against the SDK**

Run (from `android/`): `./gradlew :app:compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL (the `com.garmin.android.connectiq.*` imports now resolve from the AAR).

- [ ] **Step 3: Verify existing `WatchRequestHandlerTest` still passes**

Run: `./gradlew :app:testDebugUnitTest --tests nl.paree.climbpro.connectiq.WatchRequestHandlerTest`
Expected: PASS — `sendMessage(Map)` / `sendPayload(byte[])` signatures are unchanged, so the Mockito mock still works.

- [ ] **Step 4: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/connectiq/ConnectIqClient.java
git commit -m "feat(android): implement ConnectIqClient against Garmin Mobile SDK"
```

---

### Task 6: Make the client app-scoped and connect on startup

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/ClimbProApplication.java`

The SDK is a singleton and connection is async — there must be exactly one client for the app's lifetime, created and connected at startup, and exposed for the worker.

- [ ] **Step 1: Replace the file**

```java
package nl.paree.climbpro;

import android.app.Application;

import org.osmdroid.config.Configuration;

import java.io.File;

import nl.paree.climbpro.connectiq.ConnectIqClient;
import nl.paree.climbpro.connectiq.WatchRequestHandler;
import nl.paree.climbpro.data.route.RouteRepository;

public final class ClimbProApplication extends Application {

    private ConnectIqClient ciqClient;

    @Override
    public void onCreate() {
        super.onCreate();
        Configuration.getInstance().setUserAgentValue("ClimbPro/1.0");
        Configuration.getInstance().setOsmdroidTileCache(
                new File(getCacheDir(), "osmdroid"));

        RouteRepository routeRepo = new RouteRepository(this);
        ciqClient = new ConnectIqClient(this);
        ciqClient.setWatchRequestHandler(new WatchRequestHandler(routeRepo, ciqClient));
        ciqClient.connect();
    }

    /** App-scoped Connect IQ client. Reused by RouteSyncWorker — never construct your own. */
    public ConnectIqClient connectIqClient() {
        return ciqClient;
    }
}
```

- [ ] **Step 2: Compile**

Run (from `android/`): `./gradlew :app:compileDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/ClimbProApplication.java
git commit -m "feat(android): own app-scoped ConnectIqClient, connect on startup"
```

---

### Task 7: Reuse the app-scoped client in `RouteSyncWorker`

**Files:**
- Modify: `android/app/src/main/java/nl/paree/climbpro/service/RouteSyncWorker.java`

Stop constructing a throwaway `ConnectIqClient` (which is never connected). Reuse the app-scoped one, wait briefly for connection, and use the blocking send so `Result.retry()` reflects real delivery.

- [ ] **Step 1: Replace the `ConnectIqClient` acquisition**

Change line 59 from:
```java
        ConnectIqClient      ciqClient    = new ConnectIqClient(ctx);
```
to:
```java
        ConnectIqClient      ciqClient    =
                ((nl.paree.climbpro.ClimbProApplication) ctx).connectIqClient();
```

- [ ] **Step 2: Wait for connection before sending**

Immediately after the `ciqClient` line above, add:
```java
        // The CIQ connection is async; give it a moment if the app just started.
        for (int i = 0; i < 20 && !ciqClient.isConnected(); i++) {
            try { Thread.sleep(250); } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Result.retry();
            }
        }
        if (!ciqClient.isConnected()) {
            Log.w(TAG, "Watch not connected — will retry");
            return Result.retry();
        }
```

- [ ] **Step 3: Use the blocking send**

Change the send block (lines 116-120) from:
```java
            boolean sent = ciqClient.sendPayload(payload);
            if (!sent) {
                Log.w(TAG, "Send failed — will retry");
                return Result.retry();
            }
```
to:
```java
            boolean sent = ciqClient.sendPayloadBlocking(payload, 10_000);
            if (!sent) {
                Log.w(TAG, "Send failed or not acknowledged — will retry");
                return Result.retry();
            }
```

- [ ] **Step 4: Compile and run the worker test suite (regression)**

Run (from `android/`): `./gradlew :app:testDebugUnitTest`
Expected: BUILD SUCCESSFUL; all existing tests (incl. `WatchRequestHandlerTest`, `ConnectIqAppIdTest`, `PayloadCodecTest`) PASS.

> Note: `getApplicationContext()` inside a `Worker` returns the `Application` instance, so the cast to `ClimbProApplication` is safe. If any unit test instantiates `RouteSyncWorker` with a non-application context, guard with `instanceof`; none does today.

- [ ] **Step 5: Commit**

```bash
git add android/app/src/main/java/nl/paree/climbpro/service/RouteSyncWorker.java
git commit -m "feat(android): RouteSyncWorker reuses connected client, blocking send"
```

---

### Task 8: On-device verification (manual — no emulator substitute)

The connection path touches a real paired watch, Bluetooth, and the Garmin Connect app; it cannot be unit-tested. Verify on hardware.

**Preconditions:**
- Forerunner 255 Music paired in the Garmin Connect mobile app on the test phone.
- The ClimbPro **widget** (`ClimbWidgetApp`, id `fedcba9876543210fedcba9876543210`) sideloaded on the watch.
- At least one route present on the phone (Strava sync or GPX import).

- [ ] **Step 1: Build and install the debug app**

Run (from `android/`): `./gradlew :app:installDebug`
Expected: `Installed on 1 device.`

- [ ] **Step 2: Watch the connection logs**

Run: `adb logcat -s ConnectIqClient:* WatchRequestHandler:* RouteSyncWorker:*`
Open the app. Expected sequence:
- `Using device: <watch name>`
- state reaches `CONNECTED` (no `CIQ initialize error: GCM_NOT_INSTALLED`).

If you see `GCM_NOT_INSTALLED`, Task 4's `<queries>` entry is missing or Garmin Connect isn't installed.

- [ ] **Step 3: Verify watch → phone request**

Open the ClimbPro widget on the watch (it sends `LIST_ROUTES` from `SyncView`).
Expected logcat: `Sent ROUTE_LIST with N routes`, and the widget's "Telefoon" section populates.

- [ ] **Step 4: Verify phone → watch payload**

On the watch, select a phone route (sends `LOAD_ROUTE`).
Expected logcat: `Sent route payload for <id> (<bytes> bytes)`, and the watch renders the climb (proves the Dictionary decode in `PayloadCodec` worked — a bytes payload would have been silently dropped).

- [ ] **Step 5: Verify background sync delivers**

Trigger a manual sync (Settings sync button) with a watch connected.
Expected: `RouteSyncWorker` logs a successful send (no `not acknowledged — will retry`).

- [ ] **Step 6: Record the result**

Note pass/fail per step in the PR description. Do not claim "connection fixed" until Steps 2–4 pass on hardware (per superpowers:verification-before-completion).

---

## Self-Review

**Spec coverage** (`2026-06-08-garmin-widget-sync-and-storage-design.md`):
- Phone↔widget transport — Tasks 1, 5 (real SDK), Task 2 (matching UUID). ✓
- `LIST_ROUTES`/`LOAD_ROUTE` incoming → `WatchRequestHandler` — Task 5 `dispatchIncoming` wires `registerForAppEvents` to the existing handler. ✓
- `ROUTE_LIST` + v3 payload outgoing as a Dictionary — Tasks 3 + 5. ✓
- "Bestaand `sendMessage()`" reused, "wat niet verandert: v3 serialisatieformaat" — signatures of `sendMessage`/`sendPayload` unchanged; `ClimbPayloadBuilder` untouched. ✓
- WorkManager periodic sync preserved — Task 7 keeps the worker, only swaps client acquisition + send. ✓

**Placeholder scan:** No TBD/"add error handling"/"similar to Task N". Every code step is complete; every command has expected output.

**Type consistency:** `ConnectIqAppId.VALUE` (String) used in `new IQApp(...)`; `ConnectIqState` enum values (DISCONNECTED/CONNECTING/CONNECTED/SENDING/ERROR) all exist in the current enum; `WatchRequestHandler.handleMessage(Map<String,Object>)` matches `dispatchIncoming`'s cast; `sendMessage`/`sendPayload` keep their existing signatures so `WatchRequestHandlerTest` and `RouteSyncWorker` compile unchanged; SDK method/enum names verified against `javap` output of the AAR.

**Known limitation (called out, not hidden):** `connect()` picks `getConnectedDevices().get(0)`. If the user has multiple Garmin devices connected, this may not be the FR255M. A device-selection UI is out of scope for "fix the connection" and can be a follow-up.

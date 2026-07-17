package nl.paree.climbpro.connectiq;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.garmin.android.connectiq.ConnectIQ;
import com.garmin.android.connectiq.IQApp;
import com.garmin.android.connectiq.IQDevice;
import com.garmin.android.connectiq.exception.InvalidStateException;
import com.garmin.android.connectiq.exception.ServiceUnavailableException;

import java.io.IOException;
import java.util.LinkedHashMap;
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

    /** Delay before a self-healing reconnect after a recoverable connect failure. */
    private static final long RECONNECT_DELAY_MS = 5_000;

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

    private final Context context;
    private final ConnectIQ connectIQ;
    private final IQApp iqApp        = new IQApp(ConnectIqAppId.VALUE);
    private final IQApp datafieldApp = new IQApp(ConnectIqAppId.DATAFIELD);
    private final IQApp surfaceApp   = new IQApp(ConnectIqAppId.SURFACE_FIELD);
    private final IQApp onboardApp   = new IQApp(ConnectIqAppId.ONBOARD);

    private final MutableLiveData<ConnectIqState> stateLd =
            new MutableLiveData<>(ConnectIqState.DISCONNECTED);

    private volatile IQDevice device;
    private volatile boolean connected;
    // The check-then-set in maybeSendHello() is not atomic across SDK callback
    // threads, so a startup race can send HELLO twice. That's accepted by design:
    // HELLO is an idempotent prime/refresh trigger, a duplicate is harmless.
    private volatile boolean helloSent;
    private volatile WatchRequestHandler requestHandler;

    public ConnectIqClient(Context context) {
        this.context = context.getApplicationContext();
        this.connectIQ = ConnectIQ.getInstance(this.context, ConnectIQ.IQConnectType.WIRELESS);
    }

    public LiveData<ConnectIqState> state() { return stateLd; }

    public boolean isConnected() { return connected && device != null; }

    public void setWatchRequestHandler(WatchRequestHandler requestHandler) {
        this.requestHandler = requestHandler;
    }

    /**
     * Discover the paired Forerunner 255 Music and connect. Safe to call more
     * than once: a second call while already connecting/connected is a no-op,
     * so the SDK is never initialized twice.
     */
    public void connect() {
        if (connected || stateLd.getValue() == ConnectIqState.CONNECTING) {
            return;
        }
        stateLd.postValue(ConnectIqState.CONNECTING);
        helloSent = false;
        connectIQ.initialize(context, /* autoUI= */ true, new ConnectIQ.ConnectIQListener() {
            @Override public void onSdkReady() { handleSdkReady(); }

            @Override public void onInitializeError(ConnectIQ.IQSdkErrorStatus status) {
                Log.e(TAG, "CIQ initialize error: " + status);
                connected = false;
                stateLd.postValue(ConnectIqState.ERROR);
                scheduleReconnect();
            }

            @Override public void onSdkShutDown() {
                Log.w(TAG, "CIQ SDK shut down — scheduling reconnect");
                connected = false;
                device = null;
                stateLd.postValue(ConnectIqState.DISCONNECTED);
                new Handler(Looper.getMainLooper()).postDelayed(
                        ConnectIqClient.this::connect, RECONNECT_DELAY_MS);
            }
        });
    }

    private void handleSdkReady() {
        try {
            List<IQDevice> devices = connectIQ.getConnectedDevices();
            if (devices == null || devices.isEmpty()) {
                // Common right after forceRebind()'s shutdown(): GCM hasn't re-reported
                // the device yet. Without a retry the phone would stay ERROR forever and
                // never answer the watch's LIST_ROUTES (widget shows "No routes").
                Log.w(TAG, "No connected Garmin devices found — scheduling retry");
                connected = false;
                stateLd.postValue(ConnectIqState.ERROR);
                scheduleReconnect();
                return;
            }
            for (IQDevice d : devices) {
                Log.i(TAG, "Device: id=" + d.getDeviceIdentifier()
                        + " name=" + d.getFriendlyName() + " status=" + d.getStatus());
            }
            device = devices.get(0);
            Log.i(TAG, "Using device: " + device.getFriendlyName());

            connectIQ.registerForDeviceEvents(device, (dev, status) -> {
                Log.i(TAG, "Device event: id=" + dev.getDeviceIdentifier()
                        + " status=" + status);
                boolean nowConnected = status == IQDevice.IQDeviceStatus.CONNECTED;
                connected = nowConnected;
                stateLd.postValue(nowConnected
                        ? ConnectIqState.CONNECTED : ConnectIqState.DISCONNECTED);
                if (!nowConnected) {
                    // Arm HELLO for the next reconnect: every re-established
                    // connection must be fully re-primed (GCM message binding +
                    // widget route-list refresh), not just the first connection
                    // of this process.
                    helloSent = false;
                }
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
            });

            // Legacy per-device registration is the ONLY working inbound path.
            // Do NOT switch to registerAppToUseBinderService: GCM (verified on
            // 5.26.1) accepts that registration but never delivers anything
            // through it — no messages, no device events — and enabling it
            // makes the SDK unregister the broadcast receiver that this legacy
            // path needs. See docs/superpowers/plans/2026-07-17-always-connectable-watch-sync.md
            // for the failed experiment.
            connectIQ.registerForAppEvents(device, iqApp,
                    (dev, app, data, status) -> dispatchIncoming(data));

            // GCM 5.26.1 returns status=UNKNOWN in the getConnectedDevices()
            // parcel; the REAL status arrives via the device event above, which
            // often fires before this line. Never downgrade with the stale
            // snapshot — only upgrade — or the client stays "not connected"
            // forever while messages demonstrably flow.
            if (device.getStatus() == IQDevice.IQDeviceStatus.CONNECTED) {
                connected = true;
            }
            stateLd.postValue(connected
                    ? ConnectIqState.CONNECTED : ConnectIqState.DISCONNECTED);
            maybeSendHello();

        } catch (InvalidStateException | ServiceUnavailableException e) {
            Log.e(TAG, "handleSdkReady failed", e);
            connected = false;
            stateLd.postValue(ConnectIqState.ERROR);
            scheduleReconnect();
        }
    }

    /**
     * Schedule one delayed reconnect attempt after a recoverable failure (init error,
     * empty device list, or a discovery exception). {@link #connect()} is idempotent —
     * if we're already CONNECTING/CONNECTED by the time this fires it is a no-op — so a
     * few overlapping schedules can't double-initialise the SDK.
     */
    private void scheduleReconnect() {
        new Handler(Looper.getMainLooper()).postDelayed(this::connect, RECONNECT_DELAY_MS);
    }

    /** Send the HELLO control message once per connection, when first connected. */
    private void maybeSendHello() {
        if (!connected || device == null || helloSent) {
            return;
        }
        helloSent = true;
        Log.i(TAG, "Connected — sending HELLO to widget to prime GCM binding");
        sendMessage(helloMessage());
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

    /** Fire-and-forget send of a Map to the watch app (serialised to a Dictionary by the SDK). */
    public boolean sendMessage(Map<String, Object> message) {
        return sendMessageTo(iqApp, message);
    }

    private boolean sendMessageTo(IQApp targetApp, Map<String, Object> message) {
        final IQDevice d = device;
        if (!connected || d == null) {
            Log.w(TAG, "sendMessageTo: not connected");
            return false;
        }
        try {
            stateLd.postValue(ConnectIqState.SENDING);
            connectIQ.sendMessage(d, targetApp, message, (dev, app, status) -> {
                if (status != ConnectIQ.IQMessageStatus.SUCCESS) {
                    Log.e(TAG, "sendMessageTo status: " + status);
                }
                stateLd.postValue(ConnectIqState.CONNECTED);
            });
            return true;
        } catch (InvalidStateException | ServiceUnavailableException e) {
            Log.e(TAG, "sendMessageTo failed", e);
            stateLd.postValue(ConnectIqState.ERROR);
            return false;
        }
    }

    /** Decode JSON payload to a Map and send it to the watch app. */
    public boolean sendPayload(byte[] payload) {
        return sendPayloadTo(iqApp, payload);
    }

    /** Send a route/climb payload to the ClimbPro datafield app. */
    public boolean sendPayloadToDatafield(byte[] payload) {
        return sendPayloadTo(datafieldApp, payload);
    }

    /** Send a surface-sections payload to the surface datafield app. */
    public boolean sendPayloadToSurfaceField(byte[] payload) {
        return sendPayloadTo(surfaceApp, payload);
    }

    /**
     * Blocking send to the ClimbPro Onboard watch app (raw-route push channel).
     * Returns true only when the watch acknowledges SUCCESS within {@code timeoutMs}.
     * The raw-route transfer is push-only (the watch never NACKs), so a silently
     * dropped chunk would stall the watch's pending transfer forever — every
     * message must be individually acknowledged. No inbound registration is
     * needed — the watch never transmits anything back.
     */
    public boolean sendMessageToOnboardBlocking(Map<String, Object> message, long timeoutMs) {
        return sendMessageBlocking(onboardApp, message, timeoutMs);
    }

    private boolean sendPayloadTo(IQApp targetApp, byte[] payload) {
        try {
            return sendMessageTo(targetApp, PayloadCodec.decode(payload));
        } catch (IOException e) {
            Log.e(TAG, "sendPayloadTo: cannot parse payload JSON", e);
            return false;
        }
    }

    /**
     * Blocking send for use on WorkManager background threads. Returns true only
     * when the watch acknowledges SUCCESS within {@code timeoutMs}.
     */
    public boolean sendPayloadBlocking(byte[] payload, long timeoutMs) {
        final Map<String, Object> message;
        try {
            message = PayloadCodec.decode(payload);
        } catch (IOException e) {
            Log.e(TAG, "sendPayloadBlocking: cannot parse payload JSON", e);
            return false;
        }
        return sendMessageBlocking(iqApp, message, timeoutMs);
    }

    private boolean sendMessageBlocking(IQApp targetApp, Map<String, Object> message,
                                        long timeoutMs) {
        final IQDevice d = device;
        if (!connected || d == null) {
            Log.w(TAG, "sendMessageBlocking: not connected");
            return false;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<ConnectIQ.IQMessageStatus> result = new AtomicReference<>();
        try {
            connectIQ.sendMessage(d, targetApp, message, (dev, app, status) -> {
                result.set(status);
                latch.countDown();
            });
        } catch (InvalidStateException | ServiceUnavailableException e) {
            Log.e(TAG, "sendMessageBlocking failed", e);
            return false;
        }
        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "sendMessageBlocking: timed out");
                return false;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return result.get() == ConnectIQ.IQMessageStatus.SUCCESS;
    }

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

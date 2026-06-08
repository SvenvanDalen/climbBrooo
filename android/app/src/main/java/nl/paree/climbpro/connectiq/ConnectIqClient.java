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

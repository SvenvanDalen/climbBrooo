package nl.paree.climbpro.connectiq;

import android.content.Context;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * Wrapper around the Garmin Connect IQ Mobile SDK.
 *
 * The Garmin CIQ SDK AAR is added manually (see SETUP.md).
 * Until the AAR is present this class compiles but all operations are no-ops
 * that log a clear error — the rest of the app can be built and tested without
 * the SDK installed.
 *
 * When the SDK is added, import the following and implement the listeners:
 *   com.garmin.android.connectiq.ConnectIQ
 *   com.garmin.android.connectiq.IQDevice
 *   com.garmin.android.connectiq.IQApp
 */
public final class ConnectIqClient {

    private static final String TAG        = "ConnectIqClient";
    private static final String APP_ID     = "a3421399-b234-4d5e-b9c0-7b5a63e84d45"; // replace with real app UUID

    private final MutableLiveData<ConnectIqState> stateLd =
            new MutableLiveData<>(ConnectIqState.DISCONNECTED);

    private final Context context;

    public ConnectIqClient(Context context) {
        this.context = context.getApplicationContext();
    }

    public LiveData<ConnectIqState> state() { return stateLd; }

    /** Discover the paired Forerunner 255 Music and connect. */
    public void connect() {
        Log.w(TAG, "Garmin CIQ SDK not yet linked — connect() is a no-op. "
                + "Add ciq-mobile-sdk.aar to android/app/libs/ and uncomment SDK code.");
        stateLd.postValue(ConnectIqState.ERROR);
    }

    /**
     * Send a pre-serialised JSON payload to the datafield.
     *
     * @param payload JSON bytes — must be <= PayloadBudget.MAX_BYTES
     * @return true if sent successfully
     */
    public boolean sendPayload(byte[] payload) {
        Log.w(TAG, "Garmin CIQ SDK not linked — sendPayload() is a no-op.");
        return false;
    }

    public boolean sendMessage(Map<String, Object> message) {
        try {
            byte[] bytes = new ObjectMapper().writeValueAsBytes(message);
            return sendPayload(bytes);
        } catch (Exception e) {
            Log.e(TAG, "sendMessage serialization failed", e);
            return false;
        }
    }

    public void setWatchRequestHandler(WatchRequestHandler requestHandler) {
        Log.w(TAG, "setWatchRequestHandler: CIQ SDK not linked — incoming watch messages will be ignored.");
    }

    public void disconnect() {
        stateLd.postValue(ConnectIqState.DISCONNECTED);
    }
}

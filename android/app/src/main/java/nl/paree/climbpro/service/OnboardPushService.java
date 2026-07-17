package nl.paree.climbpro.service;

import android.util.Log;

import nl.paree.climbpro.connectiq.ConnectIqClient;
import nl.paree.climbpro.data.route.StoredRoute;

import java.util.List;
import java.util.Map;

/**
 * Pushes a route's raw geometry to the "ClimbPro Onboard" watch app,
 * unprompted. The watch never requests routes — the phone decides when to
 * send, triggered by the user tapping "Verstuur naar horloge" on a route's
 * detail screen (RouteDetailActivity).
 */
public final class OnboardPushService {

    private static final String TAG = "OnboardPushService";

    // The watch swaps its pending transfer live only when EVERY chunk has landed,
    // and push-only means it can never ask for a resend — so each message blocks
    // on the device ACK and gets one retry before the whole push is aborted.
    // Duplicates from a retry are safe: RawRouteStore ignores an already-seen seq.
    static final long SEND_TIMEOUT_MS = 10_000;
    static final int  SEND_ATTEMPTS   = 2;

    private final ConnectIqClient connectIqClient;
    private final RawRoutePayloadBuilder builder = new RawRoutePayloadBuilder();

    public OnboardPushService(ConnectIqClient connectIqClient) {
        this.connectIqClient = connectIqClient;
    }

    /**
     * Sends RAW_HDR then every RAW_CHUNK in order, each acknowledged by the
     * watch; stops at the first message that fails all attempts. Blocking —
     * call from a background thread.
     */
    public boolean pushRoute(StoredRoute route) {
        if (route == null || route.lats == null || route.lats.length < 2) {
            Log.w(TAG, "pushRoute: route has no geometry");
            return false;
        }
        List<Map<String, Object>> messages = builder.buildMessages(route);
        for (Map<String, Object> m : messages) {
            if (!sendAcknowledged(m)) {
                Log.w(TAG, "pushRoute: delivery failed for " + m.get("type")
                        + (m.containsKey("seq") ? " seq=" + m.get("seq") : ""));
                return false;
            }
        }
        Log.i(TAG, "Pushed route " + route.routeId + " in " + (messages.size() - 1) + " chunks");
        return true;
    }

    private boolean sendAcknowledged(Map<String, Object> message) {
        for (int attempt = 1; attempt <= SEND_ATTEMPTS; attempt++) {
            if (connectIqClient.sendMessageToOnboardBlocking(message, SEND_TIMEOUT_MS)) {
                return true;
            }
            Log.w(TAG, "sendAcknowledged: attempt " + attempt + " failed for "
                    + message.get("type"));
        }
        return false;
    }
}

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

    private final ConnectIqClient connectIqClient;
    private final RawRoutePayloadBuilder builder = new RawRoutePayloadBuilder();

    public OnboardPushService(ConnectIqClient connectIqClient) {
        this.connectIqClient = connectIqClient;
    }

    /** Sends RAW_HDR then every RAW_CHUNK in order; stops at the first failed send. */
    public boolean pushRoute(StoredRoute route) {
        if (route == null || route.lats == null || route.lats.length < 2) {
            Log.w(TAG, "pushRoute: route has no geometry");
            return false;
        }
        List<Map<String, Object>> messages = builder.buildMessages(route);
        for (Map<String, Object> m : messages) {
            if (!connectIqClient.sendMessageToOnboard(m)) {
                Log.w(TAG, "pushRoute: send failed for " + m.get("type"));
                return false;
            }
        }
        Log.i(TAG, "Pushed route " + route.routeId + " in " + (messages.size() - 1) + " chunks");
        return true;
    }
}

package nl.paree.climbpro.connectiq;

import android.util.Log;

import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.service.ClimbPayloadBuilder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WatchRequestHandler {

    private static final String TAG = "WatchRequestHandler";

    private final RouteRepository routeRepo;
    private final ConnectIqClient connectIqClient;
    private final ObjectMapper    mapper;

    public WatchRequestHandler(RouteRepository routeRepo, ConnectIqClient connectIqClient) {
        this.routeRepo       = routeRepo;
        this.connectIqClient = connectIqClient;
        this.mapper          = new ObjectMapper();
    }

    public void handleMessage(Map<String, Object> message) {
        if (message == null) return;
        String type = (String) message.get("type");
        if ("LIST_ROUTES".equals(type)) {
            handleListRoutes();
        } else if ("LOAD_ROUTE".equals(type)) {
            handleLoadRoute((String) message.get("id"));
        } else {
            Log.w(TAG, "Unknown message type from watch: " + type);
        }
    }

    private void handleListRoutes() {
        List<RouteCatalogEntry>   catalog = routeRepo.loadCatalog();
        List<Map<String, Object>> routes  = new ArrayList<>(catalog.size());
        for (RouteCatalogEntry e : catalog) {
            Map<String, Object> route = new LinkedHashMap<>();
            route.put("id",         e.routeId);
            route.put("name",       e.userDisplayName != null ? e.userDisplayName : e.name);
            route.put("climbCount", e.climbCount);
            routes.add(route);
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type",   "ROUTE_LIST");
        response.put("routes", routes);
        connectIqClient.sendMessage(response);
        Log.i(TAG, "Sent ROUTE_LIST with " + routes.size() + " routes");
    }

    private void handleLoadRoute(String routeId) {
        if (routeId == null || routeId.isEmpty()) {
            Log.w(TAG, "LOAD_ROUTE: missing routeId");
            return;
        }
        try {
            StoredRoute route   = routeRepo.loadRoute(routeId);
            byte[]      payload = new ClimbPayloadBuilder(mapper).buildRoutePayload(route);
            connectIqClient.sendPayload(payload);
            Log.i(TAG, "Sent route payload for " + routeId + " (" + payload.length + " bytes)");
        } catch (IOException e) {
            Log.e(TAG, "LOAD_ROUTE failed for " + routeId, e);
        }
    }
}

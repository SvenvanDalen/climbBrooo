package nl.paree.climbpro.connectiq;

import android.util.Log;

import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.data.rider.RiderProfileRepository;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.service.ClimbPayloadBuilder;
import nl.paree.climbpro.service.RoutePacingPlanner;

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
    private final RiderProfileRepository riderRepo;

    public WatchRequestHandler(RouteRepository routeRepo, ConnectIqClient connectIqClient) {
        this(routeRepo, connectIqClient, null);
    }

    public WatchRequestHandler(RouteRepository routeRepo, ConnectIqClient connectIqClient,
                               RiderProfileRepository riderRepo) {
        this.routeRepo       = routeRepo;
        this.connectIqClient = connectIqClient;
        this.mapper          = new ObjectMapper();
        this.riderRepo       = riderRepo;
    }

    /** Per-climb target seconds for the route, or null when no profile repo / incomplete profile. */
    private int[][] pacingPlan(StoredRoute route) {
        if (riderRepo == null) return null;
        RiderProfile profile = riderRepo.load();
        return RoutePacingPlanner.plan(route, profile);
    }

    public void handleMessage(Map<String, Object> message) {
        if (message == null) return;
        String type = (String) message.get("type");
        if ("LIST_ROUTES".equals(type)) {
            handleListRoutes();
        } else if ("LOAD_ROUTE".equals(type)) {
            handleLoadRoute((String) message.get("id"));
        } else if ("SET_ACTIVE_ROUTE".equals(type)) {
            handleSetActiveRoute((String) message.get("id"));
        } else if ("SET_ACTIVE_CLIMB".equals(type)) {
            Object idx = message.get("climbIdx");
            handleSetActiveClimb((String) message.get("id"),
                    idx instanceof Number ? ((Number) idx).intValue() : -1);
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
        boolean ok = connectIqClient.sendMessage(response);
        Log.i(TAG, "ROUTE_LIST with " + routes.size() + " routes — sent=" + ok);
    }

    private void handleLoadRoute(String routeId) {
        if (routeId == null || routeId.isEmpty()) {
            Log.w(TAG, "LOAD_ROUTE: missing routeId");
            return;
        }
        try {
            StoredRoute route   = routeRepo.loadRoute(routeId);
            byte[]      payload = new ClimbPayloadBuilder(mapper)
                    .buildRoutePayload(route, pacingPlan(route));
            connectIqClient.sendPayload(payload);
            Log.i(TAG, "Sent route payload for " + routeId + " (" + payload.length + " bytes)");
        } catch (IOException e) {
            Log.e(TAG, "LOAD_ROUTE failed for " + routeId, e);
        }
    }

    private void handleSetActiveRoute(String routeId) {
        if (routeId == null || routeId.isEmpty()) {
            ackActiveSet(false, null);
            return;
        }
        try {
            StoredRoute route = routeRepo.loadRoute(routeId);
            ClimbPayloadBuilder builder = new ClimbPayloadBuilder(mapper);
            boolean ok = connectIqClient.sendPayloadToDatafield(
                    builder.buildRoutePayload(route, pacingPlan(route)));
            // Always push the surface payload — an empty surfSec clears stale sections.
            connectIqClient.sendPayloadToSurfaceField(builder.buildSurfaceSectionPayload(route));
            String name = route.userDisplayName != null ? route.userDisplayName : route.name;
            ackActiveSet(ok, name);
            Log.i(TAG, "SET_ACTIVE_ROUTE " + routeId + " ok=" + ok);
        } catch (IOException | IllegalArgumentException e) {
            Log.e(TAG, "SET_ACTIVE_ROUTE failed for " + routeId, e);
            ackActiveSet(false, null);
        }
    }

    private void handleSetActiveClimb(String routeId, int climbIndex) {
        if (routeId == null || routeId.isEmpty() || climbIndex < 0) {
            ackActiveSet(false, null);
            return;
        }
        try {
            StoredRoute route = routeRepo.loadRoute(routeId);
            byte[] payload = new ClimbPayloadBuilder(mapper)
                    .buildSingleClimbPayload(route, climbIndex, pacingPlan(route));
            boolean ok = connectIqClient.sendPayloadToDatafield(payload);
            StoredClimb climb = route.climbs.get(climbIndex);
            String name = climb.userDisplayName != null ? climb.userDisplayName : climb.name;
            ackActiveSet(ok, name);
            Log.i(TAG, "SET_ACTIVE_CLIMB " + routeId + "[" + climbIndex + "] ok=" + ok);
        } catch (IOException | IllegalArgumentException e) {
            Log.e(TAG, "SET_ACTIVE_CLIMB failed for " + routeId + "[" + climbIndex + "]", e);
            ackActiveSet(false, null);
        }
    }

    private void ackActiveSet(boolean ok, String name) {
        Map<String, Object> ack = new LinkedHashMap<>();
        ack.put("type", "ACTIVE_SET");
        ack.put("ok",   ok);
        if (name != null) ack.put("name", name);
        connectIqClient.sendMessage(ack);
    }
}

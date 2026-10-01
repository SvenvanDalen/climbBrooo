package nl.paree.climbpro.connectiq;

import android.util.Log;

import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.data.rider.RiderProfileRepository;
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredClimbAttempt;
import nl.paree.climbpro.data.route.StoredRoute;
import nl.paree.climbpro.data.watch.WatchFieldLayoutStore;
import nl.paree.climbpro.domain.power.RiderProfile;
import nl.paree.climbpro.service.ClimbPayloadBuilder;
import nl.paree.climbpro.service.CombinedRefTimePlanner;
import nl.paree.climbpro.service.RoutePacingPlanner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WatchRequestHandler {

    private static final String TAG = "WatchRequestHandler";

    private final RouteRepository routeRepo;
    private final ConnectIqClient connectIqClient;
    private final ObjectMapper    mapper;
    private final RiderProfileRepository riderRepo;
    private final ClimbAttemptRepository attemptRepo;
    /** Color palette for 'pal' (issue #258); default palette until the app sets a source. */
    private volatile java.util.function.IntSupplier paletteSource =
            () -> nl.paree.climbpro.domain.segment.GradientPalette.DEFAULT;
    /** Medical ID re-sent with every route list (issue #230); null = never sent. */
    private nl.paree.climbpro.data.medical.MedicalIdRepository medicalIdRepo;
    /** Display units sent as 'un' (issue #262); null = metric (no key). */
    private final nl.paree.climbpro.data.settings.UnitPreferencesRepository unitsRepo;
    /** Datafield slot layout sent as 'lay'; null = default layout (no key). */
    private volatile WatchFieldLayoutStore layoutStore;

    public WatchRequestHandler(RouteRepository routeRepo, ConnectIqClient connectIqClient) {
        this(routeRepo, connectIqClient, null, null);
    }

    public WatchRequestHandler(RouteRepository routeRepo, ConnectIqClient connectIqClient,
                               RiderProfileRepository riderRepo) {
        this(routeRepo, connectIqClient, riderRepo, null);
    }

    public WatchRequestHandler(RouteRepository routeRepo, ConnectIqClient connectIqClient,
                               RiderProfileRepository riderRepo, ClimbAttemptRepository attemptRepo) {
        this(routeRepo, connectIqClient, riderRepo, attemptRepo, null);
    }

    public WatchRequestHandler(RouteRepository routeRepo, ConnectIqClient connectIqClient,
                               RiderProfileRepository riderRepo, ClimbAttemptRepository attemptRepo,
                               nl.paree.climbpro.data.settings.UnitPreferencesRepository unitsRepo) {
        this.unitsRepo       = unitsRepo;
        this.routeRepo       = routeRepo;
        this.connectIqClient = connectIqClient;
        this.mapper          = new ObjectMapper();
        this.riderRepo       = riderRepo;
        this.attemptRepo     = attemptRepo;
    }

    /**
     * Where watch-requested payloads read the rider's palette choice from (issue #258), so a
     * LOAD_ROUTE / SET_ACTIVE_ROUTE answer uses the same colors as the background sync.
     */
    public void setPaletteSource(java.util.function.IntSupplier source) {
        if (source != null) this.paletteSource = source;
    }

    /** Enables the datafield slot layout ('lay') in watch-requested payloads. */
    public void setFieldLayoutStore(WatchFieldLayoutStore store) {
        this.layoutStore = store;
    }

    /**
     * Enables the MEDICAL_ID message (issue #230): the widget receives phone messages only
     * while it is open, so the phone re-sends the ID every time the widget lists routes.
     */
    public void setMedicalIdRepository(nl.paree.climbpro.data.medical.MedicalIdRepository repo) {
        this.medicalIdRepo = repo;
    }

    /** Per-climb target seconds for the route, or null when no profile repo / incomplete profile. */
    private int[][] pacingPlan(StoredRoute route) {
        int[][] plan = null;
        if (riderRepo != null) {
            RiderProfile profile = riderRepo.load();
            plan = RoutePacingPlanner.plan(route, profile);
        }
        return nl.paree.climbpro.service.SegmentTargetOverrideMerger.merge(route, plan);
    }

    /** Rider FTP for interval blocks (issue #180); 0 when unknown or no profile repo. */
    private int ftpWatts() {
        return riderRepo != null ? riderRepo.load().ftpWatts : 0;
    }

    /**
     * Payload builder that also sends the per-segment FTP intensity-zone colors (issue #66)
     * when a rider profile is available; without one the payload is unchanged. Also carries
     * the rider's display units (issue #262) when a units repo is wired up, and the
     * datafield slot layout ('lay') when a layout store is set.
     */
    private ClimbPayloadBuilder payloadBuilder() {
        ClimbPayloadBuilder builder = new ClimbPayloadBuilder(mapper)
                .withPalette(paletteSource.getAsInt());
        if (unitsRepo != null) builder = builder.withUnits(unitsRepo.load());
        builder = builder.withFieldLayout(layoutStore != null ? layoutStore.load() : null);
        return riderRepo != null ? builder.withIntensityZones(riderRepo.load()) : builder;
    }

    /**
     * Per-climb per-segment refsec: manual WR/pro reference (issue #59) takes priority over
     * the rider's own PR when set for that climb. Own PR is unavailable (null) when no
     * attempt repo is wired up, but a manual reference still works in that case. Climbs
     * with neither fall back to the virtual target-speed ghost (issue #31) when set.
     */
    private int[][] refPlan(StoredRoute route) {
        List<StoredClimbAttempt> attempts = attemptRepo != null
                ? attemptRepo.loadAll() : Collections.emptyList();
        nl.paree.climbpro.domain.power.GhostTarget ghost = riderRepo != null
                ? riderRepo.loadGhostTarget() : null;
        return CombinedRefTimePlanner.plan(route, attempts, ghost);
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
        sendMedicalId();
    }

    /**
     * Sends the stored medical ID to the widget (issue #230). An empty ID is sent too: it
     * tells the watch to delete a copy the rider has since cleared on the phone.
     */
    private void sendMedicalId() {
        if (medicalIdRepo == null) return;
        boolean ok = connectIqClient.sendMessage(medicalIdRepo.load().toWatchMessage());
        Log.i(TAG, "MEDICAL_ID sent=" + ok);
    }

    private void handleLoadRoute(String routeId) {
        if (routeId == null || routeId.isEmpty()) {
            Log.w(TAG, "LOAD_ROUTE: missing routeId");
            return;
        }
        try {
            StoredRoute route   = routeRepo.loadRoute(routeId);
            byte[]      payload = payloadBuilder().withFtpWatts(ftpWatts())
                    .buildRoutePayload(route, pacingPlan(route), refPlan(route));
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
            ClimbPayloadBuilder builder = payloadBuilder().withFtpWatts(ftpWatts());
            boolean ok = connectIqClient.sendPayloadToDatafield(
                    builder.buildRoutePayload(route, pacingPlan(route), refPlan(route)));
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
            byte[] payload = payloadBuilder().withFtpWatts(ftpWatts())
                    .buildSingleClimbPayload(route, climbIndex, pacingPlan(route), refPlan(route));
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

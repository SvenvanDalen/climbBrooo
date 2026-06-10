package nl.paree.climbpro.connectiq;

import nl.paree.climbpro.data.route.RouteCatalogEntry;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.data.route.StoredClimb;
import nl.paree.climbpro.data.route.StoredRoute;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class WatchRequestHandlerTest {

    @Mock RouteRepository mockRepo;
    @Mock ConnectIqClient mockClient;

    @Test
    public void listRoutes_emptyRepo_sendsRouteListWithEmptyArray() {
        when(mockRepo.loadCatalog()).thenReturn(Collections.emptyList());
        WatchRequestHandler handler = new WatchRequestHandler(mockRepo, mockClient);
        handler.handleMessage(msg("type", "LIST_ROUTES"));
        ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
        verify(mockClient).sendMessage(captor.capture());
        assertEquals("ROUTE_LIST", captor.getValue().get("type"));
        assertTrue(((List<?>) captor.getValue().get("routes")).isEmpty());
    }

    @Test
    public void listRoutes_withEntries_includesIdNameClimbCount() {
        RouteCatalogEntry e = new RouteCatalogEntry();
        e.routeId    = "abc123";
        e.name       = "Mont Ventoux";
        e.climbCount = 2;
        when(mockRepo.loadCatalog()).thenReturn(Collections.singletonList(e));
        WatchRequestHandler handler = new WatchRequestHandler(mockRepo, mockClient);
        handler.handleMessage(msg("type", "LIST_ROUTES"));
        ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
        verify(mockClient).sendMessage(captor.capture());
        List<Map<?, ?>> routes = (List<Map<?, ?>>) captor.getValue().get("routes");
        assertEquals(1, routes.size());
        assertEquals("abc123", routes.get(0).get("id"));
        assertEquals("Mont Ventoux", routes.get(0).get("name"));
        assertEquals(2, routes.get(0).get("climbCount"));
    }

    @Test
    public void listRoutes_prefersUserDisplayName_overName() {
        RouteCatalogEntry e = new RouteCatalogEntry();
        e.routeId         = "r1";
        e.name            = "original";
        e.userDisplayName = "renamed";
        e.climbCount      = 0;
        when(mockRepo.loadCatalog()).thenReturn(Collections.singletonList(e));
        WatchRequestHandler handler = new WatchRequestHandler(mockRepo, mockClient);
        handler.handleMessage(msg("type", "LIST_ROUTES"));
        ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
        verify(mockClient).sendMessage(captor.capture());
        List<Map<?, ?>> routes = (List<Map<?, ?>>) captor.getValue().get("routes");
        assertEquals("renamed", routes.get(0).get("name"));
    }

    @Test
    public void loadRoute_existingRoute_sendsPayload() throws IOException {
        StoredRoute route = new StoredRoute();
        route.routeId = "r1";
        route.name    = "Test route";
        route.climbs  = new ArrayList<>();
        when(mockRepo.loadRoute("r1")).thenReturn(route);
        WatchRequestHandler handler = new WatchRequestHandler(mockRepo, mockClient);
        Map<String, Object> request = new HashMap<>();
        request.put("type", "LOAD_ROUTE");
        request.put("id",   "r1");
        handler.handleMessage(request);
        verify(mockClient).sendPayload(any(byte[].class));
    }

    @Test
    public void loadRoute_missingId_doesNotSend() {
        WatchRequestHandler handler = new WatchRequestHandler(mockRepo, mockClient);
        handler.handleMessage(msg("type", "LOAD_ROUTE"));
        verifyNoInteractions(mockClient);
    }

    @Test
    public void loadRoute_repoThrows_doesNotSend() throws IOException {
        when(mockRepo.loadRoute(any())).thenThrow(new IOException("not found"));
        WatchRequestHandler handler = new WatchRequestHandler(mockRepo, mockClient);
        Map<String, Object> request = new HashMap<>();
        request.put("type", "LOAD_ROUTE");
        request.put("id",   "missing");
        handler.handleMessage(request);
        verify(mockClient, never()).sendPayload(any());
    }

    @Test
    public void unknownType_doesNotSend() {
        WatchRequestHandler handler = new WatchRequestHandler(mockRepo, mockClient);
        handler.handleMessage(msg("type", "BOGUS"));
        verifyNoInteractions(mockClient);
    }

    @Test
    public void setActiveRoute_sendsToBothDatafields_andAcksOk() throws IOException {
        StoredRoute route = new StoredRoute();
        route.routeId = "r1";
        route.name    = "Test route";
        route.climbs  = new ArrayList<>();
        when(mockRepo.loadRoute("r1")).thenReturn(route);
        when(mockClient.sendPayloadToDatafield(any(byte[].class))).thenReturn(true);
        WatchRequestHandler handler = new WatchRequestHandler(mockRepo, mockClient);

        Map<String, Object> request = new HashMap<>();
        request.put("type", "SET_ACTIVE_ROUTE");
        request.put("id",   "r1");
        handler.handleMessage(request);

        verify(mockClient).sendPayloadToDatafield(any(byte[].class));
        verify(mockClient).sendPayloadToSurfaceField(any(byte[].class));
        ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
        verify(mockClient).sendMessage(captor.capture());
        assertEquals("ACTIVE_SET", captor.getValue().get("type"));
        assertEquals(Boolean.TRUE, captor.getValue().get("ok"));
        assertEquals("Test route", captor.getValue().get("name"));
    }

    @Test
    public void setActiveRoute_repoThrows_acksFailure() throws IOException {
        when(mockRepo.loadRoute("missing")).thenThrow(new IOException("not found"));
        WatchRequestHandler handler = new WatchRequestHandler(mockRepo, mockClient);

        Map<String, Object> request = new HashMap<>();
        request.put("type", "SET_ACTIVE_ROUTE");
        request.put("id",   "missing");
        handler.handleMessage(request);

        verify(mockClient, never()).sendPayloadToDatafield(any(byte[].class));
        ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
        verify(mockClient).sendMessage(captor.capture());
        assertEquals("ACTIVE_SET", captor.getValue().get("type"));
        assertEquals(Boolean.FALSE, captor.getValue().get("ok"));
    }

    @Test
    public void setActiveClimb_sendsSingleClimb_noSurfacePayload() throws IOException {
        StoredRoute route = new StoredRoute();
        route.routeId = "r1";
        route.name    = "Test route";
        route.climbs  = new ArrayList<>();
        StoredClimb a = new StoredClimb();
        a.name = "A";
        StoredClimb b = new StoredClimb();
        b.name = "B";
        route.climbs.add(a);
        route.climbs.add(b);
        when(mockRepo.loadRoute("r1")).thenReturn(route);
        when(mockClient.sendPayloadToDatafield(any(byte[].class))).thenReturn(true);
        WatchRequestHandler handler = new WatchRequestHandler(mockRepo, mockClient);

        Map<String, Object> request = new HashMap<>();
        request.put("type",     "SET_ACTIVE_CLIMB");
        request.put("id",       "r1");
        request.put("climbIdx", 1);
        handler.handleMessage(request);

        ArgumentCaptor<byte[]> payloadCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(mockClient).sendPayloadToDatafield(payloadCaptor.capture());
        verify(mockClient, never()).sendPayloadToSurfaceField(any(byte[].class));
        Map<?, ?> decoded = new com.fasterxml.jackson.databind.ObjectMapper()
                .readValue(payloadCaptor.getValue(), Map.class);
        List<?> climbs = (List<?>) decoded.get("climbs");
        assertEquals(1, climbs.size());
        assertEquals("B", ((Map<?, ?>) climbs.get(0)).get("n"));

        ArgumentCaptor<Map> ackCaptor = ArgumentCaptor.forClass(Map.class);
        verify(mockClient).sendMessage(ackCaptor.capture());
        assertEquals(Boolean.TRUE, ackCaptor.getValue().get("ok"));
        assertEquals("B", ackCaptor.getValue().get("name"));
    }

    @Test
    public void setActiveClimb_badIndex_acksFailure() throws IOException {
        StoredRoute route = new StoredRoute();
        route.routeId = "r1";
        route.climbs  = new ArrayList<>();
        when(mockRepo.loadRoute("r1")).thenReturn(route);
        WatchRequestHandler handler = new WatchRequestHandler(mockRepo, mockClient);

        Map<String, Object> request = new HashMap<>();
        request.put("type",     "SET_ACTIVE_CLIMB");
        request.put("id",       "r1");
        request.put("climbIdx", 5);
        handler.handleMessage(request);

        verify(mockClient, never()).sendPayloadToDatafield(any(byte[].class));
        ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
        verify(mockClient).sendMessage(captor.capture());
        assertEquals(Boolean.FALSE, captor.getValue().get("ok"));
    }

    private static Map<String, Object> msg(String key, String value) {
        Map<String, Object> m = new HashMap<>();
        m.put(key, value);
        return m;
    }
}

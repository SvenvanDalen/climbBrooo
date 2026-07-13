package nl.paree.climbpro.service;

import nl.paree.climbpro.connectiq.ConnectIqClient;
import nl.paree.climbpro.data.route.StoredRoute;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

@RunWith(MockitoJUnitRunner.class)
public class OnboardPushServiceTest {

    @Mock ConnectIqClient mockClient;

    private static StoredRoute route(int n) {
        StoredRoute r = new StoredRoute();
        r.routeId = "r1";
        r.name = "Rit";
        r.lats = new double[n];
        r.lons = new double[n];
        r.elevations = new double[n];
        for (int i = 0; i < n; i++) {
            r.lats[i] = 50.0 + i * 0.0009;
            r.lons[i] = 5.0;
            r.elevations[i] = 100.0;
        }
        return r;
    }

    @Test
    public void pushRoute_sendsHeaderThenChunksInOrder() {
        when(mockClient.sendMessageToOnboard(any())).thenReturn(true);
        OnboardPushService svc = new OnboardPushService(mockClient);
        boolean ok = svc.pushRoute(route(251));
        assertTrue(ok);
        ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
        verify(mockClient, times(3)).sendMessageToOnboard(captor.capture());
        List<Map> sent = captor.getAllValues();
        assertEquals("RAW_HDR", sent.get(0).get("type"));
        assertEquals("RAW_CHUNK", sent.get(1).get("type"));
        assertEquals(0, sent.get(1).get("seq"));
        assertEquals(1, sent.get(2).get("seq"));
    }

    @Test
    public void pushRoute_nullRoute_sendsNothingAndReturnsFalse() {
        OnboardPushService svc = new OnboardPushService(mockClient);
        assertFalse(svc.pushRoute(null));
        verifyNoInteractions(mockClient);
    }

    @Test
    public void pushRoute_sendFailure_stopsAndReturnsFalse() {
        when(mockClient.sendMessageToOnboard(any())).thenReturn(true, false, true);
        OnboardPushService svc = new OnboardPushService(mockClient);
        boolean ok = svc.pushRoute(route(251));
        assertFalse(ok);
        verify(mockClient, times(2)).sendMessageToOnboard(any());
    }
}

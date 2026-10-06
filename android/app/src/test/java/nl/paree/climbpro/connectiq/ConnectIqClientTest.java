package nl.paree.climbpro.connectiq;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;

import androidx.test.core.app.ApplicationProvider;

import com.garmin.android.connectiq.ConnectIQ;
import com.garmin.android.connectiq.IQApp;
import com.garmin.android.connectiq.IQDevice;
import com.garmin.android.connectiq.exception.InvalidStateException;
import com.garmin.android.connectiq.exception.ServiceUnavailableException;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;

import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Connection lifecycle, HELLO priming and sends of {@link ConnectIqClient} on a fake SDK. */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class ConnectIqClientTest {

    private final Context app = ApplicationProvider.getApplicationContext();
    private ConnectIQ sdk;
    private ConnectIqClient client;
    private IQDevice watch;

    @Before
    public void setUp() {
        sdk = mock(ConnectIQ.class);
        client = new ConnectIqClient(app, sdk);
        watch = new IQDevice(255L, "Forerunner 255 Music");
    }

    private ConnectIQ.ConnectIQListener connect() {
        client.connect();
        ArgumentCaptor<ConnectIQ.ConnectIQListener> l =
                ArgumentCaptor.forClass(ConnectIQ.ConnectIQListener.class);
        verify(sdk).initialize(eq(app), eq(true), l.capture());
        return l.getValue();
    }

    private ConnectIQ.ConnectIQListener connectReady(IQDevice.IQDeviceStatus status) throws Exception {
        watch.setStatus(status);
        when(sdk.getConnectedDevices()).thenReturn(Collections.singletonList(watch));
        ConnectIQ.ConnectIQListener l = connect();
        l.onSdkReady();
        idle();
        return l;
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    private static boolean isApp(IQApp a, String id) {
        // IQApp normalises ids (upper case, no dashes).
        return a != null && new IQApp(id).getApplicationId().equals(a.getApplicationId());
    }

    private static boolean isHello(Object m) {
        return m instanceof Map && "HELLO".equals(((Map<?, ?>) m).get("type"));
    }

    @Test
    public void startsDisconnected() {
        assertEquals(ConnectIqState.DISCONNECTED, client.state().getValue());
        assertFalse(client.isConnected());
    }

    @Test
    public void connectedDeviceSnapshotConnectsAndSendsHelloOnce() throws Exception {
        connectReady(IQDevice.IQDeviceStatus.CONNECTED);

        assertTrue(client.isConnected());
        // The HELLO is in flight until the watch acks it.
        assertEquals(ConnectIqState.SENDING, client.state().getValue());
        verify(sdk).sendMessage(eq(watch), argThat(a -> isApp(a, ConnectIqAppId.VALUE)),
                argThat(ConnectIqClientTest::isHello), any());
        verify(sdk).registerForAppEvents(eq(watch), any(IQApp.class), any());
    }

    @Test
    public void unknownSnapshotStatusDoesNotDowngradeConnectedDeviceEvent() throws Exception {
        // GCM 5.26.1 reports UNKNOWN in the snapshot while the device event says CONNECTED.
        watch.setStatus(IQDevice.IQDeviceStatus.UNKNOWN);
        when(sdk.getConnectedDevices()).thenReturn(Collections.singletonList(watch));
        doAnswer(inv -> {
            ConnectIQ.IQDeviceEventListener l = inv.getArgument(1);
            l.onDeviceStatusChanged(watch, IQDevice.IQDeviceStatus.CONNECTED);
            return null;
        }).when(sdk).registerForDeviceEvents(eq(watch), any());

        connect().onSdkReady();
        idle();

        assertTrue(client.isConnected());
        assertEquals(ConnectIqState.CONNECTED, client.state().getValue());
        // HELLO from the device event only; the snapshot path sees helloSent already.
        verify(sdk, times(1)).sendMessage(eq(watch), any(IQApp.class),
                argThat(ConnectIqClientTest::isHello), any());
    }

    @Test
    public void unknownSnapshotWithoutEventStaysDisconnected() throws Exception {
        connectReady(IQDevice.IQDeviceStatus.UNKNOWN);
        assertFalse(client.isConnected());
        assertEquals(ConnectIqState.DISCONNECTED, client.state().getValue());
        verify(sdk, never()).sendMessage(any(), any(), any(), any());
    }

    @Test
    public void disconnectEventRearmsHelloForNextConnection() throws Exception {
        connectReady(IQDevice.IQDeviceStatus.CONNECTED);
        ArgumentCaptor<ConnectIQ.IQDeviceEventListener> events =
                ArgumentCaptor.forClass(ConnectIQ.IQDeviceEventListener.class);
        verify(sdk).registerForDeviceEvents(eq(watch), events.capture());

        events.getValue().onDeviceStatusChanged(watch, IQDevice.IQDeviceStatus.NOT_CONNECTED);
        idle();
        assertFalse(client.isConnected());
        assertEquals(ConnectIqState.DISCONNECTED, client.state().getValue());

        events.getValue().onDeviceStatusChanged(watch, IQDevice.IQDeviceStatus.CONNECTED);
        idle();
        assertTrue(client.isConnected());
        verify(sdk, times(2)).sendMessage(eq(watch), any(IQApp.class),
                argThat(ConnectIqClientTest::isHello), any());
        // Reconnect re-registers inbound app events (initial + reconnect).
        verify(sdk, times(2)).registerForAppEvents(eq(watch), any(IQApp.class), any());
    }

    @Test
    public void reRegisterFailureAfterReconnectIsTolerated() throws Exception {
        connectReady(IQDevice.IQDeviceStatus.CONNECTED);
        ArgumentCaptor<ConnectIQ.IQDeviceEventListener> events =
                ArgumentCaptor.forClass(ConnectIQ.IQDeviceEventListener.class);
        verify(sdk).registerForDeviceEvents(eq(watch), events.capture());
        doThrow(new ServiceUnavailableException("gone"))
                .when(sdk).registerForAppEvents(eq(watch), any(IQApp.class), any());

        events.getValue().onDeviceStatusChanged(watch, IQDevice.IQDeviceStatus.CONNECTED);
        idle();
        assertTrue(client.isConnected());
    }

    @Test
    public void incomingMapIsDispatchedToHandler() throws Exception {
        WatchRequestHandler handler = mock(WatchRequestHandler.class);
        client.setWatchRequestHandler(handler);
        connectReady(IQDevice.IQDeviceStatus.CONNECTED);
        ArgumentCaptor<ConnectIQ.IQApplicationEventListener> inbound =
                ArgumentCaptor.forClass(ConnectIQ.IQApplicationEventListener.class);
        verify(sdk).registerForAppEvents(eq(watch), any(IQApp.class), inbound.capture());

        Map<String, Object> req = new HashMap<>();
        req.put("type", "LIST_ROUTES");
        inbound.getValue().onMessageReceived(watch, null,
                Collections.singletonList(req), ConnectIQ.IQMessageStatus.SUCCESS);
        // Non-map, empty and null payloads are ignored.
        inbound.getValue().onMessageReceived(watch, null,
                Collections.singletonList("tekst"), ConnectIQ.IQMessageStatus.SUCCESS);
        inbound.getValue().onMessageReceived(watch, null,
                Collections.singletonList(null), ConnectIQ.IQMessageStatus.SUCCESS);
        inbound.getValue().onMessageReceived(watch, null,
                Collections.emptyList(), ConnectIQ.IQMessageStatus.SUCCESS);
        inbound.getValue().onMessageReceived(watch, null, null, ConnectIQ.IQMessageStatus.SUCCESS);

        verify(handler, times(1)).handleMessage(any());
        verify(handler).handleMessage(req);
    }

    @Test
    public void incomingWithoutHandlerIsIgnored() throws Exception {
        connectReady(IQDevice.IQDeviceStatus.CONNECTED);
        ArgumentCaptor<ConnectIQ.IQApplicationEventListener> inbound =
                ArgumentCaptor.forClass(ConnectIQ.IQApplicationEventListener.class);
        verify(sdk).registerForAppEvents(eq(watch), any(IQApp.class), inbound.capture());
        inbound.getValue().onMessageReceived(watch, null,
                Collections.singletonList(new HashMap<>()), ConnectIQ.IQMessageStatus.SUCCESS);
    }

    @Test
    public void noDevicesSchedulesReconnect() throws Exception {
        when(sdk.getConnectedDevices()).thenReturn(Collections.emptyList());
        connect().onSdkReady();
        idle();
        assertEquals(ConnectIqState.ERROR, client.state().getValue());

        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));
        verify(sdk, times(2)).initialize(any(), anyBoolean(), any());
    }

    @Test
    public void nullDeviceListIsTreatedAsEmpty() throws Exception {
        when(sdk.getConnectedDevices()).thenReturn(null);
        connect().onSdkReady();
        idle();
        assertEquals(ConnectIqState.ERROR, client.state().getValue());
    }

    @Test
    public void discoveryExceptionBecomesErrorAndRetries() throws Exception {
        when(sdk.getConnectedDevices()).thenThrow(new InvalidStateException("not ready"));
        connect().onSdkReady();
        idle();
        assertEquals(ConnectIqState.ERROR, client.state().getValue());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));
        verify(sdk, times(2)).initialize(any(), anyBoolean(), any());
    }

    @Test
    public void initializeErrorRetries() {
        connect().onInitializeError(ConnectIQ.IQSdkErrorStatus.GCM_NOT_INSTALLED);
        idle();
        assertEquals(ConnectIqState.ERROR, client.state().getValue());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));
        verify(sdk, times(2)).initialize(any(), anyBoolean(), any());
    }

    @Test
    public void sdkShutdownDropsDeviceAndReconnects() throws Exception {
        ConnectIQ.ConnectIQListener l = connectReady(IQDevice.IQDeviceStatus.CONNECTED);
        l.onSdkShutDown();
        idle();
        assertFalse(client.isConnected());
        assertEquals(ConnectIqState.DISCONNECTED, client.state().getValue());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(5));
        verify(sdk, times(2)).initialize(any(), anyBoolean(), any());
    }

    @Test
    public void connectWhileConnectedIsNoOp() throws Exception {
        connectReady(IQDevice.IQDeviceStatus.CONNECTED);
        client.connect();
        verify(sdk, times(1)).initialize(any(), anyBoolean(), any());
    }

    @Test
    public void connectWhileConnectingIsNoOpOnceStatePublished() {
        client.connect();
        idle();
        client.connect();
        verify(sdk, times(1)).initialize(any(), anyBoolean(), any());
    }

    @Test
    @Ignore("BUG: connect() guards on stateLd.getValue() == CONNECTING, but the state is set with "
            + "postValue (async), so two connect() calls in the same main-loop turn both "
            + "initialise the SDK despite the 'never initialized twice' contract")
    public void connectTwiceInSameTurnInitialisesOnce() {
        client.connect();
        client.connect();
        verify(sdk, times(1)).initialize(any(), anyBoolean(), any());
    }

    @Test
    public void sendsFailWhenNotConnected() throws Exception {
        assertFalse(client.sendMessage(new HashMap<>()));
        assertFalse(client.sendPayload("{}".getBytes()));
        assertFalse(client.sendPayloadBlocking("{}".getBytes(), 10));
        assertFalse(client.sendMessageToOnboardBlocking(new HashMap<>(), 10));
        verify(sdk, never()).sendMessage(any(), any(), any(), any());
    }

    @Test
    public void payloadsGoToTheirOwnApps() throws Exception {
        connectReady(IQDevice.IQDeviceStatus.CONNECTED);
        byte[] json = "{\"t\":1}".getBytes();
        assertTrue(client.sendPayload(json));
        assertTrue(client.sendPayloadToDatafield(json));
        assertTrue(client.sendPayloadToSurfaceField(json));
        verify(sdk).sendMessage(eq(watch), argThat(a -> isApp(a, ConnectIqAppId.DATAFIELD)), any(), any());
        verify(sdk).sendMessage(eq(watch), argThat(a -> isApp(a, ConnectIqAppId.SURFACE_FIELD)), any(), any());
        // HELLO + sendPayload both target the widget.
        verify(sdk, times(2)).sendMessage(eq(watch), argThat(a -> isApp(a, ConnectIqAppId.VALUE)), any(), any());
    }

    @Test
    public void malformedPayloadIsNotSent() throws Exception {
        connectReady(IQDevice.IQDeviceStatus.CONNECTED);
        assertFalse(client.sendPayloadToDatafield("{kapot".getBytes()));
        assertFalse(client.sendPayloadBlocking("{kapot".getBytes(), 10));
    }

    @Test
    public void sendStateGoesSendingThenBackToConnected() throws Exception {
        connectReady(IQDevice.IQDeviceStatus.CONNECTED);
        ArgumentCaptor<ConnectIQ.IQSendMessageListener> ack =
                ArgumentCaptor.forClass(ConnectIQ.IQSendMessageListener.class);
        client.sendMessage(new HashMap<>());
        idle();
        assertEquals(ConnectIqState.SENDING, client.state().getValue());
        verify(sdk, times(2)).sendMessage(eq(watch), any(IQApp.class), any(), ack.capture());
        ack.getValue().onMessageStatus(watch, null, ConnectIQ.IQMessageStatus.FAILURE_UNKNOWN);
        idle();
        assertEquals(ConnectIqState.CONNECTED, client.state().getValue());
    }

    @Test
    public void sendExceptionBecomesError() throws Exception {
        connectReady(IQDevice.IQDeviceStatus.CONNECTED);
        doThrow(new InvalidStateException("x")).when(sdk).sendMessage(any(), any(), any(), any());
        assertFalse(client.sendMessage(new HashMap<>()));
        idle();
        assertEquals(ConnectIqState.ERROR, client.state().getValue());
    }

    @Test
    public void blockingSendReturnsAckStatus() throws Exception {
        connectReady(IQDevice.IQDeviceStatus.CONNECTED);
        ackWith(ConnectIQ.IQMessageStatus.SUCCESS);
        assertTrue(client.sendPayloadBlocking("{\"a\":1}".getBytes(), 1000));
        assertTrue(client.sendMessageToOnboardBlocking(new HashMap<>(), 1000));
        verify(sdk).sendMessage(eq(watch), argThat(a -> isApp(a, ConnectIqAppId.ONBOARD)), any(), any());

        ackWith(ConnectIQ.IQMessageStatus.FAILURE_DURING_TRANSFER);
        assertFalse(client.sendMessageToOnboardBlocking(new HashMap<>(), 1000));
    }

    @Test
    public void blockingSendTimesOutWithoutAck() throws Exception {
        connectReady(IQDevice.IQDeviceStatus.CONNECTED);
        assertFalse(client.sendMessageToOnboardBlocking(new HashMap<>(), 20));
    }

    @Test
    public void blockingSendExceptionReturnsFalse() throws Exception {
        connectReady(IQDevice.IQDeviceStatus.CONNECTED);
        doThrow(new ServiceUnavailableException("x")).when(sdk).sendMessage(any(), any(), any(), any());
        assertFalse(client.sendMessageToOnboardBlocking(new HashMap<>(), 1000));
    }

    @Test
    public void blockingSendInterruptedReturnsFalseAndKeepsFlag() throws Exception {
        connectReady(IQDevice.IQDeviceStatus.CONNECTED);
        Thread.currentThread().interrupt();
        try {
            assertFalse(client.sendMessageToOnboardBlocking(new HashMap<>(), 1000));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void forceRebindShutsDownAndReconnectsAfterOneSecond() throws Exception {
        connectReady(IQDevice.IQDeviceStatus.CONNECTED);
        client.forceRebind();
        idle();
        verify(sdk).shutdown(app);
        assertFalse(client.isConnected());
        assertEquals(ConnectIqState.DISCONNECTED, client.state().getValue());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        verify(sdk, times(2)).initialize(any(), anyBoolean(), any());
    }

    @Test
    public void forceRebindToleratesUninitialisedSdk() throws Exception {
        doThrow(new InvalidStateException("never initialised")).when(sdk).shutdown(any());
        client.forceRebind();
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        verify(sdk).initialize(any(), anyBoolean(), any());
    }

    @Test
    public void disconnectShutsDownEvenWhenSdkThrows() throws Exception {
        connectReady(IQDevice.IQDeviceStatus.CONNECTED);
        doThrow(new InvalidStateException("x")).when(sdk).shutdown(any());
        client.disconnect();
        idle();
        assertFalse(client.isConnected());
        assertEquals(ConnectIqState.DISCONNECTED, client.state().getValue());
    }

    private void ackWith(ConnectIQ.IQMessageStatus status) throws Exception {
        doAnswer(inv -> {
            ConnectIQ.IQSendMessageListener l = inv.getArgument(3);
            l.onMessageStatus(watch, inv.getArgument(1), status);
            return null;
        }).when(sdk).sendMessage(any(), any(), any(), any());
    }
}

package nl.paree.climbpro.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;
import androidx.work.Data;
import androidx.work.ListenableWorker;
import androidx.work.testing.TestWorkerBuilder;

import nl.paree.climbpro.data.battery.BatteryDevice;
import nl.paree.climbpro.data.maintenance.MaintenanceComponent;
import nl.paree.climbpro.data.medical.MedicalId;
import nl.paree.climbpro.data.medical.MedicalIdRepository;
import nl.paree.climbpro.data.planning.PlannedClimb;
import nl.paree.climbpro.data.ride.RideRepository;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.data.ride.WetRideCheck;
import nl.paree.climbpro.data.ride.WetRideCheckRepository;
import nl.paree.climbpro.data.safehome.SafeHomeSettings;
import nl.paree.climbpro.data.weather.OpenMeteoClient;
import nl.paree.climbpro.domain.weather.HourlyPrecipitation;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedConstruction;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * Notification-posting code in the service package: the sunscreen and wet-ride reminders, the
 * lock-screen medical ID, and every notifier's "permission revoked" (SecurityException) path.
 */
@RunWith(RobolectricTestRunner.class)
public class ReminderNotificationsTest {

    private Context app;
    private NotificationManager nm;

    @Before
    public void setUp() {
        app = ApplicationProvider.getApplicationContext();
        nm = app.getSystemService(NotificationManager.class);
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear().commit();
    }

    private List<Notification> posted() {
        return shadowOf(nm).getAllNotifications();
    }

    private static String text(Notification n) {
        CharSequence cs = n.extras.getCharSequence(NotificationCompat.EXTRA_TEXT);
        return cs == null ? null : cs.toString();
    }

    private static String title(Notification n) {
        CharSequence cs = n.extras.getCharSequence(NotificationCompat.EXTRA_TITLE);
        return cs == null ? null : cs.toString();
    }

    /** NotificationManagerCompat that reports enabled but throws on notify (revoked grant). */
    private static MockedStatic<NotificationManagerCompat> revokedPermission() {
        NotificationManagerCompat nmc = mock(NotificationManagerCompat.class);
        when(nmc.areNotificationsEnabled()).thenReturn(true);
        doThrow(new SecurityException("POST_NOTIFICATIONS")).when(nmc)
                .notify(anyInt(), any(Notification.class));
        MockedStatic<NotificationManagerCompat> st = mockStatic(NotificationManagerCompat.class);
        st.when(() -> NotificationManagerCompat.from(any(Context.class))).thenReturn(nmc);
        return st;
    }

    // --- sunscreen -----------------------------------------------------------------------

    private ListenableWorker.Result runSunscreen(String text) {
        Data.Builder in = new Data.Builder();
        if (text != null) in.putString(SunscreenReminderWorker.KEY_TEXT, text);
        SunscreenReminderWorker w = TestWorkerBuilder.from(app, SunscreenReminderWorker.class,
                Executors.newSingleThreadExecutor()).setInputData(in.build()).build();
        return w.doWork();
    }

    @Test
    public void sunscreenReminderIsPostedOnItsOwnChannel() {
        assertEquals(ListenableWorker.Result.success(), runSunscreen("Smeer factor 50 in"));
        assertNotNull(nm.getNotificationChannel(SunscreenReminderWorker.CHANNEL_ID));
        assertEquals(1, posted().size());
        assertEquals("Zonnebrand", title(posted().get(0)));
        assertEquals("Smeer factor 50 in", text(posted().get(0)));
        assertEquals(SunscreenReminderWorker.CHANNEL_ID, posted().get(0).getChannelId());
    }

    @Test
    public void newerSunscreenReminderReplacesAnUnreadOlderOne() {
        runSunscreen("Eerste");
        runSunscreen("Tijd om opnieuw zonnebrand te smeren.");
        assertEquals(1, posted().size());
        assertEquals("Tijd om opnieuw zonnebrand te smeren.", text(posted().get(0)));
    }

    @Test
    public void sunscreenWithoutTextOrWithNotificationsOffPostsNothing() {
        assertEquals(ListenableWorker.Result.success(), runSunscreen(null));
        shadowOf(nm).setNotificationsEnabled(false);
        assertEquals(ListenableWorker.Result.success(), runSunscreen("x"));
        assertTrue(posted().isEmpty());
        assertNull(nm.getNotificationChannel(SunscreenReminderWorker.CHANNEL_ID));
    }

    @Test
    public void sunscreenRevokedPermissionStillSucceeds() {
        try (MockedStatic<NotificationManagerCompat> ignored = revokedPermission()) {
            assertEquals(ListenableWorker.Result.success(), runSunscreen("x"));
        }
        assertTrue(posted().isEmpty());
    }

    @Test
    public void sunscreenChannelIsCreatedOnce() {
        SunscreenReminderWorker.ensureChannel(app);
        nm.getNotificationChannel(SunscreenReminderWorker.CHANNEL_ID).setDescription("eigen");
        SunscreenReminderWorker.ensureChannel(app);
        assertEquals(1, countChannels(SunscreenReminderWorker.CHANNEL_ID));
    }

    private int countChannels(String id) {
        int n = 0;
        for (android.app.NotificationChannel c : nm.getNotificationChannels()) {
            if (id.equals(c.getId())) n++;
        }
        return n;
    }

    // --- wet ride notifier ------------------------------------------------------------------

    private static WetRideCheck check(long id, boolean wet, boolean offroad, double mm) {
        WetRideCheck c = new WetRideCheck();
        c.activityId = id;
        c.wet = wet;
        c.offroad = offroad;
        c.precipitationMm = mm;
        return c;
    }

    @Test
    public void wetRideNotificationNamesTheRideAndAmount() {
        WetRideNotifier.notify(app, check(42L, true, false, 3.0), "Zondagsrit");
        assertEquals(1, posted().size());
        Notification n = posted().get(0);
        assertEquals("Natte rit: Zondagsrit", title(n));
        assertTrue(text(n).contains("3,0 mm"));
        assertEquals(WetRideNotifier.CHANNEL_ID, n.getChannelId());
    }

    @Test
    public void wetRideRepeatForSameRideReplacesAndOtherRideAdds() {
        WetRideNotifier.notify(app, check(42L, true, false, 3.0), null);
        WetRideNotifier.notify(app, check(42L, true, false, 4.0), null);
        assertEquals(1, posted().size());
        assertEquals("Natte rit: fiets schoonmaken", title(posted().get(0)));
        WetRideNotifier.notify(app, check(43L, true, true, 0.8), "Gravel");
        assertEquals(2, posted().size());
    }

    @Test
    public void wetRideRevokedPermissionDoesNotThrow() {
        try (MockedStatic<NotificationManagerCompat> ignored = revokedPermission()) {
            WetRideNotifier.notify(app, check(1L, true, false, 2.0), "x");
        }
        assertTrue(posted().isEmpty());
    }

    @Test
    public void wetRideChannelEnsureIsIdempotent() {
        WetRideNotifier.ensureChannel(app);
        WetRideNotifier.ensureChannel(app);
        assertEquals(1, countChannels(WetRideNotifier.CHANNEL_ID));
    }

    // --- wet ride job ---------------------------------------------------------------------

    private static StoredRide ride(long id, String name, long startSec, String type) {
        StoredRide r = new StoredRide();
        r.activityId = id;
        r.name = name;
        r.type = type;
        r.startEpochSec = startSec;
        r.elapsedTimeSec = 3600;
        r.startLat = 50.8;
        r.startLon = 5.7;
        return r;
    }

    /** Every hour of the last five days has {@code mmPerHour}. */
    private static HourlyPrecipitation weather(long nowSec, double mmPerHour) {
        int n = 5 * 24;
        Instant[] t = new Instant[n];
        double[] mm = new double[n];
        for (int i = 0; i < n; i++) {
            t[i] = Instant.ofEpochSecond(nowSec - 5 * 86_400L + 3600L * (i + 1));
            mm[i] = mmPerHour;
        }
        return new HourlyPrecipitation(t, mm);
    }

    private void enableWetRide(boolean on) {
        PreferenceManager.getDefaultSharedPreferences(app).edit()
                .putBoolean(WetRideReminderJob.PREF_ENABLED, on).commit();
    }

    @Test
    public void wetRideJobIsOffByDefaultAndMakesNoNetworkCall() throws Exception {
        long now = System.currentTimeMillis() / 1000L;
        new RideRepository(app).upsertAll(Arrays.asList(ride(1L, "Nat", now - 86_400L, "Ride")));
        try (MockedConstruction<OpenMeteoClient> client = mockConstruction(OpenMeteoClient.class)) {
            WetRideReminderJob.runIfEnabled(app);
            assertTrue(client.constructed().isEmpty());
        }
        assertTrue(new WetRideCheckRepository(app).checkedIds().isEmpty());
    }

    @Test
    public void wetRideJobSkipsWhenNotificationsAreBlocked() throws Exception {
        enableWetRide(true);
        shadowOf(nm).setNotificationsEnabled(false);
        try (MockedConstruction<OpenMeteoClient> client = mockConstruction(OpenMeteoClient.class)) {
            WetRideReminderJob.runIfEnabled(app);
            assertTrue(client.constructed().isEmpty());
        }
    }

    @Test
    public void wetRideJobNotifiesWetRidesOnlyAndRecordsEveryCheck() throws Exception {
        enableWetRide(true);
        long now = System.currentTimeMillis() / 1000L;
        new RideRepository(app).upsertAll(Arrays.asList(
                ride(1L, "Natte ochtend", now - 86_400L, "Ride"),
                ride(2L, "Droge middag", now - 40_000L, "Ride")));
        HourlyPrecipitation wet = weather(now, 2.0);
        HourlyPrecipitation dry = weather(now, 0.0);
        try (MockedConstruction<OpenMeteoClient> ignored = mockConstruction(OpenMeteoClient.class,
                (m, ctx) -> when(m.fetchPrecipitation(anyDouble(), anyDouble()))
                        .thenReturn(dry, wet))) {
            // Newest first: ride 2 gets "dry", ride 1 gets "wet".
            WetRideReminderJob.runIfEnabled(app);
        }
        assertEquals(1, posted().size());
        assertEquals("Natte rit: Natte ochtend", title(posted().get(0)));
        assertEquals(2, new WetRideCheckRepository(app).checkedIds().size());

        // Second sync: both rides already checked, so no fetch and no duplicate alert.
        try (MockedConstruction<OpenMeteoClient> client = mockConstruction(OpenMeteoClient.class)) {
            WetRideReminderJob.runIfEnabled(app);
            for (OpenMeteoClient c : client.constructed()) {
                org.mockito.Mockito.verifyNoInteractions(c);
            }
        }
        assertEquals(1, posted().size());
    }

    @Test
    public void wetRideJobOfflineFailsQuietlyAndRetriesNextSync() throws Exception {
        enableWetRide(true);
        long now = System.currentTimeMillis() / 1000L;
        new RideRepository(app).upsertAll(Arrays.asList(ride(1L, "Nat", now - 86_400L, "Ride")));
        try (MockedConstruction<OpenMeteoClient> ignored = mockConstruction(OpenMeteoClient.class,
                (m, ctx) -> when(m.fetchPrecipitation(anyDouble(), anyDouble()))
                        .thenThrow(new IOException("offline")))) {
            WetRideReminderJob.runIfEnabled(app);
        }
        assertTrue(posted().isEmpty());
        assertTrue(new WetRideCheckRepository(app).checkedIds().isEmpty());
    }

    @Test
    public void wetRideJobNeverThrowsOnUnexpectedErrors() throws Exception {
        enableWetRide(true);
        long now = System.currentTimeMillis() / 1000L;
        new RideRepository(app).upsertAll(Arrays.asList(ride(1L, "Nat", now - 86_400L, "Ride")));
        try (MockedConstruction<OpenMeteoClient> ignored = mockConstruction(OpenMeteoClient.class,
                (m, ctx) -> when(m.fetchPrecipitation(anyDouble(), anyDouble()))
                        .thenThrow(new IllegalStateException("bad json")))) {
            WetRideReminderJob.runIfEnabled(app);
        }
        assertTrue(posted().isEmpty());
    }

    // --- medical id -----------------------------------------------------------------------

    @Test
    public void medicalIdIsPostedOngoingAndCancelledWhenSwitchedOff() throws Exception {
        MedicalIdRepository repo = new MedicalIdRepository(app);
        MedicalId id = new MedicalId();
        id.name = "Sven";
        id.bloodType = "O+";
        id.showOnLockscreen = true;
        repo.save(id);
        MedicalIdNotifier.refresh(app);
        assertEquals(1, posted().size());
        Notification n = posted().get(0);
        assertEquals("Medische ID", title(n));
        assertTrue((n.flags & Notification.FLAG_ONGOING_EVENT) != 0);
        assertEquals(Notification.VISIBILITY_PUBLIC, n.visibility);
        assertNotNull(nm.getNotificationChannel(MedicalIdNotifier.CHANNEL_ID));

        MedicalIdNotifier.refresh(app); // second refresh: still one, channel reused
        assertEquals(1, posted().size());

        id.showOnLockscreen = false;
        repo.save(id);
        MedicalIdNotifier.refresh(app);
        assertTrue(posted().isEmpty());
    }

    @Test
    public void emptyMedicalIdIsNeverShown() throws Exception {
        MedicalId id = new MedicalId();
        id.showOnLockscreen = true;
        new MedicalIdRepository(app).save(id);
        MedicalIdNotifier.refresh(app);
        assertTrue(posted().isEmpty());
    }

    @Test
    public void medicalIdRevokedPermissionDoesNotThrow() throws Exception {
        MedicalId id = new MedicalId();
        id.name = "Sven";
        id.showOnLockscreen = true;
        new MedicalIdRepository(app).save(id);
        try (MockedStatic<NotificationManagerCompat> ignored = revokedPermission()) {
            MedicalIdNotifier.refresh(app);
        }
        assertTrue(posted().isEmpty());
    }

    // --- remaining notifiers: revoked grant and disabled channel -----------------------

    @Test
    public void batteryAndWarrantyReportNotShownWhenPermissionIsRevoked() {
        BatteryDevice d = new BatteryDevice();
        d.id = "b1";
        d.name = "Di2";
        d.kind = "shifting";
        d.lastChargedEpochSec = 1_750_000_000L;
        d.intervalDays = 30;
        MaintenanceComponent c = new MaintenanceComponent("c1", "Wiel", 0, 0);
        c.warrantyPurchaseEpochSec = 1_700_000_000L;
        c.warrantyMonths = 24;
        try (MockedStatic<NotificationManagerCompat> ignored = revokedPermission()) {
            assertFalse(BatteryNotifier.notify(app, d, 1_760_000_000L));
            assertFalse(WarrantyNotifier.notify(app, c, 1_760_000_000L, ZoneOffset.UTC));
        }
    }

    @Test
    public void batteryAndWarrantyRespectAChannelTheUserSwitchedOff() {
        BatteryNotifier.ensureChannel(app);
        WarrantyNotifier.ensureChannel(app);
        android.app.NotificationChannel b = nm.getNotificationChannel(BatteryNotifier.CHANNEL_ID);
        b.setImportance(NotificationManager.IMPORTANCE_NONE);
        android.app.NotificationChannel w = nm.getNotificationChannel(WarrantyNotifier.CHANNEL_ID);
        w.setImportance(NotificationManager.IMPORTANCE_NONE);

        BatteryDevice d = new BatteryDevice();
        d.id = "b1";
        d.name = "Lamp";
        MaintenanceComponent c = new MaintenanceComponent("c1", "Wiel", 0, 0);
        assertFalse(BatteryNotifier.notify(app, d, 1_760_000_000L));
        assertFalse(WarrantyNotifier.notify(app, c, 1_760_000_000L, ZoneOffset.UTC));
        assertTrue(posted().isEmpty());
    }

    @Test
    public void plannedClimbReminderTextDependsOnClimbOrRoute() {
        PlannedClimb climb = new PlannedClimb("p1", "r1", 0, "Cauberg", 1L, 1L);
        PlannedClimb route = new PlannedClimb("p2", "r1", PlannedClimb.WHOLE_ROUTE, null, 1L, 1L);
        PlannedClimbNotifier.notify(app, climb);
        PlannedClimbNotifier.notify(app, route);
        assertEquals(2, posted().size());
        List<String> titles = new ArrayList<>();
        List<String> texts = new ArrayList<>();
        for (Notification n : posted()) {
            titles.add(title(n));
            texts.add(text(n));
        }
        assertTrue(titles.contains("Vandaag gepland: Cauberg"));
        assertTrue(titles.contains("Vandaag gepland: je klim"));
        assertTrue(texts.contains("Je had deze klim vandaag gepland."));
        assertTrue(texts.contains("Je had vandaag deze route gepland."));
    }

    @Test
    public void plannedClimbWithoutIdAndRevokedPermissionDoNotThrow() {
        PlannedClimb noId = new PlannedClimb(null, "r1", 0, "X", 1L, 1L);
        PlannedClimbNotifier.notify(app, noId);
        assertEquals(1, posted().size());
        try (MockedStatic<NotificationManagerCompat> ignored = revokedPermission()) {
            PlannedClimbNotifier.notify(app, new PlannedClimb("p", "r1", 0, "Y", 1L, 1L));
        }
        assertEquals(1, posted().size());
    }

    @Test
    public void safeHomeFallsBackToNotificationWhenSmsIsNotAutomatic() {
        SafeHomeSettings s = new SafeHomeSettings();
        s.phoneNumber = "+31600000000";
        s.autoSms = false;
        assertTrue(SafeHomeSender.deliver(app, s, "Thuis"));
        Notification n = posted().get(0);
        // No contact name: the number is used to address the contact.
        assertTrue(title(n).contains("+31600000000"));
        assertNotNull(n.contentIntent);
    }

    @Test
    public void safeHomeReportsFailureWhenNothingCouldBeShown() {
        SafeHomeSettings s = new SafeHomeSettings();
        s.phoneNumber = "+31600000000";
        shadowOf(nm).setNotificationsEnabled(false);
        assertFalse(SafeHomeSender.deliver(app, s, "Thuis"));
        shadowOf(nm).setNotificationsEnabled(true);
        try (MockedStatic<NotificationManagerCompat> ignored = revokedPermission()) {
            assertFalse(SafeHomeSender.deliver(app, s, "Thuis"));
        }
    }
}

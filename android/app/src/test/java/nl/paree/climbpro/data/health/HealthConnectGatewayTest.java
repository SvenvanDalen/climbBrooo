package nl.paree.climbpro.data.health;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;

import androidx.health.connect.client.HealthConnectClient;
import androidx.health.connect.client.PermissionController;
import androidx.health.connect.client.permission.HealthPermission;
import androidx.health.connect.client.records.DistanceRecord;
import androidx.health.connect.client.records.ElevationGainedRecord;
import androidx.health.connect.client.records.ExerciseSessionRecord;
import androidx.health.connect.client.records.Record;
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord;
import androidx.health.connect.client.records.WeightRecord;
import androidx.health.connect.client.response.ReadRecordsResponse;
import androidx.health.connect.client.units.Mass;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;

import nl.paree.climbpro.data.strava.StravaActivityDto;
import nl.paree.climbpro.domain.health.RideHealthEntry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Health Connect link (issue #255) against a fake client. */
@RunWith(RobolectricTestRunner.class)
public class HealthConnectGatewayTest {

    private final Context app = ApplicationProvider.getApplicationContext();
    private HealthConnectClient client;
    private PermissionController permissions;

    @Before
    public void setUp() {
        PreferenceManager.getDefaultSharedPreferences(app).edit().clear().commit();
        client = mock(HealthConnectClient.class);
        permissions = mock(PermissionController.class);
        when(client.getPermissionController()).thenReturn(permissions);
    }

    // Mockito drops the Kotlin Continuation parameter of suspend functions from matching,
    // so it is passed as a plain null next to the real-argument matchers.
    private HealthConnectGateway gateway(String... granted) {
        Set<String> set = new HashSet<>(Arrays.asList(granted));
        when(permissions.getGrantedPermissions(null)).thenReturn(set);
        return new HealthConnectGateway(app, client);
    }

    @Test
    public void permissionsCoverEverythingWeUse() {
        assertTrue(HealthConnectGateway.PERMISSIONS.contains(HealthPermission.WRITE_EXERCISE));
        assertTrue(HealthConnectGateway.PERMISSIONS.contains(HealthPermission.READ_WEIGHT));
        assertEquals(5, HealthConnectGateway.PERMISSIONS.size());
    }

    @Test
    public void availabilityWithoutProviderIsNotAvailable() {
        assertTrue(new HealthConnectGateway(app).availability()
                != HealthConnectGateway.Availability.AVAILABLE);
    }

    @Test
    public void canWriteRidesFollowsExercisePermission() throws Exception {
        assertTrue(gateway(HealthPermission.WRITE_EXERCISE).canWriteRides());
        assertFalse(gateway(HealthPermission.READ_WEIGHT).canWriteRides());
    }

    @Test
    public void exportWithoutWritePermissionFails() throws Exception {
        try {
            gateway().exportRides();
            fail();
        } catch (IOException e) {
            assertEquals("Geen toestemming om trainingen te schrijven", e.getMessage());
        }
    }

    @Test
    public void exportWithoutStravaLinkFails() throws Exception {
        try {
            gateway(HealthPermission.WRITE_EXERCISE).exportRides();
            fail();
        } catch (IOException e) {
            assertEquals("Koppel eerst Strava", e.getMessage());
        }
    }

    @Test
    public void exportIfEnabledDoesNothingWhenOff() {
        HealthConnectGateway g = gateway(HealthPermission.WRITE_EXERCISE);
        g.exportIfEnabled();
        verify(client, never()).getPermissionController();
    }

    @Test
    public void exportIfEnabledSwallowsUnavailableProvider() {
        PreferenceManager.getDefaultSharedPreferences(app).edit()
                .putBoolean(HealthConnectGateway.PREF_AUTO, true).commit();
        gateway(HealthPermission.WRITE_EXERCISE).exportIfEnabled(); // provider missing → no-op
        verify(client, never()).getPermissionController();
    }

    @Test
    public void latestWeightNeedsReadPermission() throws Exception {
        assertNull(gateway(HealthPermission.WRITE_EXERCISE).latestWeightKg());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void latestWeightReturnsNewestRecord() throws Exception {
        WeightRecord w = mock(WeightRecord.class);
        when(w.getWeight()).thenReturn(Mass.kilograms(72.5));
        ReadRecordsResponse<WeightRecord> resp = mock(ReadRecordsResponse.class);
        when(resp.getRecords()).thenReturn(Collections.singletonList(w));
        when(client.readRecords(any(), null)).thenReturn(resp);
        assertEquals(72.5, gateway(HealthPermission.READ_WEIGHT).latestWeightKg(), 1e-9);
    }

    @Test
    @SuppressWarnings("unchecked")
    public void latestWeightNullWhenNoRecords() throws Exception {
        ReadRecordsResponse<WeightRecord> resp = mock(ReadRecordsResponse.class);
        when(resp.getRecords()).thenReturn(Collections.emptyList());
        when(client.readRecords(any(), null)).thenReturn(resp);
        assertNull(gateway(HealthPermission.READ_WEIGHT).latestWeightKg());
    }

    @Test
    public void toRecordsWritesOnlyGrantedTypes() {
        RideHealthEntry e = RideHealthEntry.from(ride("Ride", 250.0));
        Set<String> all = new HashSet<>(HealthConnectGateway.PERMISSIONS);

        List<Record> full = HealthConnectGateway.toRecords(e, all, 1000L);
        assertEquals(4, full.size());
        assertTrue(full.get(0) instanceof ExerciseSessionRecord);
        assertTrue(full.get(1) instanceof DistanceRecord);
        assertTrue(full.get(2) instanceof ElevationGainedRecord);
        assertTrue(full.get(3) instanceof TotalCaloriesBurnedRecord);
        assertEquals("climbpro-strava-7-session", full.get(0).getMetadata().getClientRecordId());
        assertEquals(1000L, full.get(0).getMetadata().getClientRecordVersion());
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
                ((ExerciseSessionRecord) full.get(0)).getExerciseType());

        List<Record> sessionOnly = HealthConnectGateway.toRecords(e,
                Collections.singleton(HealthPermission.WRITE_EXERCISE), 1000L);
        assertEquals(1, sessionOnly.size());
    }

    @Test
    public void toRecordsSkipsEmptyMeasuresAndMarksVirtualRidesStationary() {
        StravaActivityDto a = ride("VirtualRide", null);
        a.distance = 0;
        a.totalElevationGain = 0;
        RideHealthEntry e = RideHealthEntry.from(a);
        List<Record> out = HealthConnectGateway.toRecords(e,
                new HashSet<>(HealthConnectGateway.PERMISSIONS), 1L);
        assertEquals(1, out.size());
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY,
                ((ExerciseSessionRecord) out.get(0)).getExerciseType());
    }

    private static StravaActivityDto ride(String type, Double kj) {
        StravaActivityDto a = new StravaActivityDto();
        a.id = 7;
        a.type = type;
        a.name = "Ochtendrit";
        a.startDate = "2026-09-01T06:00:00Z";
        a.startDateLocal = "2026-09-01T08:00:00Z";
        a.elapsedTime = 3600;
        a.distance = 30000;
        a.totalElevationGain = 400;
        a.kilojoules = kj;
        return a;
    }
}

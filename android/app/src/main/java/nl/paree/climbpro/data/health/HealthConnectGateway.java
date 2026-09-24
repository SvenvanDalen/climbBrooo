package nl.paree.climbpro.data.health;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.health.connect.client.HealthConnectClient;
import androidx.health.connect.client.PermissionController;
import androidx.health.connect.client.permission.HealthPermission;
import androidx.health.connect.client.records.DistanceRecord;
import androidx.health.connect.client.records.ElevationGainedRecord;
import androidx.health.connect.client.records.ExerciseSessionRecord;
import androidx.health.connect.client.records.Record;
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord;
import androidx.health.connect.client.records.WeightRecord;
import androidx.health.connect.client.records.metadata.DataOrigin;
import androidx.health.connect.client.records.metadata.Metadata;
import androidx.health.connect.client.request.ReadRecordsRequest;
import androidx.health.connect.client.response.ReadRecordsResponse;
import androidx.health.connect.client.time.TimeRangeFilter;
import androidx.health.connect.client.units.Energy;
import androidx.health.connect.client.units.Length;
import androidx.preference.PreferenceManager;

import nl.paree.climbpro.data.strava.StravaActivitiesRepository;
import nl.paree.climbpro.data.strava.StravaActivityDto;
import nl.paree.climbpro.data.strava.StravaAuthRepository;
import nl.paree.climbpro.data.route.ClimbAttemptRepository;
import nl.paree.climbpro.data.route.RouteRepository;
import nl.paree.climbpro.domain.health.RideHealthEntry;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import kotlin.coroutines.EmptyCoroutineContext;
import kotlin.jvm.JvmClassMappingKt;
import kotlinx.coroutines.BuildersKt;

/**
 * Android Health Connect link (issue #255): writes Strava rides as exercise sessions with
 * distance, elevation gain and calories, and reads the latest body weight for the rider
 * profile. Health Connect's client is Kotlin-suspend only; each call here blocks the calling
 * (background) thread via {@code runBlocking} — never call from the main thread.
 *
 * <p>Pinned to {@code connect-client:1.1.0-alpha08}: the last version that builds against
 * this project's compileSdk 34 / AGP 8.5 (1.1.0 stable needs compileSdk 36, AGP 8.9).
 */
public final class HealthConnectGateway {

    public static final String PREF_AUTO = "health_connect_auto";
    public static final String PREF_CURSOR = "health_connect_cursor_epoch_sec";
    private static final long FIRST_EXPORT_DAYS = 30;
    private static final String TAG = "HealthConnectGateway";

    /** Everything we ask for; the user may grant a subset. */
    public static final Set<String> PERMISSIONS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            HealthPermission.WRITE_EXERCISE,
            HealthPermission.WRITE_DISTANCE,
            HealthPermission.WRITE_ELEVATION_GAINED,
            HealthPermission.WRITE_TOTAL_CALORIES_BURNED,
            HealthPermission.READ_WEIGHT)));

    public enum Availability { AVAILABLE, NEEDS_UPDATE, UNAVAILABLE }

    private final Context ctx;
    private final SharedPreferences prefs;

    public HealthConnectGateway(Context context) {
        this.ctx = context.getApplicationContext();
        this.prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
    }

    public Availability availability() {
        int status = HealthConnectClient.getSdkStatus(ctx);
        if (status == HealthConnectClient.SDK_AVAILABLE) return Availability.AVAILABLE;
        if (status == HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED) {
            return Availability.NEEDS_UPDATE;
        }
        return Availability.UNAVAILABLE;
    }

    public Set<String> grantedPermissions() throws InterruptedException {
        PermissionController pc = client().getPermissionController();
        return BuildersKt.<Set<String>>runBlocking(EmptyCoroutineContext.INSTANCE,
                (scope, cont) -> pc.getGrantedPermissions(cont));
    }

    public boolean canWriteRides() throws InterruptedException {
        return grantedPermissions().contains(HealthPermission.WRITE_EXERCISE);
    }

    /**
     * Writes every Strava ride since the export cursor (the last 30 days on first use) and
     * advances the cursor. Idempotent per ride thanks to the stable client record ids.
     *
     * @return number of rides written
     */
    public int exportRides() throws IOException, InterruptedException {
        Set<String> granted = grantedPermissions();
        if (!granted.contains(HealthPermission.WRITE_EXERCISE)) {
            throw new IOException("Geen toestemming om trainingen te schrijven");
        }
        StravaAuthRepository auth = new StravaAuthRepository(ctx);
        if (!auth.isAuthorised()) throw new IOException("Koppel eerst Strava");

        long nowSec = System.currentTimeMillis() / 1000L;
        long cursor = prefs.getLong(PREF_CURSOR,
                nowSec - FIRST_EXPORT_DAYS * 24 * 3600);
        List<StravaActivityDto> activities = new StravaActivitiesRepository(ctx, auth,
                new RouteRepository(ctx), new ClimbAttemptRepository(ctx))
                .listActivitiesSince(cursor);

        List<Record> records = new ArrayList<>();
        int rides = 0;
        long newest = cursor;
        for (StravaActivityDto a : activities) {
            RideHealthEntry e = RideHealthEntry.from(a);
            if (e == null) continue;
            rides++;
            newest = Math.max(newest, e.start.getEpochSecond());
            records.addAll(toRecords(e, granted));
        }
        if (!records.isEmpty()) {
            List<Record> batch = records;
            BuildersKt.<Object>runBlocking(EmptyCoroutineContext.INSTANCE,
                    (scope, cont) -> client().insertRecords(batch, cont));
        }
        // Next run starts just after the newest ride written (or stays put when none).
        prefs.edit().putLong(PREF_CURSOR, newest).apply();
        Log.i(TAG, "Wrote " + rides + " ride(s) to Health Connect");
        return rides;
    }

    /** Latest weight of the last 90 days in kg, or null. */
    public Double latestWeightKg() throws InterruptedException {
        if (!grantedPermissions().contains(HealthPermission.READ_WEIGHT)) return null;
        Instant now = Instant.now();
        ReadRecordsRequest<WeightRecord> req = new ReadRecordsRequest<>(
                JvmClassMappingKt.getKotlinClass(WeightRecord.class),
                TimeRangeFilter.between(now.minus(90, ChronoUnit.DAYS), now),
                Collections.emptySet(), false, 1, null);
        ReadRecordsResponse<WeightRecord> resp = BuildersKt.<ReadRecordsResponse<WeightRecord>>runBlocking(
                EmptyCoroutineContext.INSTANCE, (scope, cont) -> client().readRecords(req, cont));
        List<WeightRecord> list = resp.getRecords();
        return list.isEmpty() ? null : list.get(0).getWeight().getKilograms();
    }

    /** Runs {@link #exportRides} if the user enabled automatic export and it is possible. */
    public void exportIfEnabled() {
        if (!prefs.getBoolean(PREF_AUTO, false)) return;
        try {
            if (availability() != Availability.AVAILABLE || !canWriteRides()) return;
            exportRides();
        } catch (Exception e) {
            Log.w(TAG, "Automatic Health Connect export failed", e);
        }
    }

    private HealthConnectClient client() {
        return HealthConnectClient.getOrCreate(ctx);
    }

    private static List<Record> toRecords(RideHealthEntry e, Set<String> granted) {
        List<Record> out = new ArrayList<>();
        out.add(new ExerciseSessionRecord(e.start, e.offset, e.end, e.offset,
                e.stationary ? ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY
                        : ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
                e.title, null, metadata(e.clientRecordId + "-session")));
        if (granted.contains(HealthPermission.WRITE_DISTANCE) && e.distanceM > 0) {
            out.add(new DistanceRecord(e.start, e.offset, e.end, e.offset,
                    Length.meters(e.distanceM), metadata(e.clientRecordId + "-distance")));
        }
        if (granted.contains(HealthPermission.WRITE_ELEVATION_GAINED) && e.elevationM > 0) {
            out.add(new ElevationGainedRecord(e.start, e.offset, e.end, e.offset,
                    Length.meters(e.elevationM), metadata(e.clientRecordId + "-elevation")));
        }
        if (granted.contains(HealthPermission.WRITE_TOTAL_CALORIES_BURNED) && e.kcal != null) {
            out.add(new TotalCaloriesBurnedRecord(e.start, e.offset, e.end, e.offset,
                    Energy.kilocalories(e.kcal), metadata(e.clientRecordId + "-calories")));
        }
        return out;
    }

    /** Client id + version 1: re-inserting the same id replaces instead of duplicating. */
    private static Metadata metadata(String clientRecordId) {
        return new Metadata("", new DataOrigin(""), Instant.EPOCH, clientRecordId, 1L, null,
                Metadata.RECORDING_METHOD_ACTIVELY_RECORDED);
    }
}

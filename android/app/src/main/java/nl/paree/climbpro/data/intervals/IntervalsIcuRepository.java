package nl.paree.climbpro.data.intervals;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.VisibleForTesting;
import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

import java.io.IOException;

import nl.paree.climbpro.domain.export.IntervalsIcuExport;
import okhttp3.OkHttpClient;
import okhttp3.logging.HttpLoggingInterceptor;
import retrofit2.Response;
import retrofit2.Retrofit;
import retrofit2.converter.jackson.JacksonConverterFactory;

/**
 * intervals.icu link (issue #78): the personal API key and athlete id, kept in
 * EncryptedSharedPreferences like the Strava token (and, like it, excluded from backups — see
 * {@code res/xml/backup_rules.xml}), plus the two API calls. Network methods block: call them
 * off the main thread.
 */
public final class IntervalsIcuRepository {

    private static final String TAG = "IntervalsIcu";
    /** Encrypted prefs file; keep in step with the backup exclusions. */
    public static final String PREFS = "intervals_icu_auth";
    private static final String KEY_API_KEY = "api_key";
    private static final String KEY_ATHLETE_ID = "athlete_id";

    private final Context context;
    /** Test seams; null means encrypted prefs / the real API. */
    private final SharedPreferences prefsOverride;
    private final IntervalsIcuApiClient apiOverride;

    public IntervalsIcuRepository(Context context) {
        this(context, null, null);
    }

    @VisibleForTesting
    IntervalsIcuRepository(Context context, SharedPreferences prefs, IntervalsIcuApiClient api) {
        this.context = context.getApplicationContext();
        this.prefsOverride = prefs;
        this.apiOverride = api;
    }

    public boolean isConfigured() {
        return apiKey() != null;
    }

    /** Stored key, or null when none. */
    public String apiKey() {
        try {
            String k = prefs().getString(KEY_API_KEY, null);
            return IntervalsIcuExport.isValidApiKey(k) ? k : null;
        } catch (Exception e) {
            Log.e(TAG, "Failed to read credentials", e);
            return null;
        }
    }

    /** Stored athlete id, {@code 0} (own athlete) by default. */
    public String athleteId() {
        try {
            String id = IntervalsIcuExport.normalizeAthleteId(prefs().getString(KEY_ATHLETE_ID, null));
            return id != null ? id : IntervalsIcuExport.OWN_ATHLETE_ID;
        } catch (Exception e) {
            return IntervalsIcuExport.OWN_ATHLETE_ID;
        }
    }

    /** Validated values only; see {@link IntervalsIcuExport#normalizeAthleteId}. */
    public void save(String apiKey, String athleteId) throws IOException {
        try {
            prefs().edit()
                    .putString(KEY_API_KEY, apiKey.trim())
                    .putString(KEY_ATHLETE_ID, athleteId)
                    .apply();
        } catch (Exception e) {
            throw new IOException("Opslaan mislukt", e);
        }
    }

    public void clear() {
        try {
            prefs().edit().clear().apply();
        } catch (Exception e) {
            Log.e(TAG, "Failed to clear credentials", e);
        }
    }

    /** GET the athlete; returns its display name (or id). Blocks. */
    public String testConnection(String apiKey, String athleteId) throws IOException {
        Response<IntervalsIcuAthleteDto> resp = api()
                .getAthlete(IntervalsIcuExport.basicAuthHeader(apiKey), athleteId).execute();
        if (!resp.isSuccessful()) throw new IOException(IntervalsIcuExport.errorMessage(resp.code()));
        IntervalsIcuAthleteDto a = resp.body();
        if (a == null) return athleteId;
        return a.name != null && !a.name.trim().isEmpty() ? a.name : a.id;
    }

    /** POSTs a planned workout with the stored credentials; returns the new event id. Blocks. */
    public Long createEvent(IntervalsIcuEventDto event) throws IOException {
        String key = apiKey();
        if (key == null) throw new IOException("Koppel eerst intervals.icu in Instellingen.");
        Response<IntervalsIcuEventDto> resp = api()
                .createEvent(IntervalsIcuExport.basicAuthHeader(key), athleteId(), event).execute();
        if (!resp.isSuccessful()) throw new IOException(IntervalsIcuExport.errorMessage(resp.code()));
        return resp.body() != null ? resp.body().id : null;
    }

    // -------------------------------------------------------------------------

    private SharedPreferences prefs() throws java.security.GeneralSecurityException, IOException {
        if (prefsOverride != null) return prefsOverride;
        MasterKey masterKey = new MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build();
        return EncryptedSharedPreferences.create(
                context, PREFS, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
    }

    private IntervalsIcuApiClient api() {
        if (apiOverride != null) return apiOverride;
        // BASIC logs only the request line — never the Authorization header.
        HttpLoggingInterceptor logging = new HttpLoggingInterceptor();
        logging.setLevel(HttpLoggingInterceptor.Level.BASIC);
        OkHttpClient client = new OkHttpClient.Builder().addInterceptor(logging).build();
        return new Retrofit.Builder()
                .baseUrl(IntervalsIcuApiClient.BASE_URL)
                .client(client)
                .addConverterFactory(JacksonConverterFactory.create())
                .build()
                .create(IntervalsIcuApiClient.class);
    }
}

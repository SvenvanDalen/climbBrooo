package nl.paree.climbpro.data.events;

import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.util.Log;

import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.domain.events.IcsParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Event calendar storage and feed refresh (issue #241). There is no free central source for
 * tour rides and gran fondos, so the rider subscribes to iCal feeds (organisers, clubs,
 * cycling unions publish these) and can add events by hand. Layout:
 * getFilesDir()/event_calendar.json, written atomically. Feed events without GEO are
 * geocoded from their LOCATION text with the platform Geocoder; results are cached by text.
 * Network and geocoding run on the caller's thread — call off the main thread.
 */
public final class EventCalendarRepository {

    private static final String TAG = "EventCalendarRepo";
    public static final String FILE = "event_calendar.json";
    /** Feeds are small text files; anything bigger is not a calendar. */
    static final long MAX_FEED_BYTES = 2_000_000;

    private static final Object LOCK = new Object();

    private final Context app;
    private final File file;
    private final ObjectMapper mapper = new ObjectMapper();
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build();

    public EventCalendarRepository(Context context) {
        this.app = context.getApplicationContext();
        this.file = new File(app.getFilesDir(), FILE);
    }

    public EventCalendar load() {
        synchronized (LOCK) {
            if (!file.exists()) return new EventCalendar();
            try (FileInputStream in = new FileInputStream(file)) {
                EventCalendar c = mapper.readValue(in, EventCalendar.class);
                if (c == null) return new EventCalendar();
                if (c.feeds == null) c.feeds = new ArrayList<>();
                if (c.manualEvents == null) c.manualEvents = new ArrayList<>();
                if (c.feedEvents == null) c.feedEvents = new ArrayList<>();
                if (c.radiusKm <= 0) c.radiusKm = EventCalendar.DEFAULT_RADIUS_KM;
                return c;
            } catch (IOException e) {
                Log.e(TAG, "Failed to load event calendar", e);
                return new EventCalendar();
            }
        }
    }

    public void save(EventCalendar c) throws IOException {
        synchronized (LOCK) {
            File tmp = new File(file.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                mapper.writeValue(out, c);
            }
            if (!tmp.renameTo(file) && (!file.delete() || !tmp.renameTo(file))) {
                throw new IOException("Could not replace " + file);
            }
        }
    }

    /**
     * Fetches every feed, replaces its events, geocodes events without coordinates and saves.
     * A failing feed keeps its previous events and records the error. Returns the new state.
     */
    public EventCalendar refresh() throws IOException {
        EventCalendar c = load();
        Map<String, double[]> geoCache = new HashMap<>();
        for (CyclingEvent e : c.feedEvents) {
            if (e.location != null && e.lat != null) geoCache.put(e.location, new double[]{e.lat, e.lon});
        }
        List<CyclingEvent> events = new ArrayList<>();
        for (EventCalendar.Feed f : c.feeds) {
            try {
                List<CyclingEvent> fetched = IcsParser.parse(download(f.url), f.url);
                f.lastError = null;
                events.addAll(fetched);
            } catch (IOException e) {
                Log.w(TAG, "Feed failed: " + f.url, e);
                f.lastError = e.getMessage() != null ? e.getMessage() : "niet bereikbaar";
                for (CyclingEvent old : c.feedEvents) {
                    if (f.url.equals(old.feedUrl)) events.add(old);
                }
            }
        }
        for (CyclingEvent e : events) geocode(e, geoCache);
        for (CyclingEvent e : c.manualEvents) geocode(e, geoCache);
        c.feedEvents = events;
        c.lastFetchMs = System.currentTimeMillis();
        save(c);
        return c;
    }

    /** Geocodes the location text of an event without coordinates (best effort). */
    public void geocode(CyclingEvent e, Map<String, double[]> cache) {
        if (e.lat != null || e.location == null) return;
        double[] hit = cache.get(e.location);
        if (hit == null && !cache.containsKey(e.location)) {
            hit = forwardGeocode(e.location);
            cache.put(e.location, hit);
        }
        if (hit != null) {
            e.lat = hit[0];
            e.lon = hit[1];
        }
    }

    private double[] forwardGeocode(String text) {
        if (!Geocoder.isPresent()) return null;
        try {
            @SuppressWarnings("deprecation") // sync overload; the caller is off the main thread
            List<Address> r = new Geocoder(app, Locale.getDefault()).getFromLocationName(text, 1);
            if (r == null || r.isEmpty()) return null;
            return new double[]{r.get(0).getLatitude(), r.get(0).getLongitude()};
        } catch (Exception e) {
            // IOException (no network/backend) or a geocoder implementation quirk.
            Log.w(TAG, "Geocoding failed for an event location", e);
            return null;
        }
    }

    private String download(String url) throws IOException {
        Request req = new Request.Builder().url(normalise(url)).get().build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) throw new IOException("HTTP " + resp.code());
            ResponseBody body = resp.body();
            if (body == null) throw new IOException("lege reactie");
            if (body.contentLength() > MAX_FEED_BYTES) throw new IOException("te groot");
            String text = body.string();
            if (!text.contains("BEGIN:VCALENDAR")) throw new IOException("geen iCal-agenda");
            return text;
        } catch (IllegalArgumentException e) {
            throw new IOException("ongeldige link", e);
        }
    }

    /** webcal:// is how calendar sites often link feeds; it is plain HTTPS. */
    public static String normalise(String url) {
        String u = url.trim();
        if (u.regionMatches(true, 0, "webcal://", 0, 9)) return "https://" + u.substring(9);
        return u;
    }
}

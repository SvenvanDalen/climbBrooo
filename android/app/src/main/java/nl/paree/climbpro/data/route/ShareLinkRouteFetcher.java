package nl.paree.climbpro.data.route;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import nl.paree.climbpro.domain.route.KomootTourConverter;
import nl.paree.climbpro.domain.route.RoutePoint;
import nl.paree.climbpro.domain.route.ShareLink;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Downloads the route behind a Komoot/RideWithGPS share link (issue #210) as GPX bytes, so the
 * caller can feed it into the regular GPX import. Keyless public endpoints only; private
 * routes fail with a Dutch {@link FetchException} message. Blocking — call off the main thread.
 */
public final class ShareLinkRouteFetcher {

    /** A downloaded route: display name + GPX document. */
    public static final class Result {
        public final String name;
        public final byte[] gpx;

        Result(String name, byte[] gpx) {
            this.name = name;
            this.gpx = gpx;
        }
    }

    /** Failure with a user-facing (Dutch) message. */
    public static final class FetchException extends IOException {
        public FetchException(String message) {
            super(message);
        }
    }

    /** Refuse absurd downloads; a very long route GPX is a few MB. */
    private static final long MAX_BYTES = 20L * 1024 * 1024;

    private final OkHttpClient http;

    public ShareLinkRouteFetcher() {
        this(new OkHttpClient.Builder().callTimeout(30, TimeUnit.SECONDS).build());
    }

    /** Test seam: inject a client (e.g. one that redirects to a MockWebServer). */
    @androidx.annotation.VisibleForTesting
    ShareLinkRouteFetcher(OkHttpClient http) {
        this.http = http;
    }

    public Result fetch(ShareLink link) throws IOException {
        return link.provider == ShareLink.Provider.KOMOOT ? fetchKomoot(link) : fetchRwgps(link);
    }

    private Result fetchKomoot(ShareLink link) throws IOException {
        String coordinates = get(link, link.komootCoordinatesUrl());
        List<RoutePoint> points = KomootTourConverter.parseCoordinates(coordinates);
        String name = null;
        try {
            name = KomootTourConverter.parseName(get(link, link.komootTourUrl()));
        } catch (IOException ignored) {
            // The name is a nicety; the geometry already came through.
        }
        if (name == null) name = link.defaultName();
        byte[] gpx = KomootTourConverter.toGpx(name, points).getBytes(StandardCharsets.UTF_8);
        return new Result(name, gpx);
    }

    private Result fetchRwgps(ShareLink link) throws IOException {
        String gpx = get(link, link.gpxUrl());
        if (!gpx.contains("<gpx")) {
            // RideWithGPS answers a login page instead of GPX for some private routes.
            throw new FetchException(errorMessage(link.provider, 403));
        }
        String name = KomootTourConverter.gpxName(gpx);
        return new Result(name != null ? name : link.defaultName(),
                gpx.getBytes(StandardCharsets.UTF_8));
    }

    private String get(ShareLink link, String url) throws IOException {
        Request req = new Request.Builder().url(url)
                .header("User-Agent", "ClimbPro-Android")
                .build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful() || resp.body() == null) {
                throw new FetchException(errorMessage(link.provider, resp.code()));
            }
            // Content-Length is -1 for chunked responses, so also cap what is actually read:
            // request() buffers at most MAX_BYTES + 1 bytes and says whether there were more.
            long length = resp.body().contentLength();
            if (length > MAX_BYTES || resp.body().source().request(MAX_BYTES + 1)) {
                throw new FetchException("Route is te groot om te importeren");
            }
            return resp.body().string();
        } catch (FetchException e) {
            throw e;
        } catch (IOException e) {
            throw new FetchException("Geen verbinding met " + label(link.provider)
                    + ": " + e.getMessage());
        }
    }

    /** User-facing message for a failed HTTP status. Package-private for tests. */
    static String errorMessage(ShareLink.Provider provider, int httpCode) {
        String site = label(provider);
        if (httpCode == 401 || httpCode == 403) {
            return provider == ShareLink.Provider.KOMOOT
                    ? "Deze Komoot-tour is privé. Zet hem op openbaar of deel hem via "
                            + "'Delen' in Komoot (een link met share_token) en probeer opnieuw."
                    : "Deze RideWithGPS-route is privé. Zet hem op openbaar of 'iedereen met "
                            + "de link' en probeer opnieuw.";
        }
        if (httpCode == 404) {
            return "Route niet gevonden op " + site + ". Controleer de link.";
        }
        if (httpCode == 429) {
            return site + " weigert tijdelijk te veel verzoeken. Probeer het later opnieuw.";
        }
        return site + " gaf een fout (HTTP " + httpCode + ").";
    }

    private static String label(ShareLink.Provider provider) {
        return provider == ShareLink.Provider.KOMOOT ? "Komoot" : "RideWithGPS";
    }
}

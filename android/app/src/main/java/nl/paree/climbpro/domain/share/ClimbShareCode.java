package nl.paree.climbpro.domain.share;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Compact share code for one climb or a collection of climbs (issue #216), so friends can swap
 * climbs without a GPX file or a server: {@code CPC1:} + base64url of gzipped JSON. Geometry is
 * quantised (1e-5 degrees, about 1 m; elevation in decimetres) and delta-encoded, which gzip
 * then squeezes well. Decoding is bounded, so a pasted code can't exhaust memory, and every
 * failure is an {@link InvalidCodeException} with a Dutch message for the UI. Pure.
 */
public final class ClimbShareCode {

    private ClimbShareCode() {}

    public static final String PREFIX = "CPC1:";
    public static final int MAX_CLIMBS = 25;
    static final int MAX_POINTS_PER_CLIMB = 3000;
    static final int MAX_NAME_CHARS = 80;
    static final int MAX_CODE_CHARS = 250_000;
    static final int MAX_INFLATED_BYTES = 2_000_000;

    private static final double COORD_SCALE = 1e5;
    private static final double ELEVATION_SCALE = 10;
    private static final Pattern CODE = Pattern.compile(
            Pattern.quote(PREFIX) + "([A-Za-z0-9_-]+)");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** What a code holds: an optional collection name and one or more climbs. */
    public static final class Payload {
        /** Null for a single climb that isn't part of a collection. */
        public final String collectionName;
        public final List<SharedClimb> climbs;

        public Payload(String collectionName, List<SharedClimb> climbs) {
            this.collectionName = collectionName;
            this.climbs = Collections.unmodifiableList(new ArrayList<>(climbs));
        }
    }

    public static final class InvalidCodeException extends Exception {
        public InvalidCodeException(String message) { super(message); }
    }

    public static String encode(Payload payload) {
        if (payload == null || payload.climbs.isEmpty()) {
            throw new IllegalArgumentException("Nothing to share");
        }
        if (payload.climbs.size() > MAX_CLIMBS) {
            throw new IllegalArgumentException("At most " + MAX_CLIMBS + " climbs per code");
        }
        ObjectNode root = MAPPER.createObjectNode();
        root.put("v", 1);
        if (payload.collectionName != null) root.put("n", clip(payload.collectionName));
        ArrayNode climbs = root.putArray("c");
        for (SharedClimb c : payload.climbs) {
            if (c.size() < 2 || c.size() > MAX_POINTS_PER_CLIMB) {
                throw new IllegalArgumentException("Climb geometry has " + c.size() + " points");
            }
            ObjectNode o = climbs.addObject();
            o.put("n", clip(c.name != null ? c.name : ""));
            putDeltas(o.putArray("la"), c.lats, COORD_SCALE);
            putDeltas(o.putArray("lo"), c.lons, COORD_SCALE);
            putDeltas(o.putArray("el"), c.elevations, ELEVATION_SCALE);
        }
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(buf)) {
                gz.write(MAPPER.writeValueAsBytes(root));
            }
            return PREFIX + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(buf.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException(e); // in-memory streams don't fail
        }
    }

    /**
     * Finds the first code anywhere in {@code text} (a whole shared message may be pasted) and
     * decodes it.
     */
    public static Payload decode(String text) throws InvalidCodeException {
        if (text == null) throw new InvalidCodeException("Geen klimcode gevonden.");
        if (text.length() > MAX_CODE_CHARS) {
            throw new InvalidCodeException("Deze code is te lang.");
        }
        Matcher m = CODE.matcher(text);
        if (!m.find()) {
            throw new InvalidCodeException("Geen klimcode gevonden. Een klimcode begint met "
                    + PREFIX);
        }
        byte[] json;
        try {
            byte[] zipped = Base64.getUrlDecoder().decode(m.group(1));
            json = inflate(zipped);
        } catch (IllegalArgumentException | IOException e) {
            throw new InvalidCodeException("De code is beschadigd of onvolledig gekopieerd.");
        }
        try {
            JsonNode root = MAPPER.readTree(json);
            if (root == null || root.path("v").asInt() != 1) {
                throw new InvalidCodeException("Deze code komt uit een nieuwere versie van de app.");
            }
            JsonNode climbs = root.path("c");
            if (!climbs.isArray() || climbs.size() == 0) {
                throw new InvalidCodeException("De code bevat geen klimmen.");
            }
            if (climbs.size() > MAX_CLIMBS) {
                throw new InvalidCodeException("De code bevat te veel klimmen.");
            }
            List<SharedClimb> out = new ArrayList<>();
            for (JsonNode c : climbs) {
                double[] la = readDeltas(c.path("la"), COORD_SCALE);
                double[] lo = readDeltas(c.path("lo"), COORD_SCALE);
                double[] el = readDeltas(c.path("el"), ELEVATION_SCALE);
                if (la.length < 2 || la.length != lo.length || la.length != el.length) {
                    throw new InvalidCodeException("De code bevat een ongeldige klim.");
                }
                for (int i = 0; i < la.length; i++) {
                    if (Math.abs(la[i]) > 90 || Math.abs(lo[i]) > 180) {
                        throw new InvalidCodeException("De code bevat ongeldige coördinaten.");
                    }
                }
                out.add(new SharedClimb(clip(c.path("n").asText("")), la, lo, el));
            }
            JsonNode name = root.get("n");
            return new Payload(name != null && name.isTextual() ? clip(name.asText()) : null, out);
        } catch (IOException e) {
            throw new InvalidCodeException("De code is beschadigd of onvolledig gekopieerd.");
        }
    }

    private static void putDeltas(ArrayNode arr, double[] values, double scale) {
        long prev = 0;
        for (double v : values) {
            long q = Math.round(v * scale);
            arr.add(q - prev);
            prev = q;
        }
    }

    private static double[] readDeltas(JsonNode arr, double scale) throws InvalidCodeException {
        if (!arr.isArray() || arr.size() > MAX_POINTS_PER_CLIMB) {
            throw new InvalidCodeException("De code bevat een ongeldige klim.");
        }
        double[] out = new double[arr.size()];
        long acc = 0;
        for (int i = 0; i < out.length; i++) {
            JsonNode d = arr.get(i);
            if (!d.canConvertToLong()) {
                throw new InvalidCodeException("De code bevat een ongeldige klim.");
            }
            acc += d.asLong();
            out[i] = acc / scale;
        }
        return out;
    }

    /** Inflates at most {@link #MAX_INFLATED_BYTES}; more is treated as a zip bomb. */
    private static byte[] inflate(byte[] zipped) throws IOException {
        try (InputStream in = new GZIPInputStream(new ByteArrayInputStream(zipped))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > MAX_INFLATED_BYTES) throw new IOException("Too large");
            }
            return out.toByteArray();
        }
    }

    private static String clip(String s) {
        String t = s.trim();
        return t.length() > MAX_NAME_CHARS ? t.substring(0, MAX_NAME_CHARS) : t;
    }
}

package nl.paree.climbpro.domain.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The rider-profile code of the ride-buddy matcher (issue #242): {@code CPR1:} + URL-safe
 * base64 (no padding) of gzipped JSON — the same envelope as {@link FriendShareCode} (#240)
 * and the climb share code (#216). No server: the code travels through any chat app and
 * matching happens on the receiving phone.
 *
 * <p>Decoding treats the input as hostile: bounded size, bounded inflation, every numeric field
 * range-checked (an out-of-range value is dropped, not rejected, so one odd field doesn't lose
 * the whole profile) and the area re-snapped to the ~5 km grid, so even a hand-crafted code
 * can't make this app store or show a precise location. Pure.
 */
public final class RideBuddyCode {

    public static final String PREFIX = "CPR1:";
    public static final int VERSION = 1;
    public static final int MAX_NAME_CHARS = 40;
    public static final int MAX_ID_CHARS = 64;
    public static final int MAX_CODE_CHARS = 2_000;
    static final int MAX_JSON_BYTES = 4_096;

    static final int MIN_SPEED_DKMH = 50;
    static final int MAX_SPEED_DKMH = 600;
    static final int MAX_DISTANCE_KM = 1_000;
    static final int MAX_RIDE_COUNT = 100_000;

    public static final String MSG_NOT_FOUND = "Geen ClimbPro-profielcode gevonden.";
    public static final String MSG_CORRUPT = "Deze profielcode is beschadigd of onvolledig.";
    public static final String MSG_NEWER =
            "Deze profielcode komt uit een nieuwere versie van ClimbPro. Werk de app bij.";
    public static final String MSG_TOO_LARGE = "Deze profielcode is te groot.";

    private static final Pattern CODE = Pattern.compile("CPR(\\d{1,3}):([A-Za-z0-9_-]*)");
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Locale NL = new Locale("nl");
    private static final String[] DAY_SHORT = {"ma", "di", "wo", "do", "vr", "za", "zo"};

    public static final class InvalidCodeException extends Exception {
        public InvalidCodeException(String message) {
            super(message);
        }
    }

    private RideBuddyCode() {}

    /** Encodes {@code p} exactly as given; restrict it to the opted-in fields first. */
    public static String encode(RideBuddyProfile p) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("v", VERSION);
        root.put("id", FriendShareCode.truncate(p.riderId, MAX_ID_CHARS));
        root.put("n", FriendShareCode.truncate(p.name == null ? "" : p.name.trim(), MAX_NAME_CHARS));
        root.put("t", p.createdEpochSec);
        root.put("c", Math.max(0, p.rideCount));
        if (p.flatSpeedDkmh > 0) root.put("s", p.flatSpeedDkmh);
        if (p.vamMph > 0) root.put("va", p.vamMph);
        if (p.typicalDistanceKm > 0) root.put("d", p.typicalDistanceKm);
        if (p.rideTypes != 0) root.put("rt", p.rideTypes & 7);
        if (p.weekdays != 0) root.put("wd", p.weekdays & 127);
        if (p.dayparts != 0) root.put("dp", p.dayparts & 7);
        if (p.hasArea()) {
            double[] c = RideBuddyProfile.snapToCell(p.areaLat, p.areaLon);
            List<Long> a = new ArrayList<>();
            a.add(Math.round(c[0] * 1000));
            a.add(Math.round(c[1] * 1000));
            root.put("a", a);
        }
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
                gz.write(MAPPER.writeValueAsBytes(root));
            }
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bos.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Encoding a profile code in memory failed", e);
        }
    }

    /** Finds the first profile code in {@code text} (which may be a whole chat message). */
    public static RideBuddyProfile decode(String text) throws InvalidCodeException {
        Matcher m = CODE.matcher(text == null ? "" : text);
        if (!m.find()) throw new InvalidCodeException(MSG_NOT_FOUND);
        int prefixVersion = Integer.parseInt(m.group(1));
        if (prefixVersion > VERSION) throw new InvalidCodeException(MSG_NEWER);
        if (prefixVersion != VERSION) throw new InvalidCodeException(MSG_CORRUPT);
        String token = m.group(2);
        if (token.isEmpty()) throw new InvalidCodeException(MSG_CORRUPT);
        if (token.length() > MAX_CODE_CHARS) throw new InvalidCodeException(MSG_TOO_LARGE);

        byte[] gz;
        try {
            gz = Base64.getUrlDecoder().decode(token);
        } catch (IllegalArgumentException e) {
            throw new InvalidCodeException(MSG_CORRUPT);
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(gunzip(gz));
        } catch (IOException e) {
            throw new InvalidCodeException(MSG_CORRUPT);
        }
        if (root == null || !root.isObject()) throw new InvalidCodeException(MSG_CORRUPT);
        int v = root.path("v").asInt(-1);
        if (v > VERSION) throw new InvalidCodeException(MSG_NEWER);
        if (v != VERSION) throw new InvalidCodeException(MSG_CORRUPT);
        String id = text(root, "id");
        String name = text(root, "n");
        if (id == null || id.length() > MAX_ID_CHARS || name == null) {
            throw new InvalidCodeException(MSG_CORRUPT);
        }

        RideBuddyProfile p = new RideBuddyProfile();
        p.riderId = id;
        p.name = FriendShareCode.truncate(name, MAX_NAME_CHARS);
        p.createdEpochSec = Math.max(0, root.path("t").asLong(0));
        p.rideCount = inRange(root, "c", 0, MAX_RIDE_COUNT);
        p.flatSpeedDkmh = inRange(root, "s", MIN_SPEED_DKMH, MAX_SPEED_DKMH);
        p.vamMph = inRange(root, "va", RideBuddyProfileBuilder.MIN_VAM, RideBuddyProfileBuilder.MAX_VAM);
        p.typicalDistanceKm = inRange(root, "d", 1, MAX_DISTANCE_KM);
        p.rideTypes = inRange(root, "rt", 1, 7);
        p.weekdays = inRange(root, "wd", 1, 127);
        p.dayparts = inRange(root, "dp", 1, 7);
        JsonNode a = root.get("a");
        if (a != null && a.isArray() && a.size() == 2 && a.get(0).isIntegralNumber()
                && a.get(1).isIntegralNumber()) {
            double lat = a.get(0).asLong() / 1000.0;
            double lon = a.get(1).asLong() / 1000.0;
            if (lat >= -90 && lat <= 90 && lon >= -180 && lon <= 180) {
                double[] c = RideBuddyProfile.snapToCell(lat, lon);
                p.areaLat = c[0];
                p.areaLon = c[1];
            }
        }
        return p;
    }

    /**
     * Human-readable lines of everything {@code p} carries — shown before sharing ("dit staat
     * in je code") and when importing. Apart from the random id, nothing else is in the code.
     */
    public static List<String> describe(RideBuddyProfile p) {
        List<String> out = new ArrayList<>();
        out.add("Naam: " + (p.name == null || p.name.isEmpty() ? "—" : p.name));
        out.add("Gebaseerd op " + p.rideCount + " rit(ten)");
        if (p.flatSpeedDkmh > 0) {
            out.add("Tempo op vlak terrein: " + kmh(p.flatSpeedDkmh) + " km/u");
        }
        if (p.vamMph > 0) out.add("Klimsnelheid: " + p.vamMph + " m/u (VAM)");
        if (p.typicalDistanceKm > 0) out.add("Gebruikelijke ritlengte: " + p.typicalDistanceKm + " km");
        if (p.rideTypes != 0) out.add("Rittype: " + typeNames(p.rideTypes));
        if (p.weekdays != 0) out.add("Rijdagen: " + dayNames(p.weekdays));
        if (p.dayparts != 0) out.add("Dagdelen: " + partNames(p.dayparts));
        if (p.hasArea()) {
            out.add(String.format(Locale.US, "Gebied: vak van ~5 km rond %.2f, %.2f",
                    p.areaLat, p.areaLon));
        }
        return out;
    }

    public static String kmh(int dkmh) {
        return String.format(NL, "%.1f", dkmh / 10.0);
    }

    public static String typeNames(int mask) {
        List<String> n = new ArrayList<>();
        if ((mask & RideBuddyProfile.TYPE_ROAD) != 0) n.add("weg");
        if ((mask & RideBuddyProfile.TYPE_GRAVEL) != 0) n.add("gravel");
        if ((mask & RideBuddyProfile.TYPE_MTB) != 0) n.add("MTB");
        return String.join(", ", n);
    }

    public static String dayNames(int mask) {
        List<String> n = new ArrayList<>();
        for (int i = 0; i < 7; i++) if ((mask & (1 << i)) != 0) n.add(DAY_SHORT[i]);
        return String.join(", ", n);
    }

    public static String partNames(int mask) {
        List<String> n = new ArrayList<>();
        if ((mask & RideBuddyProfile.PART_MORNING) != 0) n.add("ochtend");
        if ((mask & RideBuddyProfile.PART_AFTERNOON) != 0) n.add("middag");
        if ((mask & RideBuddyProfile.PART_EVENING) != 0) n.add("avond");
        return String.join(", ", n);
    }

    /** Value of {@code field} when it is an integer within [min, max], else 0 ("not shared"). */
    private static int inRange(JsonNode root, String field, int min, int max) {
        JsonNode n = root.get(field);
        if (n == null || !n.isIntegralNumber()) return 0;
        long v = n.asLong();
        return v >= min && v <= max ? (int) v : 0;
    }

    /** Inflates with a hard output limit so a tiny code can't expand into megabytes. */
    private static byte[] gunzip(byte[] data) throws IOException, InvalidCodeException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            byte[] buf = new byte[1_024];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (out.size() + n > MAX_JSON_BYTES) throw new InvalidCodeException(MSG_TOO_LARGE);
                out.write(buf, 0, n);
            }
        }
        return out.toByteArray();
    }

    private static String text(JsonNode node, String field) {
        JsonNode n = node.get(field);
        if (n == null || !n.isTextual()) return null;
        String s = n.textValue().trim();
        return s.isEmpty() ? null : s;
    }
}

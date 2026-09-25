package nl.paree.climbpro.domain.social;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import nl.paree.climbpro.data.social.FriendFeedEntry;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The friends'-feed share code (issue #240): {@code CPF1:} + URL-safe base64 (no padding) of
 * gzipped JSON. No server is involved; the code travels through any messaging app. Encoding
 * bounds the size (max {@link #MAX_ENTRIES} entries, truncated titles); decoding treats the
 * input as hostile — it comes from other people and from an exported share target — and turns
 * every problem into an {@link InvalidCodeException} with a Dutch message. Pure.
 */
public final class FriendShareCode {

    public static final String PREFIX = "CPF1:";
    public static final int VERSION = 1;
    public static final int MAX_ENTRIES = 20;
    public static final int MAX_TITLE_CHARS = 60;
    public static final int MAX_NAME_CHARS = 40;
    public static final int MAX_ID_CHARS = 64;
    public static final int MAX_CODE_CHARS = 8_000;
    static final int MAX_JSON_BYTES = 32_768;

    public static final String MSG_NOT_FOUND = "Geen ClimbPro-deelcode gevonden.";
    public static final String MSG_CORRUPT = "Deze deelcode is beschadigd of onvolledig.";
    public static final String MSG_NEWER =
            "Deze deelcode komt uit een nieuwere versie van ClimbPro. Werk de app bij.";
    public static final String MSG_TOO_LARGE = "Deze deelcode is te groot.";

    private static final Pattern CODE = Pattern.compile("CPF(\\d{1,3}):([A-Za-z0-9_-]*)");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final class InvalidCodeException extends Exception {
        public InvalidCodeException(String message) {
            super(message);
        }
    }

    public static final class Payload {
        public final String sharerId;
        public final String sharerName;
        public final long createdEpochSec;
        public final List<FriendFeedEntry> entries;

        Payload(String sharerId, String sharerName, long createdEpochSec,
                List<FriendFeedEntry> entries) {
            this.sharerId = sharerId;
            this.sharerName = sharerName;
            this.createdEpochSec = createdEpochSec;
            this.entries = Collections.unmodifiableList(entries);
        }
    }

    private FriendShareCode() {}

    /** Builds the code for {@code own}: newest {@link #MAX_ENTRIES} entries, titles truncated. */
    public static String encode(String sharerId, String sharerName, long nowEpochSec,
                                List<FriendFeedEntry> own) {
        List<FriendFeedEntry> sorted = new ArrayList<>(own);
        Collections.sort(sorted, (a, b) -> Long.compare(b.epochSec, a.epochSec));
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("v", VERSION);
        root.put("id", truncate(sharerId, MAX_ID_CHARS));
        root.put("n", truncate(sharerName == null ? "" : sharerName.trim(), MAX_NAME_CHARS));
        root.put("t", nowEpochSec);
        List<Map<String, Object>> entries = new ArrayList<>();
        for (FriendFeedEntry e : sorted) {
            if (entries.size() >= MAX_ENTRIES) break;
            String k = wireKind(e.kind);
            if (k == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("k", k);
            m.put("t", e.epochSec);
            m.put("s", truncate(e.title == null ? "" : e.title.trim(), MAX_TITLE_CHARS));
            if (e.distanceM > 0) m.put("d", e.distanceM);
            if (e.gainM > 0) m.put("h", e.gainM);
            if (e.movingSec > 0) m.put("m", e.movingSec);
            entries.add(m);
        }
        root.put("e", entries);
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
                gz.write(MAPPER.writeValueAsBytes(root));
            }
            return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bos.toByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("Encoding a share code in memory failed", e);
        }
    }

    /** Finds the first share code in {@code text} (which may contain a whole chat message). */
    public static Payload decode(String text) throws InvalidCodeException {
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
        JsonNode arr = root.get("e");
        if (id == null || id.length() > MAX_ID_CHARS || name == null
                || arr == null || !arr.isArray()) {
            throw new InvalidCodeException(MSG_CORRUPT);
        }
        name = truncate(name, MAX_NAME_CHARS);

        List<FriendFeedEntry> entries = new ArrayList<>();
        for (JsonNode n : arr) {
            if (entries.size() >= MAX_ENTRIES) break;
            if (!n.isObject()) continue;
            String kind = appKind(text(n, "k"));
            long t = n.path("t").asLong(0);
            String title = text(n, "s");
            if (kind == null || t <= 0 || title == null) continue;
            FriendFeedEntry e = new FriendFeedEntry(kind, t, truncate(title, MAX_TITLE_CHARS),
                    Math.max(0, n.path("d").asInt(0)),
                    Math.max(0, n.path("h").asInt(0)),
                    Math.max(0, n.path("m").asInt(0)));
            e.friendId = id;
            e.friendName = name;
            entries.add(e);
        }
        return new Payload(id, name, Math.max(0, root.path("t").asLong(0)), entries);
    }

    /** Inflates with a hard output limit so a tiny code can't expand into megabytes. */
    private static byte[] gunzip(byte[] data) throws IOException, InvalidCodeException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(data))) {
            byte[] buf = new byte[4_096];
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

    private static String wireKind(String kind) {
        if (FriendFeedEntry.KIND_RIDE.equals(kind)) return "r";
        if (FriendFeedEntry.KIND_MILESTONE.equals(kind)) return "m";
        return null;
    }

    private static String appKind(String wire) {
        if ("r".equals(wire)) return FriendFeedEntry.KIND_RIDE;
        if ("m".equals(wire)) return FriendFeedEntry.KIND_MILESTONE;
        return null;
    }

    /** Cuts to {@code max} chars without leaving half of a surrogate pair (emoji) behind. */
    static String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        int end = max;
        if (Character.isHighSurrogate(s.charAt(end - 1))) end--;
        return s.substring(0, end);
    }
}

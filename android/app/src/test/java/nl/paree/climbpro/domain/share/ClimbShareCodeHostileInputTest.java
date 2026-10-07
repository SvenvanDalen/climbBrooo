package nl.paree.climbpro.domain.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPOutputStream;

/** Hand-crafted share codes that a well-behaved encoder never produces. */
public class ClimbShareCodeHostileInputTest {

    private static String code(String json) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(buf)) {
            gz.write(json.getBytes(StandardCharsets.UTF_8));
        }
        return ClimbShareCode.PREFIX
                + Base64.getUrlEncoder().withoutPadding().encodeToString(buf.toByteArray());
    }

    private static String climb(String la, String lo, String el) {
        return "{\"n\":\"K\",\"la\":[" + la + "],\"lo\":[" + lo + "],\"el\":[" + el + "]}";
    }

    private static void assertRejected(String text, String message) {
        try {
            ClimbShareCode.decode(text);
            fail("expected InvalidCodeException");
        } catch (ClimbShareCode.InvalidCodeException e) {
            assertEquals(message, e.getMessage());
        }
    }

    @Test
    public void tooLongText_isRejectedBeforeParsing() {
        StringBuilder sb = new StringBuilder(ClimbShareCode.PREFIX);
        while (sb.length() <= ClimbShareCode.MAX_CODE_CHARS) sb.append('A');
        assertRejected(sb.toString(), "Deze code is te lang.");
    }

    @Test
    public void tooManyClimbs_isRejected() throws Exception {
        List<String> climbs = new ArrayList<>();
        for (int i = 0; i <= ClimbShareCode.MAX_CLIMBS; i++) climbs.add(climb("5000000,1", "600000,1", "0,1"));
        assertRejected(code("{\"v\":1,\"c\":[" + String.join(",", climbs) + "]}"),
                "De code bevat te veel klimmen.");
    }

    @Test
    public void mismatchedOrTooShortArrays_areRejected() throws Exception {
        assertRejected(code("{\"v\":1,\"c\":[" + climb("5000000,1", "600000", "0,1") + "]}"),
                "De code bevat een ongeldige klim.");
        assertRejected(code("{\"v\":1,\"c\":[" + climb("5000000", "600000", "0") + "]}"),
                "De code bevat een ongeldige klim.");
    }

    @Test
    public void nonNumericDeltaOrMissingArray_isRejected() throws Exception {
        assertRejected(code("{\"v\":1,\"c\":[" + climb("5000000,\"x\"", "600000,1", "0,1") + "]}"),
                "De code bevat een ongeldige klim.");
        assertRejected(code("{\"v\":1,\"c\":[{\"n\":\"K\",\"la\":[1,2],\"lo\":[1,2]}]}"),
                "De code bevat een ongeldige klim.");
    }

    @Test
    public void tooManyPointsPerClimb_isRejected() throws Exception {
        String ones = String.join(",", Collections.nCopies(ClimbShareCode.MAX_POINTS_PER_CLIMB + 1, "1"));
        assertRejected(code("{\"v\":1,\"c\":[" + climb(ones, ones, ones) + "]}"),
                "De code bevat een ongeldige klim.");
    }

    @Test
    public void coordinatesOutOfRange_areRejected() throws Exception {
        assertRejected(code("{\"v\":1,\"c\":[" + climb("9100000,0", "600000,0", "0,0") + "]}"),
                "De code bevat ongeldige coördinaten.");
    }

    @Test
    public void gzipOfNonJson_isRejected() throws Exception {
        assertRejected(code("{not json"), "De code is beschadigd of onvolledig gekopieerd.");
    }

    @Test
    public void nonTextCollectionName_isIgnored() throws Exception {
        ClimbShareCode.Payload p = ClimbShareCode.decode(
                code("{\"v\":1,\"n\":42,\"c\":[" + climb("5000000,1", "600000,1", "0,1") + "]}"));
        assertNull(p.collectionName);
        assertEquals(50.0, p.climbs.get(0).lats[0], 1e-9);
    }

    @Test(expected = IllegalArgumentException.class)
    public void encodeNothing_isRefused() {
        ClimbShareCode.encode(new ClimbShareCode.Payload(null,
                Collections.<SharedClimb>emptyList()));
    }

    @Test(expected = IllegalArgumentException.class)
    public void encodeNull_isRefused() {
        ClimbShareCode.encode(null);
    }

    @Test(expected = IllegalArgumentException.class)
    public void encodeSinglePointClimb_isRefused() {
        ClimbShareCode.encode(new ClimbShareCode.Payload(null, Collections.singletonList(
                new SharedClimb("K", new double[]{50}, new double[]{6}, new double[]{0}))));
    }
}

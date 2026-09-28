package nl.paree.climbpro.domain.share;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPOutputStream;

public class ClimbShareCodeTest {

    /** A 3 km straight climb of {@code n} points going north from 45.1, 6.05. */
    static SharedClimb climb(String name, int n) {
        double[] la = new double[n];
        double[] lo = new double[n];
        double[] el = new double[n];
        for (int i = 0; i < n; i++) {
            la[i] = 45.1 + i * 0.000451;
            lo[i] = 6.05 + i * 0.0000013;
            el[i] = 700 + i * 3.04;
        }
        return new SharedClimb(name, la, lo, el);
    }

    private static String gzipCode(String json) throws Exception {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(buf)) {
            gz.write(json.getBytes("UTF-8"));
        }
        return ClimbShareCode.PREFIX
                + Base64.getUrlEncoder().withoutPadding().encodeToString(buf.toByteArray());
    }

    @Test
    public void roundTripKeepsNamesAndGeometryWithinQuantisation() throws Exception {
        SharedClimb c = climb("Alpe d'Huez", 61);
        String code = ClimbShareCode.encode(
                new ClimbShareCode.Payload(null, Collections.singletonList(c)));
        assertTrue(code.startsWith(ClimbShareCode.PREFIX));

        ClimbShareCode.Payload p = ClimbShareCode.decode(code);
        assertNull(p.collectionName);
        assertEquals(1, p.climbs.size());
        SharedClimb back = p.climbs.get(0);
        assertEquals("Alpe d'Huez", back.name);
        assertEquals(61, back.size());
        for (int i = 0; i < 61; i++) {
            assertEquals(c.lats[i], back.lats[i], 0.6e-5);
            assertEquals(c.lons[i], back.lons[i], 0.6e-5);
            assertEquals(c.elevations[i], back.elevations[i], 0.051);
        }
    }

    @Test
    public void collectionRoundTripAndCodeInsideMessage() throws Exception {
        String code = ClimbShareCode.encode(new ClimbShareCode.Payload("Vogezen",
                Arrays.asList(climb("Ballon", 40), climb("Markstein", 30))));
        ClimbShareCode.Payload p = ClimbShareCode.decode(
                "Hoi! Importeer in ClimbPro:\n" + code + "\nGroet");
        assertEquals("Vogezen", p.collectionName);
        assertEquals(2, p.climbs.size());
        assertEquals("Markstein", p.climbs.get(1).name);
    }

    @Test
    public void codeIsCompact() {
        String code = ClimbShareCode.encode(new ClimbShareCode.Payload(null,
                Collections.singletonList(climb("Lang", 400))));
        // 400 points of lat/lon/elevation: well under 10 bytes per point.
        assertTrue("length " + code.length(), code.length() < 4000);
    }

    @Test
    public void longNamesAreClipped() throws Exception {
        StringBuilder name = new StringBuilder();
        for (int i = 0; i < 200; i++) name.append('x');
        ClimbShareCode.Payload p = ClimbShareCode.decode(ClimbShareCode.encode(
                new ClimbShareCode.Payload(null, Collections.singletonList(
                        climb(name.toString(), 5)))));
        assertEquals(ClimbShareCode.MAX_NAME_CHARS, p.climbs.get(0).name.length());
    }

    @Test
    public void rejectsTooManyClimbsWhenEncoding() {
        List<SharedClimb> many = new ArrayList<>();
        for (int i = 0; i <= ClimbShareCode.MAX_CLIMBS; i++) many.add(climb("k" + i, 5));
        try {
            ClimbShareCode.encode(new ClimbShareCode.Payload(null, many));
            fail();
        } catch (IllegalArgumentException expected) {
            // ok
        }
    }

    @Test
    public void badCodesGiveReadableErrors() throws Exception {
        assertInvalid(null);
        assertInvalid("zomaar tekst");
        assertInvalid(ClimbShareCode.PREFIX + "!!!");
        assertInvalid(ClimbShareCode.PREFIX + "AAAA");                    // not gzip
        assertInvalid(gzipCode("{\"v\":2,\"c\":[]}"));                    // newer version
        assertInvalid(gzipCode("{\"v\":1,\"c\":[]}"));                    // no climbs
        assertInvalid(gzipCode("{\"v\":1,\"c\":[{\"n\":\"x\",\"la\":[1],\"lo\":[1],\"el\":[1]}]}"));
        assertInvalid(gzipCode("{\"v\":1,\"c\":[{\"n\":\"x\",\"la\":[1,2],\"lo\":[1],\"el\":[1,2]}]}"));
        assertInvalid(gzipCode("{\"v\":1,\"c\":[{\"n\":\"x\",\"la\":[9500000,1],"
                + "\"lo\":[1,1],\"el\":[1,1]}]}"));                          // latitude 95
        assertInvalid(gzipCode("{\"v\":1,\"c\":[{\"n\":\"x\",\"la\":[\"a\",1],"
                + "\"lo\":[1,1],\"el\":[1,1]}]}"));
    }

    @Test
    public void rejectsZipBomb() throws Exception {
        StringBuilder json = new StringBuilder("{\"v\":1,\"n\":\"");
        for (int i = 0; i < ClimbShareCode.MAX_INFLATED_BYTES + 10; i++) json.append(' ');
        json.append("\",\"c\":[]}");
        assertInvalid(gzipCode(json.toString()));
    }

    private static void assertInvalid(String code) {
        try {
            ClimbShareCode.decode(code);
            fail("expected invalid: " + code);
        } catch (ClimbShareCode.InvalidCodeException e) {
            assertTrue(e.getMessage() != null && !e.getMessage().isEmpty());
        }
    }
}

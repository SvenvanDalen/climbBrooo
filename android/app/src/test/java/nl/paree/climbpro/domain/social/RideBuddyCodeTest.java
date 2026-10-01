package nl.paree.climbpro.domain.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPOutputStream;

public class RideBuddyCodeTest {

    private static RideBuddyProfile full() {
        RideBuddyProfile p = new RideBuddyProfile();
        p.riderId = "rider-1";
        p.name = "Sanne";
        p.createdEpochSec = 1_780_000_000L;
        p.rideCount = 42;
        p.flatSpeedDkmh = 285;
        p.vamMph = 820;
        p.typicalDistanceKm = 65;
        p.rideTypes = RideBuddyProfile.TYPE_ROAD | RideBuddyProfile.TYPE_GRAVEL;
        p.weekdays = 0b1100001; // ma, za, zo
        p.dayparts = RideBuddyProfile.PART_MORNING;
        double[] c = RideBuddyProfile.snapToCell(52.0907, 5.1214);
        p.areaLat = c[0];
        p.areaLon = c[1];
        return p;
    }

    private static String codeOf(String json) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(json.getBytes(StandardCharsets.UTF_8));
        }
        return RideBuddyCode.PREFIX
                + Base64.getUrlEncoder().withoutPadding().encodeToString(bos.toByteArray());
    }

    private static String errorOf(String text) {
        try {
            RideBuddyCode.decode(text);
            fail("expected rejection of: " + text);
            return null;
        } catch (RideBuddyCode.InvalidCodeException e) {
            return e.getMessage();
        }
    }

    @Test
    public void roundTrip_keepsEveryField() throws Exception {
        RideBuddyProfile in = full();
        RideBuddyProfile out = RideBuddyCode.decode(RideBuddyCode.encode(in));
        assertEquals(in.riderId, out.riderId);
        assertEquals(in.name, out.name);
        assertEquals(in.createdEpochSec, out.createdEpochSec);
        assertEquals(in.rideCount, out.rideCount);
        assertEquals(in.flatSpeedDkmh, out.flatSpeedDkmh);
        assertEquals(in.vamMph, out.vamMph);
        assertEquals(in.typicalDistanceKm, out.typicalDistanceKm);
        assertEquals(in.rideTypes, out.rideTypes);
        assertEquals(in.weekdays, out.weekdays);
        assertEquals(in.dayparts, out.dayparts);
        assertEquals(in.areaLat, out.areaLat, 1e-9);
        assertEquals(in.areaLon, out.areaLon, 1e-9);
    }

    @Test
    public void roundTrip_restrictedFieldsStayOut() throws Exception {
        RideBuddyProfile in = full().restrictTo(RideBuddyProfile.FIELD_PACE);
        String code = RideBuddyCode.encode(in);
        RideBuddyProfile out = RideBuddyCode.decode(code);
        assertEquals(285, out.flatSpeedDkmh);
        assertEquals(0, out.vamMph);
        assertEquals(0, out.typicalDistanceKm);
        assertEquals(0, out.rideTypes);
        assertEquals(0, out.weekdays);
        assertEquals(0, out.dayparts);
        assertFalse(out.hasArea());
        assertEquals(RideBuddyProfile.FIELD_PACE, out.availableFields());
    }

    @Test
    public void encode_neverCarriesExactCoordinates() throws Exception {
        RideBuddyProfile in = full();
        in.areaLat = 52.090712;
        in.areaLon = 5.121433;
        RideBuddyProfile out = RideBuddyCode.decode(RideBuddyCode.encode(in));
        double[] cell = RideBuddyProfile.snapToCell(52.090712, 5.121433);
        assertEquals(cell[0], out.areaLat, 1e-9);
        assertEquals(cell[1], out.areaLon, 1e-9);
    }

    @Test
    public void decode_resnapsHandCraftedPreciseArea() throws Exception {
        String code = codeOf("{\"v\":1,\"id\":\"x\",\"n\":\"Evil\",\"a\":[52123,5456]}");
        RideBuddyProfile p = RideBuddyCode.decode(code);
        double[] cell = RideBuddyProfile.snapToCell(52.123, 5.456);
        assertEquals(cell[0], p.areaLat, 1e-9);
        assertEquals(cell[1], p.areaLon, 1e-9);
    }

    @Test
    public void snapToCell_isIdempotentAndCoarse() {
        double[] a = RideBuddyProfile.snapToCell(52.0907, 5.1214);
        double[] b = RideBuddyProfile.snapToCell(a[0], a[1]);
        assertEquals(a[0], b[0], 0);
        assertEquals(a[1], b[1], 0);
        // Everything within one cell maps to the same centre.
        double[] c = RideBuddyProfile.snapToCell(a[0] + 0.01, a[1] - 0.01);
        assertEquals(a[0], c[0], 0);
        assertEquals(a[1], c[1], 0);
        // ~5 km: the centre is never more than half a cell diagonal from the input.
        assertTrue(RideBuddyMatcher.haversineKm(52.0907, 5.1214, a[0], a[1]) < 4);
    }

    @Test
    public void decode_findsCodeInsideChatMessage() throws Exception {
        String msg = "Hoi! Hier mijn profiel:\n\n" + RideBuddyCode.encode(full()) + "\n\nGroet";
        assertEquals("Sanne", RideBuddyCode.decode(msg).name);
    }

    @Test
    public void decode_rejectsMalformedInput() throws Exception {
        assertEquals(RideBuddyCode.MSG_NOT_FOUND, errorOf(null));
        assertEquals(RideBuddyCode.MSG_NOT_FOUND, errorOf(""));
        assertEquals(RideBuddyCode.MSG_NOT_FOUND, errorOf("zomaar tekst"));
        // A friend-feed code is not a profile code.
        assertEquals(RideBuddyCode.MSG_NOT_FOUND, errorOf("CPF1:abc"));
        assertEquals(RideBuddyCode.MSG_CORRUPT, errorOf("CPR1:"));
        assertEquals(RideBuddyCode.MSG_CORRUPT, errorOf("CPR1:abc"));
        assertEquals(RideBuddyCode.MSG_CORRUPT, errorOf("CPR0:" + RideBuddyCode.encode(full()).substring(5)));
        assertEquals(RideBuddyCode.MSG_NEWER, errorOf("CPR2:abc"));
        assertEquals(RideBuddyCode.MSG_CORRUPT, errorOf(codeOf("[1,2,3]")));
        assertEquals(RideBuddyCode.MSG_CORRUPT, errorOf(codeOf("not json")));
        assertEquals(RideBuddyCode.MSG_CORRUPT, errorOf(codeOf("{\"v\":1,\"n\":\"x\"}")));
        assertEquals(RideBuddyCode.MSG_CORRUPT, errorOf(codeOf("{\"v\":1,\"id\":\"x\"}")));
        assertEquals(RideBuddyCode.MSG_CORRUPT, errorOf(codeOf("{\"v\":1,\"id\":5,\"n\":\"x\"}")));
        assertEquals(RideBuddyCode.MSG_NEWER, errorOf(codeOf("{\"v\":9,\"id\":\"x\",\"n\":\"y\"}")));
        assertEquals(RideBuddyCode.MSG_CORRUPT, errorOf(codeOf("{\"id\":\"x\",\"n\":\"y\"}")));
    }

    @Test
    public void decode_truncatedCodeIsCorrupt() {
        String code = RideBuddyCode.encode(full());
        assertEquals(RideBuddyCode.MSG_CORRUPT, errorOf(code.substring(0, code.length() - 6)));
    }

    @Test
    public void decode_rejectsOversizedCodeAndZipBomb() throws Exception {
        StringBuilder sb = new StringBuilder("CPR1:");
        for (int i = 0; i < RideBuddyCode.MAX_CODE_CHARS + 1; i++) sb.append('A');
        assertEquals(RideBuddyCode.MSG_TOO_LARGE, errorOf(sb.toString()));

        StringBuilder big = new StringBuilder("{\"v\":1,\"id\":\"x\",\"n\":\"");
        for (int i = 0; i < 20_000; i++) big.append('a');
        big.append("\"}");
        assertEquals(RideBuddyCode.MSG_TOO_LARGE, errorOf(codeOf(big.toString())));
    }

    @Test
    public void decode_dropsOutOfRangeFieldsInsteadOfFailing() throws Exception {
        RideBuddyProfile p = RideBuddyCode.decode(codeOf("{\"v\":1,\"id\":\"x\",\"n\":\"Y\","
                + "\"c\":-4,\"s\":9999,\"va\":5,\"d\":0,\"rt\":8,\"wd\":\"ma\",\"dp\":3,"
                + "\"a\":[95000,1000]}"));
        assertEquals(0, p.rideCount);
        assertEquals(0, p.flatSpeedDkmh);
        assertEquals(0, p.vamMph);
        assertEquals(0, p.typicalDistanceKm);
        assertEquals(0, p.rideTypes);
        assertEquals(0, p.weekdays);
        assertEquals(3, p.dayparts);
        assertNull(p.areaLat);
        assertFalse(p.hasArea());
    }

    @Test
    public void decode_truncatesLongName() throws Exception {
        StringBuilder n = new StringBuilder();
        for (int i = 0; i < 100; i++) n.append('n');
        RideBuddyProfile p = RideBuddyCode.decode(
                codeOf("{\"v\":1,\"id\":\"x\",\"n\":\"" + n + "\"}"));
        assertEquals(RideBuddyCode.MAX_NAME_CHARS, p.name.length());
    }

    @Test
    public void decode_randomGarbageNeverThrowsUnchecked() {
        Random r = new Random(242);
        for (int i = 0; i < 500; i++) {
            byte[] b = new byte[r.nextInt(200)];
            r.nextBytes(b);
            String text = "CPR1:" + Base64.getUrlEncoder().withoutPadding().encodeToString(b);
            try {
                RideBuddyCode.decode(text);
            } catch (RideBuddyCode.InvalidCodeException expected) {
                // fine
            }
        }
    }

    @Test
    public void describe_listsExactlyTheSharedFields() throws Exception {
        List<String> all = RideBuddyCode.describe(full());
        assertEquals(9, all.size());
        assertTrue(all.contains("Tempo op vlak terrein: 28,5 km/u"));
        assertTrue(all.contains("Rittype: weg, gravel"));
        assertTrue(all.contains("Rijdagen: ma, za, zo"));

        List<String> few = RideBuddyCode.describe(full().restrictTo(RideBuddyProfile.FIELD_DISTANCE));
        assertEquals(3, few.size());
        assertEquals("Gebruikelijke ritlengte: 65 km", few.get(2));
        for (String line : few) assertFalse(line, line.startsWith("Gebied"));
    }
}

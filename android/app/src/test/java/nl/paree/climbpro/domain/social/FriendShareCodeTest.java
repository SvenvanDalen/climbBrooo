package nl.paree.climbpro.domain.social;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import nl.paree.climbpro.data.social.FriendFeedEntry;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.zip.GZIPOutputStream;

public class FriendShareCodeTest {

    private static final String ID = "3f1c2d4e-0000-4000-8000-000000000001";

    private static String codeOf(String json) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bos)) {
            gz.write(json.getBytes(StandardCharsets.UTF_8));
        }
        return FriendShareCode.PREFIX
                + Base64.getUrlEncoder().withoutPadding().encodeToString(bos.toByteArray());
    }

    private static String errorOf(String text) {
        try {
            FriendShareCode.decode(text);
            fail("expected rejection of: " + text);
            return null;
        } catch (FriendShareCode.InvalidCodeException e) {
            return e.getMessage();
        }
    }

    @Test public void roundTripKeepsFieldsNewestFirstAndStampsFriend() throws Exception {
        String code = FriendShareCode.encode(ID, "  Anna ", 5_000, Arrays.asList(
                new FriendFeedEntry(FriendFeedEntry.KIND_RIDE, 1_000, "Ochtendrit", 42_300, 350, 5_520),
                new FriendFeedEntry(FriendFeedEntry.KIND_MILESTONE, 2_000, "Eerste beklimming: Cauberg", 0, 60, 0)));
        assertTrue(code.startsWith("CPF1:"));
        FriendShareCode.Payload p = FriendShareCode.decode(code);
        assertEquals(ID, p.sharerId);
        assertEquals("Anna", p.sharerName);
        assertEquals(5_000, p.createdEpochSec);
        assertEquals(2, p.entries.size());
        FriendFeedEntry m = p.entries.get(0);
        assertEquals(FriendFeedEntry.KIND_MILESTONE, m.kind);
        assertEquals(2_000, m.epochSec);
        assertEquals(60, m.gainM);
        assertEquals(0, m.distanceM);
        FriendFeedEntry r = p.entries.get(1);
        assertEquals(FriendFeedEntry.KIND_RIDE, r.kind);
        assertEquals("Ochtendrit", r.title);
        assertEquals(42_300, r.distanceM);
        assertEquals(350, r.gainM);
        assertEquals(5_520, r.movingSec);
        assertEquals(ID, r.friendId);
        assertEquals("Anna", r.friendName);
    }

    @Test public void encodeCapsEntriesAndTitlesSoTheCodeStaysSmall() throws Exception {
        Random rnd = new Random(7);
        List<FriendFeedEntry> many = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            StringBuilder title = new StringBuilder();
            for (int c = 0; c < 200; c++) title.append((char) ('a' + rnd.nextInt(26)));
            many.add(new FriendFeedEntry(FriendFeedEntry.KIND_RIDE, 1_000 + i, title.toString(),
                    100_000, 1_500, 20_000));
        }
        String code = FriendShareCode.encode(ID, "Anna", 9_999, many);
        assertTrue("code length " + code.length(), code.length() < FriendShareCode.MAX_CODE_CHARS);
        FriendShareCode.Payload p = FriendShareCode.decode(code);
        assertEquals(FriendShareCode.MAX_ENTRIES, p.entries.size());
        assertEquals(1_049, p.entries.get(0).epochSec); // newest kept
        for (FriendFeedEntry e : p.entries) {
            assertTrue(e.title.length() <= FriendShareCode.MAX_TITLE_CHARS);
        }
    }

    @Test public void truncationNeverSplitsSurrogatePair() throws Exception {
        StringBuilder t = new StringBuilder();
        for (int i = 0; i < 59; i++) t.append('a');
        t.append("🚴"); // bicycle emoji: chars 60 and 61
        String code = FriendShareCode.encode(ID, "Anna", 1,
                Collections.singletonList(new FriendFeedEntry(FriendFeedEntry.KIND_RIDE, 10, t.toString(), 0, 0, 0)));
        String title = FriendShareCode.decode(code).entries.get(0).title;
        assertEquals(59, title.length());
        assertFalse(Character.isHighSurrogate(title.charAt(title.length() - 1)));
    }

    @Test public void findsCodeInsideMessageText() throws Exception {
        String code = FriendShareCode.encode(ID, "Anna", 1, Collections.singletonList(
                new FriendFeedEntry(FriendFeedEntry.KIND_RIDE, 10, "Rit", 1_000, 0, 0)));
        FriendShareCode.Payload p = FriendShareCode.decode(
                "Mijn ritten in ClimbPro:\n\n" + code + "\nTot zondag!");
        assertEquals("Anna", p.sharerName);
        assertEquals(1, p.entries.size());
    }

    @Test public void rejectsTextWithoutCode() {
        assertEquals(FriendShareCode.MSG_NOT_FOUND, errorOf(null));
        assertEquals(FriendShareCode.MSG_NOT_FOUND, errorOf(""));
        assertEquals(FriendShareCode.MSG_NOT_FOUND, errorOf("https://www.strava.com/activities/1"));
    }

    @Test public void rejectsTruncatedAndGarbageCodes() throws Exception {
        String code = FriendShareCode.encode(ID, "Anna", 1, Collections.singletonList(
                new FriendFeedEntry(FriendFeedEntry.KIND_RIDE, 10, "Rit", 1_000, 0, 0)));
        assertEquals(FriendShareCode.MSG_CORRUPT, errorOf(code.substring(0, code.length() - 10)));
        assertEquals(FriendShareCode.MSG_CORRUPT, errorOf("CPF1:"));
        assertEquals(FriendShareCode.MSG_CORRUPT, errorOf("CPF1:A"));
        assertEquals(FriendShareCode.MSG_CORRUPT, errorOf("CPF1:SGVsbG8gd29ybGQ")); // not gzip
    }

    @Test public void rejectsNonObjectOrIncompleteJson() throws Exception {
        assertEquals(FriendShareCode.MSG_CORRUPT, errorOf(codeOf("[1,2]")));
        assertEquals(FriendShareCode.MSG_CORRUPT, errorOf(codeOf("{\"v\":1,\"n\":\"A\",\"e\":[]}")));
        assertEquals(FriendShareCode.MSG_CORRUPT, errorOf(codeOf("{\"v\":1,\"id\":\"x\",\"e\":[]}")));
        assertEquals(FriendShareCode.MSG_CORRUPT, errorOf(codeOf("{\"v\":1,\"id\":\"x\",\"n\":\"A\"}")));
        assertEquals(FriendShareCode.MSG_CORRUPT, errorOf(codeOf("not json")));
    }

    @Test public void newerVersionsAskForAnUpdate() throws Exception {
        assertEquals(FriendShareCode.MSG_NEWER, errorOf("CPF2:abcd"));
        assertEquals(FriendShareCode.MSG_NEWER,
                errorOf(codeOf("{\"v\":2,\"id\":\"x\",\"n\":\"A\",\"e\":[]}")));
    }

    @Test public void rejectsOversizedTokenAndDecompressionBomb() throws Exception {
        StringBuilder big = new StringBuilder(FriendShareCode.PREFIX);
        for (int i = 0; i <= FriendShareCode.MAX_CODE_CHARS; i++) big.append('A');
        assertEquals(FriendShareCode.MSG_TOO_LARGE, errorOf(big.toString()));

        StringBuilder pad = new StringBuilder();
        for (int i = 0; i < FriendShareCode.MAX_JSON_BYTES + 1_000; i++) pad.append('a');
        String bomb = codeOf("{\"v\":1,\"id\":\"x\",\"n\":\"A\",\"pad\":\"" + pad + "\",\"e\":[]}");
        assertTrue(bomb.length() < FriendShareCode.MAX_CODE_CHARS); // compresses well
        assertEquals(FriendShareCode.MSG_TOO_LARGE, errorOf(bomb));
    }

    @Test public void skipsMalformedEntriesAndClampsNegatives() throws Exception {
        String json = "{\"v\":1,\"id\":\"x\",\"n\":\"A\",\"t\":1,\"future\":true,\"e\":["
                + "{\"k\":\"r\",\"t\":100,\"s\":\"Goed\",\"d\":-5,\"h\":10},"
                + "{\"k\":\"x\",\"t\":100,\"s\":\"Onbekend soort\"},"
                + "{\"k\":\"r\",\"t\":100},"
                + "{\"k\":\"r\",\"t\":0,\"s\":\"Geen datum\"},"
                + "{\"k\":\"m\",\"t\":90,\"s\":\"  \"},"
                + "\"geen object\","
                + "{\"k\":\"m\",\"t\":80,\"s\":\"Eerste beklimming: Keutenberg\",\"h\":90}]}";
        FriendShareCode.Payload p = FriendShareCode.decode(codeOf(json));
        assertEquals(2, p.entries.size());
        assertEquals("Goed", p.entries.get(0).title);
        assertEquals(0, p.entries.get(0).distanceM);
        assertEquals(10, p.entries.get(0).gainM);
        assertEquals(FriendFeedEntry.KIND_MILESTONE, p.entries.get(1).kind);
    }

    @Test public void decodeCapsEntryCountOfForeignCodes() throws Exception {
        StringBuilder json = new StringBuilder("{\"v\":1,\"id\":\"x\",\"n\":\"A\",\"e\":[");
        for (int i = 0; i < 60; i++) {
            if (i > 0) json.append(',');
            json.append("{\"k\":\"r\",\"t\":").append(1_000 + i).append(",\"s\":\"R\"}");
        }
        json.append("]}");
        assertEquals(FriendShareCode.MAX_ENTRIES,
                FriendShareCode.decode(codeOf(json.toString())).entries.size());
    }
}

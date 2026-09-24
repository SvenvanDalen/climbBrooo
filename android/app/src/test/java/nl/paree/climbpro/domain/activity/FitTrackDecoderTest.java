package nl.paree.climbpro.domain.activity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import nl.paree.climbpro.domain.matching.ClimbAttemptMatcher.TrackSample;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/** Builds small FIT files byte by byte, following the FIT protocol layout. */
public class FitTrackDecoderTest {

    private static final long FIT_TS = 1_100_000_000L; // raw FIT seconds
    private static final long UNIX_TS = FIT_TS + FitTrackDecoder.FIT_EPOCH_OFFSET;

    /** Little-endian writer for FIT content. */
    private static final class Fit {
        final ByteArrayOutputStream body = new ByteArrayOutputStream();

        Fit u8(int v) { body.write(v & 0xFF); return this; }
        Fit u16(int v) { return u8(v).u8(v >> 8); }
        Fit u32(long v) { return u8((int) v).u8((int) (v >> 8)).u8((int) (v >> 16)).u8((int) (v >> 24)); }
        Fit u32be(long v) { return u8((int) (v >> 24)).u8((int) (v >> 16)).u8((int) (v >> 8)).u8((int) v); }

        /** Definition: local type, global message, then (fieldNum, size, baseType) triples. */
        Fit def(int local, boolean bigEndian, int global, int... fields) {
            u8(0x40 | local).u8(0).u8(bigEndian ? 1 : 0);
            if (bigEndian) u8(global >> 8).u8(global); else u16(global);
            u8(fields.length / 3);
            for (int f : fields) u8(f);
            return this;
        }

        byte[] build() {
            byte[] data = body.toByteArray();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(14);                       // header size
            out.write(0x20);                     // protocol version
            out.write(0x54); out.write(0x08);    // profile version
            out.write(data.length); out.write(data.length >> 8);
            out.write(data.length >> 16); out.write(data.length >> 24);
            out.write('.'); out.write('F'); out.write('I'); out.write('T');
            out.write(0); out.write(0);          // header CRC (unchecked)
            out.write(data, 0, data.length);
            out.write(0); out.write(0);          // file CRC (unchecked)
            return out.toByteArray();
        }
    }

    private static long semicircles(double deg) {
        return Math.round(deg * 2147483648.0 / 180.0);
    }

    @Test
    public void decodesRecordsSkippingOtherMessagesAndFields() throws IOException {
        Fit f = new Fit();
        // file_id (global 0) with a timestamp-like field we must not treat as a record.
        f.def(0, false, 0, 4, 4, 0x86).u8(0).u32(FIT_TS - 100);
        // record: timestamp, lat, lon, altitude (2 bytes, skipped).
        f.def(1, false, 20, 253, 4, 0x86, 0, 4, 0x85, 1, 4, 0x85, 2, 2, 0x84);
        f.u8(1).u32(FIT_TS).u32(semicircles(50.85)).u32(semicircles(5.69)).u16(2600);
        f.u8(1).u32(FIT_TS + 1).u32(semicircles(50.851)).u32(semicircles(5.691)).u16(2610);
        // Record without GPS fix: invalid lat/lon, dropped.
        f.u8(1).u32(FIT_TS + 2).u32(0x7FFFFFFFL).u32(0x7FFFFFFFL).u16(2620);

        List<TrackSample> track = FitTrackDecoder.decode(f.build());

        assertEquals(2, track.size());
        assertEquals(UNIX_TS, track.get(0).timeSec);
        assertEquals(50.85, track.get(0).lat, 1e-6);
        assertEquals(5.69, track.get(0).lon, 1e-6);
        assertEquals(UNIX_TS + 1, track.get(1).timeSec);
    }

    @Test
    public void compressedTimestampHeadersIncludingRollover() throws IOException {
        long base = (FIT_TS & ~0x1FL) + 30; // low 5 bits = 30
        Fit f = new Fit();
        f.def(0, false, 20, 253, 4, 0x86, 0, 4, 0x85, 1, 4, 0x85);
        f.u8(0).u32(base).u32(semicircles(50.0)).u32(semicircles(5.0));
        // Local type 1: record without timestamp field, used with compressed headers.
        f.def(1, false, 20, 0, 4, 0x85, 1, 4, 0x85);
        f.u8(0x80 | (1 << 5) | 31).u32(semicircles(50.001)).u32(semicircles(5.0)); // +1 s
        f.u8(0x80 | (1 << 5) | 2).u32(semicircles(50.002)).u32(semicircles(5.0));  // rollover: +3 s

        List<TrackSample> track = FitTrackDecoder.decode(f.build());

        assertEquals(3, track.size());
        assertEquals(base + FitTrackDecoder.FIT_EPOCH_OFFSET + 1, track.get(1).timeSec);
        assertEquals(base + FitTrackDecoder.FIT_EPOCH_OFFSET + 4, track.get(2).timeSec);
    }

    @Test
    public void bigEndianAndDeveloperFields() throws IOException {
        Fit f = new Fit();
        // Definition with developer data flag: one dev field of 3 bytes to skip.
        f.u8(0x40 | 0x20).u8(0).u8(1).u8(0).u8(20).u8(3);
        f.u8(253).u8(4).u8(0x86).u8(0).u8(4).u8(0x85).u8(1).u8(4).u8(0x85);
        f.u8(1).u8(0).u8(3).u8(0); // dev field: number 0, size 3, dev index 0
        f.u8(0).u32be(FIT_TS).u32be(semicircles(-33.9)).u32be(semicircles(151.2)).u8(9).u8(9).u8(9);

        List<TrackSample> track = FitTrackDecoder.decode(f.build());

        assertEquals(1, track.size());
        assertEquals(-33.9, track.get(0).lat, 1e-6);
        assertEquals(151.2, track.get(0).lon, 1e-6);
        assertEquals(UNIX_TS, track.get(0).timeSec);
    }

    @Test
    public void rejectsNonFitAndTruncatedFiles() {
        assertFalse(FitTrackDecoder.looksLikeFit("<gpx></gpx>".getBytes()));
        Fit f = new Fit();
        f.def(0, false, 20, 253, 4, 0x86, 0, 4, 0x85, 1, 4, 0x85);
        f.u8(0).u32(FIT_TS).u32(1).u32(2);
        byte[] full = f.build();
        assertTrue(FitTrackDecoder.looksLikeFit(full));
        byte[] cut = Arrays.copyOf(full, full.length - 8);
        try {
            FitTrackDecoder.decode(cut);
            fail("expected IOException");
        } catch (IOException expected) {
            // ok
        }
        Fit noDef = new Fit();
        noDef.u8(3).u32(1);
        try {
            FitTrackDecoder.decode(noDef.build());
            fail("expected IOException");
        } catch (IOException expected) {
            // ok
        }
    }
}

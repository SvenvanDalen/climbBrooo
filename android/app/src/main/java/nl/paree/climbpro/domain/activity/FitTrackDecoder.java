package nl.paree.climbpro.domain.activity;

import nl.paree.climbpro.domain.matching.ClimbAttemptMatcher.TrackSample;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal decoder for Garmin FIT activity files (issue #253): extracts the GPS track —
 * {@code record} messages (global number 20) with timestamp, latitude and longitude — and
 * nothing else. Enough to match climbs; everything else in the file is skipped by size.
 * {@link #decodeRecords} additionally returns altitude and distance per record, for
 * importing a ride as a route (MyWhoosh, issue #342).
 *
 * <p>Implements the FIT binary layout: file header, normal and compressed-timestamp record
 * headers, definition messages (either byte order, developer fields) and data messages.
 * Records without a position (indoor, GPS not yet locked) are dropped. Chained FIT files
 * (several FIT files back to back) are decoded one after another.
 */
public final class FitTrackDecoder {

    /** FIT epoch (1989-12-31T00:00:00Z) in Unix seconds. */
    static final long FIT_EPOCH_OFFSET = 631_065_600L;

    private static final int MESG_RECORD = 20;
    private static final int FIELD_LAT = 0;
    private static final int FIELD_LON = 1;
    private static final int FIELD_ALTITUDE = 2;
    private static final int FIELD_DISTANCE = 5;
    private static final int FIELD_ENHANCED_ALTITUDE = 78;
    private static final int FIELD_TIMESTAMP = 253;
    private static final int INVALID_SINT32 = 0x7FFFFFFF;
    private static final double SEMICIRCLE_TO_DEG = 180.0 / 2147483648.0;

    private FitTrackDecoder() {}

    /** One {@code record} message; missing values are NaN, an unknown time is -1. */
    public static final class Record {
        public final double lat;
        public final double lon;
        public final double altitudeM;
        public final double distanceM;
        public final long timeSec;

        Record(double lat, double lon, double altitudeM, double distanceM, long timeSec) {
            this.lat = lat;
            this.lon = lon;
            this.altitudeM = altitudeM;
            this.distanceM = distanceM;
            this.timeSec = timeSec;
        }

        public boolean hasPosition() {
            return !Double.isNaN(lat) && !Double.isNaN(lon);
        }
    }

    public static boolean looksLikeFit(byte[] data) {
        return data != null && data.length >= 12
                && data[8] == '.' && data[9] == 'F' && data[10] == 'I' && data[11] == 'T';
    }

    /** @return track samples in file order with Unix-second timestamps. */
    public static List<TrackSample> decode(byte[] data) throws IOException {
        List<TrackSample> out = new ArrayList<>();
        for (Record r : decodeRecords(data)) {
            if (r.hasPosition() && r.timeSec >= 0) {
                out.add(new TrackSample(r.lat, r.lon, r.timeSec));
            }
        }
        return out;
    }

    /** @return every {@code record} message in file order, with or without a position. */
    public static List<Record> decodeRecords(byte[] data) throws IOException {
        if (!looksLikeFit(data)) throw new IOException("Geen FIT-bestand");
        List<Record> out = new ArrayList<>();
        int pos = 0;
        try {
            while (pos + 12 <= data.length && looksLikeFitAt(data, pos)) {
                pos = decodeOne(data, pos, out);
            }
        } catch (IndexOutOfBoundsException e) {
            throw new IOException("Beschadigd FIT-bestand", e);
        }
        return out;
    }

    private static boolean looksLikeFitAt(byte[] d, int p) {
        return d[p + 8] == '.' && d[p + 9] == 'F' && d[p + 10] == 'I' && d[p + 11] == 'T';
    }

    /** Decodes one FIT file starting at {@code start}; returns the offset after it. */
    private static int decodeOne(byte[] d, int start, List<Record> out) throws IOException {
        int headerSize = d[start] & 0xFF;
        if (headerSize < 12) throw new IOException("Ongeldige FIT-header");
        long dataSize = u32(d, start + 4, false);
        int p = start + headerSize;
        long endL = p + dataSize;
        if (endL > d.length) throw new IOException("FIT-bestand is afgekapt");
        int end = (int) endL;

        Definition[] defs = new Definition[16];
        long lastTimestamp = -1;
        while (p < end) {
            int header = d[p++] & 0xFF;
            if ((header & 0x80) != 0) {
                // Compressed timestamp header: local type in bits 5-6, 5-bit time offset.
                Definition def = defs[(header >> 5) & 0x03];
                if (def == null) throw new IOException("FIT-data zonder definitie");
                if (p + def.size > end) throw new IOException("FIT-bestand is afgekapt");
                int offset = header & 0x1F;
                long ts = -1;
                if (lastTimestamp >= 0) {
                    ts = (lastTimestamp & ~0x1FL) + offset;
                    if (offset < (lastTimestamp & 0x1F)) ts += 0x20; // 5-bit rollover
                }
                lastTimestamp = readData(d, p, def, lastTimestamp, ts, out);
                p += def.size;
            } else if ((header & 0x40) != 0) {
                boolean devData = (header & 0x20) != 0;
                Definition def = new Definition();
                p++; // reserved
                def.bigEndian = d[p++] == 1;
                def.global = (int) (def.bigEndian ? u16be(d, p) : u16le(d, p));
                p += 2;
                int fields = d[p++] & 0xFF;
                def.nums = new int[fields];
                def.sizes = new int[fields];
                for (int i = 0; i < fields; i++) {
                    def.nums[i] = d[p] & 0xFF;
                    def.sizes[i] = d[p + 1] & 0xFF;
                    def.size += def.sizes[i];
                    p += 3;
                }
                if (devData) {
                    int devFields = d[p++] & 0xFF;
                    for (int i = 0; i < devFields; i++) {
                        def.size += d[p + 1] & 0xFF;
                        p += 3;
                    }
                }
                defs[header & 0x0F] = def;
            } else {
                Definition def = defs[header & 0x0F];
                if (def == null) throw new IOException("FIT-data zonder definitie");
                if (p + def.size > end) throw new IOException("FIT-bestand is afgekapt");
                lastTimestamp = readData(d, p, def, lastTimestamp, -1, out);
                p += def.size;
            }
        }
        return end + 2; // skip the file CRC
    }

    /**
     * Reads one data message. Timestamps are raw FIT seconds; {@code knownTs} is the
     * timestamp implied by a compressed header, or -1.
     *
     * @return the timestamp to use as the base for later compressed headers
     */
    private static long readData(byte[] d, int p, Definition def, long lastTimestamp,
                                 long knownTs, List<Record> out) {
        long ts = knownTs;
        int lat = INVALID_SINT32, lon = INVALID_SINT32;
        double altitude = Double.NaN, enhancedAltitude = Double.NaN, distance = Double.NaN;
        int q = p;
        for (int i = 0; i < def.nums.length; i++) {
            int num = def.nums[i], size = def.sizes[i];
            if (num == FIELD_TIMESTAMP && size == 4) {
                long v = u32(d, q, def.bigEndian);
                if (v != 0xFFFFFFFFL) ts = v;
            } else if (def.global == MESG_RECORD && num == FIELD_LAT && size == 4) {
                lat = (int) u32(d, q, def.bigEndian);
            } else if (def.global == MESG_RECORD && num == FIELD_LON && size == 4) {
                lon = (int) u32(d, q, def.bigEndian);
            } else if (def.global == MESG_RECORD && num == FIELD_ALTITUDE && size == 2) {
                long v = def.bigEndian ? u16be(d, q) : u16le(d, q);
                if (v != 0xFFFFL) altitude = v / 5.0 - 500.0;
            } else if (def.global == MESG_RECORD && num == FIELD_ENHANCED_ALTITUDE && size == 4) {
                long v = u32(d, q, def.bigEndian);
                if (v != 0xFFFFFFFFL) enhancedAltitude = v / 5.0 - 500.0;
            } else if (def.global == MESG_RECORD && num == FIELD_DISTANCE && size == 4) {
                long v = u32(d, q, def.bigEndian);
                if (v != 0xFFFFFFFFL) distance = v / 100.0;
            }
            q += size;
        }
        if (def.global == MESG_RECORD) {
            boolean positioned = lat != INVALID_SINT32 && lon != INVALID_SINT32;
            out.add(new Record(
                    positioned ? lat * SEMICIRCLE_TO_DEG : Double.NaN,
                    positioned ? lon * SEMICIRCLE_TO_DEG : Double.NaN,
                    Double.isNaN(enhancedAltitude) ? altitude : enhancedAltitude,
                    distance,
                    ts >= 0 ? ts + FIT_EPOCH_OFFSET : -1));
        }
        return ts >= 0 ? ts : lastTimestamp;
    }

    private static final class Definition {
        boolean bigEndian;
        int global;
        int[] nums;
        int[] sizes;
        int size;
    }

    private static long u16le(byte[] d, int p) {
        return (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8);
    }

    private static long u16be(byte[] d, int p) {
        return ((d[p] & 0xFF) << 8) | (d[p + 1] & 0xFF);
    }

    private static long u32(byte[] d, int p, boolean bigEndian) {
        if (bigEndian) {
            return ((long) (d[p] & 0xFF) << 24) | ((d[p + 1] & 0xFF) << 16)
                    | ((d[p + 2] & 0xFF) << 8) | (d[p + 3] & 0xFF);
        }
        return (d[p] & 0xFF) | ((d[p + 1] & 0xFF) << 8) | ((d[p + 2] & 0xFF) << 16)
                | ((long) (d[p + 3] & 0xFF) << 24);
    }
}

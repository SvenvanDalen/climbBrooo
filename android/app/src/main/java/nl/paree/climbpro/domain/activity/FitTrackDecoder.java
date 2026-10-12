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
                Definition def = requireDefinition(defs[(header >> 5) & 0x03], p, end);
                long ts = compressedTimestamp(lastTimestamp, header & 0x1F);
                lastTimestamp = readData(d, p, def, lastTimestamp, ts, out);
                p += def.size;
            } else if ((header & 0x40) != 0) {
                Definition def = new Definition();
                p = readDefinition(d, p, (header & 0x20) != 0, def);
                defs[header & 0x0F] = def;
            } else {
                Definition def = requireDefinition(defs[header & 0x0F], p, end);
                lastTimestamp = readData(d, p, def, lastTimestamp, -1, out);
                p += def.size;
            }
        }
        return end + 2; // skip the file CRC
    }

    /** A data message's definition must exist and its data must fit before {@code end}. */
    private static Definition requireDefinition(Definition def, int p, int end)
            throws IOException {
        if (def == null) throw new IOException("FIT-data zonder definitie");
        if (p + def.size > end) throw new IOException("FIT-bestand is afgekapt");
        return def;
    }

    /** Full timestamp of a compressed header; -1 while no full timestamp was seen yet. */
    private static long compressedTimestamp(long lastTimestamp, int offset) {
        if (lastTimestamp < 0) return -1;
        long ts = (lastTimestamp & ~0x1FL) + offset;
        if (offset < (lastTimestamp & 0x1F)) ts += 0x20; // 5-bit rollover
        return ts;
    }

    /** Reads a definition message body at {@code p} into {@code def}; returns the offset after it. */
    private static int readDefinition(byte[] d, int p, boolean devData, Definition def) {
        p++; // reserved
        def.bigEndian = d[p++] == 1;
        def.global = (int) u16(d, p, def.bigEndian);
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
        return p;
    }

    /**
     * Reads one data message; a {@code record} message is appended to {@code out}.
     * Returns the latest full timestamp ({@code lastTimestamp} when the message has none).
     */
    private static long readData(byte[] d, int p, Definition def, long lastTimestamp,
                                 long knownTs, List<Record> out) {
        RecordFields f = new RecordFields(knownTs);
        int q = p;
        for (int i = 0; i < def.nums.length; i++) {
            int num = def.nums[i], size = def.sizes[i];
            if (num == FIELD_TIMESTAMP && size == 4) {
                long v = u32(d, q, def.bigEndian);
                if (v != 0xFFFFFFFFL) f.ts = v;
            } else if (def.global == MESG_RECORD) {
                readRecordField(d, q, def.bigEndian, num, size, f);
            }
            q += size;
        }
        if (def.global == MESG_RECORD) out.add(f.toRecord());
        return f.ts >= 0 ? f.ts : lastTimestamp;
    }

    private static void readRecordField(byte[] d, int q, boolean bigEndian, int num, int size,
                                        RecordFields f) {
        if (size == 4) {
            readRecordField32(num, u32(d, q, bigEndian), f);
        } else if (num == FIELD_ALTITUDE && size == 2) {
            long v = u16(d, q, bigEndian);
            if (v != 0xFFFFL) f.altitude = v / 5.0 - 500.0;
        }
    }

    private static void readRecordField32(int num, long v, RecordFields f) {
        switch (num) {
            case FIELD_LAT:
                f.lat = (int) v;
                break;
            case FIELD_LON:
                f.lon = (int) v;
                break;
            case FIELD_ENHANCED_ALTITUDE:
                if (v != 0xFFFFFFFFL) f.enhancedAltitude = v / 5.0 - 500.0;
                break;
            case FIELD_DISTANCE:
                if (v != 0xFFFFFFFFL) f.distance = v / 100.0;
                break;
            default:
                break;
        }
    }

    /** Raw field values of one {@code record} message while it is being read. */
    private static final class RecordFields {
        long ts;
        int lat = INVALID_SINT32;
        int lon = INVALID_SINT32;
        double altitude = Double.NaN;
        double enhancedAltitude = Double.NaN;
        double distance = Double.NaN;

        RecordFields(long knownTs) {
            this.ts = knownTs;
        }

        Record toRecord() {
            boolean positioned = lat != INVALID_SINT32 && lon != INVALID_SINT32;
            return new Record(
                    positioned ? lat * SEMICIRCLE_TO_DEG : Double.NaN,
                    positioned ? lon * SEMICIRCLE_TO_DEG : Double.NaN,
                    Double.isNaN(enhancedAltitude) ? altitude : enhancedAltitude,
                    distance,
                    ts >= 0 ? ts + FIT_EPOCH_OFFSET : -1);
        }
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

    private static long u16(byte[] d, int p, boolean bigEndian) {
        return bigEndian ? u16be(d, p) : u16le(d, p);
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

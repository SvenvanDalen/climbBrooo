package nl.paree.climbpro.domain.activity;

import nl.paree.climbpro.domain.matching.ClimbAttemptMatcher.TrackSample;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.xml.parsers.SAXParserFactory;

/**
 * Reads recorded activities from the files Garmin Connect exports (issue #253): a FIT file
 * (the device's original recording), a GPX with timestamps, or the zip Garmin Connect's
 * "Export original" produces (one or more FIT/GPX files inside). Returns the GPS track of
 * each activity; files without a timed track are skipped. Pure — no Android.
 */
public final class ActivityFileReader {

    /** One activity found in the input. */
    public static final class Activity {
        public final String name;
        public final List<TrackSample> track;

        Activity(String name, List<TrackSample> track) {
            this.name = name;
            this.track = track;
        }

        public long startEpochSec() {
            return track.isEmpty() ? 0 : track.get(0).timeSec;
        }
    }

    private static final int MAX_ZIP_ENTRY_BYTES = 64 * 1024 * 1024;

    private ActivityFileReader() {}

    public static List<Activity> read(String name, byte[] data) throws IOException {
        List<Activity> out = new ArrayList<>();
        if (FitTrackDecoder.looksLikeFit(data)) {
            addIfTimed(out, name, FitTrackDecoder.decode(data));
        } else if (data.length >= 4 && data[0] == 'P' && data[1] == 'K') {
            readZip(data, out);
        } else {
            addIfTimed(out, name, parseGpx(data));
        }
        return out;
    }

    private static void readZip(byte[] data, List<Activity> out) throws IOException {
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(data))) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String entry = e.getName().toLowerCase(Locale.ROOT);
                if (!entry.endsWith(".fit") && !entry.endsWith(".gpx")) continue;
                byte[] bytes = readCapped(zip);
                try {
                    out.addAll(read(e.getName(), bytes));
                } catch (IOException bad) {
                    // One unreadable file in the export must not sink the others.
                }
            }
        }
    }

    private static void addIfTimed(List<Activity> out, String name, List<TrackSample> track) {
        if (track.size() >= 2) out.add(new Activity(name, track));
    }

    /** {@code <trkpt>}/{@code <rtept>} with a {@code <time>}; untimed points are dropped. */
    static List<TrackSample> parseGpx(byte[] data) throws IOException {
        List<TrackSample> out = new ArrayList<>();
        try {
            SAXParserFactory factory = SAXParserFactory.newInstance();
            factory.setNamespaceAware(false);
            factory.newSAXParser().parse(new ByteArrayInputStream(data), new DefaultHandler() {
                double lat, lon;
                boolean inPoint, inTime;
                Long time;
                final StringBuilder text = new StringBuilder();

                @Override
                public void startElement(String uri, String local, String qName, Attributes a) {
                    String tag = strip(qName);
                    if (tag.equals("trkpt") || tag.equals("rtept")) {
                        inPoint = true;
                        time = null;
                        lat = parse(a.getValue("lat"));
                        lon = parse(a.getValue("lon"));
                    } else if (inPoint && tag.equals("time")) {
                        inTime = true;
                        text.setLength(0);
                    }
                }

                @Override
                public void characters(char[] ch, int start, int length) {
                    if (inTime) text.append(ch, start, length);
                }

                @Override
                public void endElement(String uri, String local, String qName) {
                    String tag = strip(qName);
                    if (tag.equals("time") && inTime) {
                        inTime = false;
                        time = parseTime(text.toString().trim());
                    } else if (tag.equals("trkpt") || tag.equals("rtept")) {
                        inPoint = false;
                        if (time != null && !Double.isNaN(lat) && !Double.isNaN(lon)) {
                            out.add(new TrackSample(lat, lon, time));
                        }
                    }
                }
            });
        } catch (SAXException | javax.xml.parsers.ParserConfigurationException e) {
            throw new IOException("Geen geldig GPX-bestand", e);
        }
        return out;
    }

    private static String strip(String qName) {
        int i = qName.indexOf(':');
        return i >= 0 ? qName.substring(i + 1) : qName;
    }

    private static double parse(String v) {
        try {
            return v != null ? Double.parseDouble(v) : Double.NaN;
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    static Long parseTime(String s) {
        try {
            return Instant.parse(s).getEpochSecond();
        } catch (DateTimeParseException e) {
            try {
                return OffsetDateTime.parse(s).toEpochSecond();
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    private static byte[] readCapped(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] tmp = new byte[8192];
        int n;
        while ((n = in.read(tmp)) != -1) {
            buf.write(tmp, 0, n);
            if (buf.size() > MAX_ZIP_ENTRY_BYTES) throw new IOException("Bestand in zip is te groot");
        }
        return buf.toByteArray();
    }
}

package nl.paree.climbpro.domain.events;

import nl.paree.climbpro.data.events.CyclingEvent;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal iCalendar (RFC 5545) reader for event feeds (issue #241): VEVENT blocks with
 * SUMMARY, DTSTART, LOCATION, GEO, URL, DESCRIPTION and UID. Handles line folding, property
 * parameters (DTSTART;VALUE=DATE / ;TZID=…) and text escapes. Recurrence rules are ignored
 * — tour rides are one-off dates. Events without a name or a parseable start date are
 * skipped. Pure, no Android dependencies.
 */
public final class IcsParser {

    private IcsParser() {}

    public static List<CyclingEvent> parse(String ics, String feedUrl) {
        List<CyclingEvent> out = new ArrayList<>();
        if (ics == null) return out;
        CyclingEvent cur = null;
        for (String line : unfold(ics)) {
            if (line.equalsIgnoreCase("BEGIN:VEVENT")) {
                cur = new CyclingEvent();
                cur.feedUrl = feedUrl;
                continue;
            }
            if (line.equalsIgnoreCase("END:VEVENT")) {
                if (cur != null && cur.name != null && cur.date != null) {
                    String text = cur.name + " " + (cur.description != null ? cur.description : "");
                    cur.distancesKm = EventTextStats.distancesKm(text);
                    cur.elevationM = EventTextStats.elevationM(text);
                    if (cur.uid == null) cur.uid = cur.date + "|" + cur.name;
                    out.add(cur);
                }
                cur = null;
                continue;
            }
            if (cur == null) continue;
            int colon = line.indexOf(':');
            if (colon <= 0) continue;
            String head = line.substring(0, colon);
            String value = line.substring(colon + 1);
            int semi = head.indexOf(';');
            String name = (semi >= 0 ? head.substring(0, semi) : head).toUpperCase(java.util.Locale.ROOT);
            switch (name) {
                case "SUMMARY": cur.name = blankToNull(unescape(value)); break;
                case "DESCRIPTION": cur.description = blankToNull(unescape(value)); break;
                case "LOCATION": cur.location = blankToNull(unescape(value)); break;
                case "URL": cur.url = blankToNull(value.trim()); break;
                case "UID": cur.uid = blankToNull(value.trim()); break;
                case "DTSTART": cur.date = parseDate(value); break;
                case "GEO": parseGeo(cur, value); break;
                default: break;
            }
        }
        return out;
    }

    /** Joins folded lines (a line starting with a space or tab continues the previous one). */
    static List<String> unfold(String ics) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = null;
        for (String raw : ics.split("\r\n|\n|\r", -1)) {
            if (!raw.isEmpty() && (raw.charAt(0) == ' ' || raw.charAt(0) == '\t') && cur != null) {
                cur.append(raw, 1, raw.length());
            } else {
                if (cur != null) lines.add(cur.toString());
                cur = new StringBuilder(raw);
            }
        }
        if (cur != null) lines.add(cur.toString());
        return lines;
    }

    /** "20270614", "20270614T080000", "20270614T060000Z" → "2027-06-14"; null if unparseable. */
    static String parseDate(String value) {
        String v = value.trim();
        if (v.length() < 8) return null;
        try {
            return LocalDate.of(Integer.parseInt(v.substring(0, 4)),
                    Integer.parseInt(v.substring(4, 6)),
                    Integer.parseInt(v.substring(6, 8))).toString();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void parseGeo(CyclingEvent e, String value) {
        String[] parts = value.split("[;,]");
        if (parts.length != 2) return;
        try {
            double lat = Double.parseDouble(parts[0].trim());
            double lon = Double.parseDouble(parts[1].trim());
            if (Math.abs(lat) <= 90 && Math.abs(lon) <= 180 && !(lat == 0 && lon == 0)) {
                e.lat = lat;
                e.lon = lon;
            }
        } catch (NumberFormatException ignored) {
            // No usable GEO: the location text is geocoded instead.
        }
    }

    static String unescape(String v) {
        StringBuilder sb = new StringBuilder(v.length());
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c == '\\' && i + 1 < v.length()) {
                char n = v.charAt(++i);
                sb.append(n == 'n' || n == 'N' ? '\n' : n);
            } else {
                sb.append(c);
            }
        }
        return sb.toString().trim();
    }

    private static String blankToNull(String s) {
        return s == null || s.trim().isEmpty() ? null : s.trim();
    }
}

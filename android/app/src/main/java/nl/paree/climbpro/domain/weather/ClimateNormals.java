package nl.paree.climbpro.domain.weather;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Compact climatology of one location (issue #41): per calendar month × day-part the mean
 * temperature, mean wind speed, the chance that the day-part is wet and the mean wind vector
 * (so the headwind along any climb bearing can be derived later). Built once from an
 * Open-Meteo archive response (hourly, local time) and cached as a few hundred bytes. Pure.
 */
public final class ClimateNormals {

    /** Rideable parts of the day, in local time. Hours outside them are ignored. */
    public enum DayPart {
        OCHTEND("ochtend", 7, 11),
        MIDDAG("middag", 11, 15),
        NAMIDDAG("namiddag", 15, 19),
        AVOND("avond", 19, 22);

        public final String label;
        final int fromHour;
        final int toHourExclusive;

        DayPart(String label, int fromHour, int toHourExclusive) {
            this.label = label;
            this.fromHour = fromHour;
            this.toHourExclusive = toHourExclusive;
        }

        /** The day-part containing local {@code hour}, or null at night. */
        public static DayPart ofHour(int hour) {
            for (DayPart p : values()) {
                if (hour >= p.fromHour && hour < p.toHourExclusive) return p;
            }
            return null;
        }
    }

    /** One month × day-part aggregate. */
    public static final class Cell {
        public static final Cell EMPTY =
                new Cell(Double.NaN, Double.NaN, Double.NaN, Double.NaN, Double.NaN, 0);

        public final double meanTempC;
        public final double meanWindKmh;
        /** Fraction (0–1) of these day-parts with at least {@link #WET_MM} of precipitation. */
        public final double rainChance;
        /** Mean of speed·sin(direction the wind comes from), km/h. */
        final double windFromU;
        /** Mean of speed·cos(direction the wind comes from), km/h. */
        final double windFromV;
        public final int hours;

        private Cell(double meanTempC, double meanWindKmh, double rainChance,
                     double windFromU, double windFromV, int hours) {
            this.meanTempC = meanTempC;
            this.meanWindKmh = meanWindKmh;
            this.rainChance = rainChance;
            this.windFromU = windFromU;
            this.windFromV = windFromV;
            this.hours = hours;
        }

        public static Cell of(double meanTempC, double meanWindKmh, double rainChance,
                              double windFromU, double windFromV, int hours) {
            return new Cell(meanTempC, meanWindKmh, rainChance, windFromU, windFromV, hours);
        }

        public boolean hasData() { return hours > 0 && !Double.isNaN(meanTempC); }

        /**
         * Mean wind component against a rider heading {@code bearingDeg} (positive = headwind,
         * negative = tailwind); 0 when the bearing or the wind is unknown.
         */
        public double headwindKmh(double bearingDeg) {
            if (Double.isNaN(bearingDeg) || Double.isNaN(windFromU) || Double.isNaN(windFromV)) {
                return 0;
            }
            double b = Math.toRadians(bearingDeg);
            return windFromU * Math.sin(b) + windFromV * Math.cos(b);
        }
    }

    /** A day-part with at least this much precipitation counts as wet. */
    public static final double WET_MM = 0.5;

    private static final int FORMAT_VERSION = 1;
    private static final int FIELDS = 6;

    private final Cell[][] cells;

    /** {@code cells[month-1][dayPart.ordinal()]}; missing entries become {@link Cell#EMPTY}. */
    public ClimateNormals(Cell[][] cells) {
        this.cells = new Cell[12][DayPart.values().length];
        for (int m = 0; m < 12; m++) {
            for (int p = 0; p < DayPart.values().length; p++) {
                Cell c = cells != null && m < cells.length && cells[m] != null
                        && p < cells[m].length ? cells[m][p] : null;
                this.cells[m][p] = c != null ? c : Cell.EMPTY;
            }
        }
    }

    /** @param month 1–12 */
    public Cell cell(int month, DayPart part) { return cells[month - 1][part.ordinal()]; }

    /** Aggregates an Open-Meteo archive response requested with {@code timezone=auto}. */
    public static ClimateNormals fromArchive(String json) throws IOException {
        JsonNode hourly = new ObjectMapper().readTree(json).get("hourly");
        if (hourly == null || !hourly.has("time")) {
            throw new IOException("Onverwacht antwoord van de weerdienst");
        }
        JsonNode time = hourly.get("time");
        JsonNode temp = hourly.path("temperature_2m");
        JsonNode speed = hourly.path("wind_speed_10m");
        JsonNode dir = hourly.path("wind_direction_10m");
        JsonNode precip = hourly.path("precipitation");

        Sums sums = new Sums(DayPart.values().length);
        for (int i = 0; i < time.size(); i++) {
            String t = time.get(i).asText();
            int[] monthHour = monthAndHour(t);
            if (monthHour == null) continue;
            int month = monthHour[0];
            DayPart part = DayPart.ofHour(monthHour[1]);
            if (part != null && month >= 1 && month <= 12) {
                sums.add(month - 1, part, t, number(temp, i), number(speed, i), number(dir, i),
                        number(precip, i));
            }
        }
        sums.countRainBlocks();
        return new ClimateNormals(sums.cells());
    }

    /** {month, hour} of an ISO local date-time ("2024-05-01T13:00"); null if unparseable. */
    private static int[] monthAndHour(String t) {
        if (t.length() < 13) return null;
        try {
            return new int[] {Integer.parseInt(t.substring(5, 7)),
                    Integer.parseInt(t.substring(11, 13))};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Running sums per (month, day-part) while the hourly archive is read. */
    private static final class Sums {
        final int parts;
        final double[][] tempSum;
        final double[][] windSum;
        final double[][] uSum;
        final double[][] vSum;
        final int[][] tempN;
        final int[][] windN;
        final int[][] hours;
        final int[][] blocks;
        final int[][] wetBlocks;
        // Precipitation per (date, day-part): the rain chance is about the ride, not one hour.
        final Map<String, Double> blockRain = new HashMap<>();
        final Map<String, int[]> blockCell = new HashMap<>();

        Sums(int parts) {
            this.parts = parts;
            tempSum = new double[12][parts];
            windSum = new double[12][parts];
            uSum = new double[12][parts];
            vSum = new double[12][parts];
            tempN = new int[12][parts];
            windN = new int[12][parts];
            hours = new int[12][parts];
            blocks = new int[12][parts];
            wetBlocks = new int[12][parts];
        }

        void add(int m, DayPart part, String t, double tv, double sv, double dv, double pv) {
            int p = part.ordinal();
            hours[m][p]++;
            if (!Double.isNaN(tv)) {
                tempSum[m][p] += tv;
                tempN[m][p]++;
            }
            if (!Double.isNaN(sv) && !Double.isNaN(dv)) {
                windSum[m][p] += sv;
                uSum[m][p] += sv * Math.sin(Math.toRadians(dv));
                vSum[m][p] += sv * Math.cos(Math.toRadians(dv));
                windN[m][p]++;
            }
            if (!Double.isNaN(pv)) {
                String key = t.substring(0, 10) + part.name();
                blockRain.merge(key, pv, Double::sum);
                blockCell.putIfAbsent(key, new int[] {m, p});
            }
        }

        void countRainBlocks() {
            for (Map.Entry<String, Double> e : blockRain.entrySet()) {
                int[] mp = blockCell.get(e.getKey());
                blocks[mp[0]][mp[1]]++;
                if (e.getValue() >= WET_MM) wetBlocks[mp[0]][mp[1]]++;
            }
        }

        Cell[][] cells() {
            Cell[][] out = new Cell[12][parts];
            for (int m = 0; m < 12; m++) {
                for (int p = 0; p < parts; p++) {
                    if (hours[m][p] != 0) out[m][p] = cell(m, p);
                }
            }
            return out;
        }

        private Cell cell(int m, int p) {
            return new Cell(
                    tempN[m][p] > 0 ? tempSum[m][p] / tempN[m][p] : Double.NaN,
                    windN[m][p] > 0 ? windSum[m][p] / windN[m][p] : Double.NaN,
                    blocks[m][p] > 0 ? (double) wetBlocks[m][p] / blocks[m][p] : Double.NaN,
                    windN[m][p] > 0 ? uSum[m][p] / windN[m][p] : Double.NaN,
                    windN[m][p] > 0 ? vSum[m][p] / windN[m][p] : Double.NaN,
                    hours[m][p]);
        }
    }

    /** Cache form: {@code {"v":1,"cells":[[temp,wind,rain,u,v,hours] × 48]}} (month-major). */
    public String toJson() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        root.put("v", FORMAT_VERSION);
        ArrayNode arr = root.putArray("cells");
        for (Cell[] row : cells) {
            for (Cell c : row) {
                ArrayNode a = arr.addArray();
                put(a, c.meanTempC);
                put(a, c.meanWindKmh);
                put(a, c.rainChance);
                put(a, c.windFromU);
                put(a, c.windFromV);
                a.add(c.hours);
            }
        }
        try {
            return mapper.writeValueAsString(root);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    public static ClimateNormals fromJson(String json) throws IOException {
        JsonNode root = new ObjectMapper().readTree(json);
        int parts = DayPart.values().length;
        JsonNode arr = root == null ? null : root.get("cells");
        if (arr == null || root.path("v").asInt() != FORMAT_VERSION || !arr.isArray()
                || arr.size() != 12 * parts) {
            throw new IOException("Ongeldige klimaatcache");
        }
        Cell[][] out = new Cell[12][parts];
        for (int i = 0; i < arr.size(); i++) {
            JsonNode a = arr.get(i);
            if (!a.isArray() || a.size() != FIELDS) throw new IOException("Ongeldige klimaatcache");
            out[i / parts][i % parts] = new Cell(number(a, 0), number(a, 1), number(a, 2),
                    number(a, 3), number(a, 4), a.get(5).asInt());
        }
        return new ClimateNormals(out);
    }

    /** Initial compass bearing from foot to top in degrees (0 = north), NaN for one point. */
    public static double bearingDeg(double lat1, double lon1, double lat2, double lon2) {
        if (lat1 == lat2 && lon1 == lon2) return Double.NaN;
        double p1 = Math.toRadians(lat1);
        double p2 = Math.toRadians(lat2);
        double dl = Math.toRadians(lon2 - lon1);
        double y = Math.sin(dl) * Math.cos(p2);
        double x = Math.cos(p1) * Math.sin(p2) - Math.sin(p1) * Math.cos(p2) * Math.cos(dl);
        return (Math.toDegrees(Math.atan2(y, x)) + 360) % 360;
    }

    private static void put(ArrayNode a, double v) {
        if (Double.isNaN(v)) a.addNull();
        else a.add(Math.round(v * 100) / 100.0);
    }

    private static double number(JsonNode arr, int i) {
        JsonNode v = arr.get(i);
        return v == null || v.isNull() || !v.isNumber() ? Double.NaN : v.asDouble();
    }
}

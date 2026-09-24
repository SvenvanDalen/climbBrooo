package nl.paree.climbpro.domain.region;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Countries and provinces where the rider climbed, with visit counts (issue #250). Pure. */
public final class VisitedRegions {

    public static final String UNKNOWN_PROVINCE = "(regio onbekend)";

    public static final class Visit {
        final String countryCode, country, province;
        final double lat, lon;

        public Visit(String countryCode, String country, String province, double lat, double lon) {
            this.countryCode = countryCode;
            this.country = country;
            this.province = province;
            this.lat = lat;
            this.lon = lon;
        }
    }

    public static final class Province {
        public final String name;
        public final int visits;
        public final double lat, lon;

        Province(String name, int visits, double lat, double lon) {
            this.name = name; this.visits = visits; this.lat = lat; this.lon = lon;
        }
    }

    public static final class Country {
        public final String code, name;
        public final int visits;
        public final List<Province> provinces;

        Country(String code, String name, int visits, List<Province> provinces) {
            this.code = code; this.name = name; this.visits = visits; this.provinces = provinces;
        }
    }

    private VisitedRegions() {}

    public static List<Country> aggregate(List<Visit> visits) {
        Map<String, Map<String, List<Visit>>> byCountry = new LinkedHashMap<>();
        Map<String, Visit> firstOfCountry = new LinkedHashMap<>();
        for (Visit v : visits) {
            String prov = v.province == null || v.province.trim().isEmpty()
                    ? UNKNOWN_PROVINCE : v.province.trim();
            byCountry.computeIfAbsent(v.countryCode, k -> new LinkedHashMap<>())
                    .computeIfAbsent(prov, k -> new ArrayList<>()).add(v);
            firstOfCountry.putIfAbsent(v.countryCode, v);
        }
        List<Country> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, List<Visit>>> c : byCountry.entrySet()) {
            List<Province> provinces = new ArrayList<>();
            int total = 0;
            for (Map.Entry<String, List<Visit>> p : c.getValue().entrySet()) {
                double lat = 0, lon = 0;
                for (Visit v : p.getValue()) { lat += v.lat; lon += v.lon; }
                int n = p.getValue().size();
                provinces.add(new Province(p.getKey(), n, lat / n, lon / n));
                total += n;
            }
            provinces.sort(Comparator.comparingInt((Province p) -> -p.visits).thenComparing(p -> p.name));
            out.add(new Country(c.getKey(), firstOfCountry.get(c.getKey()).country, total,
                    Collections.unmodifiableList(provinces)));
        }
        out.sort(Comparator.comparingInt((Country c) -> -c.visits).thenComparing(c -> c.name));
        return out;
    }
}

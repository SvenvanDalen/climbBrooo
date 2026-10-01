package nl.paree.climbpro.domain.history;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses the bundled famous-climb dataset (issue #212). Pure (no Android). Entries with a
 * missing name, top or facts, or with out-of-range coordinates, are skipped instead of failing
 * the whole file, so one bad edit to the dataset can't hide every other climb's facts.
 */
public final class ClimbFactsParser {

    private ClimbFactsParser() {}

    public static List<FamousClimb> parse(InputStream in) throws IOException {
        JsonNode root = new ObjectMapper().readTree(in);
        List<FamousClimb> out = new ArrayList<>();
        JsonNode climbs = root == null ? null : root.get("climbs");
        if (climbs == null || !climbs.isArray()) return out;
        for (JsonNode c : climbs) {
            FamousClimb climb = parseClimb(c);
            if (climb != null) out.add(climb);
        }
        return out;
    }

    private static FamousClimb parseClimb(JsonNode c) {
        String name = text(c, "name");
        double[] top = latLon(c.get("top"));
        if (name == null || top == null) return null;

        List<String> facts = new ArrayList<>();
        JsonNode factsNode = c.get("facts");
        if (factsNode != null && factsNode.isArray()) {
            for (JsonNode f : factsNode) {
                String s = f.asText("").trim();
                if (!s.isEmpty()) facts.add(s);
            }
        }
        if (facts.isEmpty()) return null;

        List<String> aliases = new ArrayList<>();
        String normName = FamousClimbMatcher.normalize(name);
        if (!normName.isEmpty()) aliases.add(normName);
        JsonNode aliasNode = c.get("aliases");
        if (aliasNode != null && aliasNode.isArray()) {
            for (JsonNode a : aliasNode) {
                String s = FamousClimbMatcher.normalize(a.asText(""));
                if (!s.isEmpty() && !aliases.contains(s)) aliases.add(s);
            }
        }

        List<FamousClimb.Side> sides = new ArrayList<>();
        JsonNode sidesNode = c.get("sides");
        if (sidesNode != null && sidesNode.isArray()) {
            for (JsonNode s : sidesNode) {
                double[] start = latLon(s.get("start"));
                if (start == null) continue;
                String label = text(s, "label");
                sides.add(new FamousClimb.Side(label, start[0], start[1]));
            }
        }

        String id = text(c, "id");
        return new FamousClimb(id != null ? id : normName, name, aliases, top[0], top[1],
                sides, facts);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null || !v.isTextual()) return null;
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }

    private static double[] latLon(JsonNode node) {
        if (node == null || !node.isArray() || node.size() != 2
                || !node.get(0).isNumber() || !node.get(1).isNumber()) return null;
        double lat = node.get(0).asDouble();
        double lon = node.get(1).asDouble();
        if (lat < -90 || lat > 90 || lon < -180 || lon > 180) return null;
        return new double[] {lat, lon};
    }
}

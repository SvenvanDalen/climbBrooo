package nl.paree.climbpro.domain.history;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ClimbFactsParserTest {

    private static List<FamousClimb> parse(String json) throws Exception {
        return ClimbFactsParser.parse(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    public void parsesEntryWithSidesAliasesAndFacts() throws Exception {
        List<FamousClimb> list = parse("{\"climbs\":[{\"id\":\"v\",\"name\":\"Mont Ventoux\","
                + "\"aliases\":[\"Ventoux\"],\"top\":[44.17,5.27],"
                + "\"sides\":[{\"label\":\"Bédoin\",\"start\":[44.12,5.18]}],"
                + "\"facts\":[\"Een\",\" \",\"Twee\"]}]}");
        assertEquals(1, list.size());
        FamousClimb c = list.get(0);
        assertEquals("v", c.id);
        assertEquals(44.17, c.topLat, 1e-9);
        assertEquals(1, c.sides.size());
        assertEquals("Bédoin", c.sides.get(0).label);
        assertEquals(2, c.facts.size()); // blank fact dropped
        assertTrue(c.aliases.contains("mont ventoux")); // name is always an alias
        assertTrue(c.aliases.contains("ventoux"));
    }

    @Test
    public void skipsBrokenEntriesButKeepsTheRest() throws Exception {
        List<FamousClimb> list = parse("{\"climbs\":["
                + "{\"name\":\"NoTop\",\"facts\":[\"x\"]},"
                + "{\"name\":\"BadTop\",\"top\":[95,5],\"facts\":[\"x\"]},"
                + "{\"name\":\"NoFacts\",\"top\":[45,5],\"facts\":[]},"
                + "{\"top\":[45,5],\"facts\":[\"x\"]},"
                + "{\"name\":\"Good\",\"top\":[45,5],\"sides\":[{\"start\":\"oops\"}],\"facts\":[\"x\"]}"
                + "]}");
        assertEquals(1, list.size());
        assertEquals("Good", list.get(0).name);
        assertEquals("good", list.get(0).id); // id falls back to the normalised name
        assertTrue(list.get(0).sides.isEmpty());
    }

    @Test
    public void missingClimbsArrayGivesEmptyList() throws Exception {
        assertTrue(parse("{}").isEmpty());
    }

    /** Guards the shipped dataset: it parses fully, ids are unique, every entry is usable. */
    @Test
    public void bundledDatasetIsValid() throws Exception {
        File file = new File("src/main/assets/climb_facts.json");
        assertTrue("dataset not found at " + file.getAbsolutePath(), file.exists());
        List<FamousClimb> list;
        try (InputStream in = new FileInputStream(file)) {
            list = ClimbFactsParser.parse(in);
        }
        String raw = new String(java.nio.file.Files.readAllBytes(file.toPath()),
                StandardCharsets.UTF_8);
        int declared = raw.split("\"facts\"", -1).length - 1;
        assertEquals("every dataset entry must parse", declared, list.size());
        assertTrue(list.size() >= 20);

        Set<String> ids = new HashSet<>();
        for (FamousClimb c : list) {
            assertTrue("duplicate id " + c.id, ids.add(c.id));
            assertFalse(c.name + " has no sides", c.sides.isEmpty());
            for (FamousClimb.Side s : c.sides) {
                assertNotNull(c.name + " side without label", s.label);
                // A side's start must be a plausible foot: within 40 km of the top.
                double d = nl.paree.climbpro.domain.route.CumulativeDistance.haversine(
                        s.lat, s.lon, c.topLat, c.topLon);
                assertTrue(c.name + " / " + s.label + " start " + d + " m from top",
                        d > 100 && d < 40_000);
            }
            for (String fact : c.facts) {
                assertTrue(c.name + ": keep facts brief", fact.length() <= 160);
            }
            // Each entry matches itself from each of its sides.
            for (FamousClimb.Side s : c.sides) {
                FamousClimbMatcher.Match m = FamousClimbMatcher.match(list,
                        s.lat, s.lon, c.topLat, c.topLon);
                assertNotNull(c.name + " does not match itself", m);
                assertEquals(c.id, m.climb.id);
            }
        }
    }
}

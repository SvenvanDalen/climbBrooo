package nl.paree.climbpro.domain.maintenance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.data.maintenance.TorqueValue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class TorqueReferenceTest {

    private static TorqueReference.Spec find(String part) {
        for (TorqueReference.Spec s : TorqueReference.all()) {
            if (s.part.equals(part)) return s;
        }
        throw new AssertionError("missing " + part);
    }

    @Test
    public void referenceTable_isCompleteAndSane() {
        List<TorqueReference.Spec> all = TorqueReference.all();
        assertEquals(14, all.size());
        Set<String> parts = new HashSet<>();
        for (TorqueReference.Spec s : all) {
            assertTrue(s.part, s.minNm > 0);
            assertTrue(s.part, s.maxNm >= s.minNm);
            assertTrue(s.part, s.maxNm <= TorqueReference.MAX_NM);
            assertTrue("duplicate " + s.part, parts.add(s.part));
        }
        assertEquals("Stuurpen stuurklem", all.get(0).part);
    }

    @Test
    public void referenceTable_containsIssueValues() {
        assertEquals("4–6 Nm", find("Stuurpen stuurklem").rangeLabel());
        assertEquals("5–6 Nm", find("Stuurpen balhoofdklem").rangeLabel());
        assertEquals("4–6 Nm", find("Zadelpenklem (carbon)").rangeLabel());
        assertEquals("5–7 Nm", find("Zadelpenklem (aluminium)").rangeLabel());
        assertEquals("40 Nm", find("Cassette-lockring").rangeLabel());
        assertEquals("35–50 Nm", find("Trapas BSA").rangeLabel());
        assertEquals("2–4 Nm", find("Remschijf 6-bouts").rangeLabel());
    }

    @Test(expected = UnsupportedOperationException.class)
    public void referenceTable_isUnmodifiable() {
        TorqueReference.all().clear();
    }

    @Test
    public void formatNm_usesDutchDecimalComma() {
        assertEquals("5", TorqueReference.formatNm(5));
        assertEquals("2,5", TorqueReference.formatNm(2.5));
        assertEquals("12,3", TorqueReference.formatNm(12.34));
        assertEquals("6", TorqueReference.formatNm(5.96));
        assertEquals("2,5–3 Nm", TorqueReference.formatRange(2.5, 3));
        assertEquals("40 Nm", TorqueReference.formatRange(40, 40));
    }

    @Test
    public void parseNm_acceptsCommaDotAndUnit() {
        assertEquals(5.5, TorqueReference.parseNm("5,5"), 1e-9);
        assertEquals(5.5, TorqueReference.parseNm("5.5"), 1e-9);
        assertEquals(5.0, TorqueReference.parseNm("5 Nm"), 1e-9);
        assertEquals(12.0, TorqueReference.parseNm(" 12 nm "), 1e-9);
        assertEquals(4.3, TorqueReference.parseNm("4,26"), 1e-9);
        assertEquals(200.0, TorqueReference.parseNm("200"), 1e-9);
    }

    @Test
    public void parseNm_rejectsInvalid() {
        String[] bad = {null, "", "   ", "abc", "0", "-3", "250", "0,04", "Nm", "5,5,5", "NaN",
                "Infinity"};
        for (String s : bad) {
            assertTrue("should reject " + s, Double.isNaN(TorqueReference.parseNm(s)));
        }
    }

    @Test
    public void sorted_labelledBikesFirstThenPart_caseInsensitive() {
        List<TorqueValue> in = new ArrayList<>(Arrays.asList(
                new TorqueValue("1", null, "Pedalen", 35, null),
                new TorqueValue("2", "racefiets", "Zadelpenklem", 5, null),
                null,
                new TorqueValue("3", "Gravel", "stuurpen", 5, null),
                new TorqueValue("4", "  ", "Bidonhouder", 2, null),
                new TorqueValue("5", "Racefiets", "Bidonhouder", 2, null)));
        List<TorqueValue> out = TorqueReference.sorted(in);
        StringBuilder ids = new StringBuilder();
        for (TorqueValue v : out) ids.append(v.id);
        assertEquals("35241", ids.toString());
        assertEquals(6, in.size()); // input untouched
    }

    @Test
    public void sorted_handlesNullList() {
        assertTrue(TorqueReference.sorted(null).isEmpty());
    }

    @Test
    public void label_includesBikeOnlyWhenPresent() {
        assertEquals("Racefiets · Stuurpen stuurklem", TorqueReference.label(
                new TorqueValue("a", " Racefiets ", "Stuurpen stuurklem", 5, null)));
        assertEquals("Pedalen", TorqueReference.label(
                new TorqueValue("b", "", "Pedalen", 35, null)));
    }

    @Test
    public void bikeLabels_distinctTrimmedSorted() {
        List<TorqueValue> in = Arrays.asList(
                new TorqueValue("1", "Racefiets", "a", 1, null),
                new TorqueValue("2", " racefiets ", "b", 1, null),
                new TorqueValue("3", "Gravel", "c", 1, null),
                new TorqueValue("4", null, "d", 1, null),
                new TorqueValue("5", "  ", "e", 1, null));
        assertEquals(Arrays.asList("Gravel", "Racefiets"), TorqueReference.bikeLabels(in));
    }
}

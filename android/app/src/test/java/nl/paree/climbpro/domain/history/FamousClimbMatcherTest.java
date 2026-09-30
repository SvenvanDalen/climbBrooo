package nl.paree.climbpro.domain.history;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

public class FamousClimbMatcherTest {

    private static final FamousClimb VENTOUX = new FamousClimb("mont-ventoux", "Mont Ventoux",
            Arrays.asList("mont ventoux", "ventoux"), 44.1737, 5.2787,
            Arrays.asList(new FamousClimb.Side("Bédoin", 44.1247, 5.1780),
                    new FamousClimb.Side("Malaucène", 44.1740, 5.1330)),
            Collections.singletonList("Reus van de Provence"));

    private static final FamousClimb OUDE_KWAREMONT = new FamousClimb("oude-kwaremont",
            "Oude Kwaremont", Arrays.asList("oude kwaremont", "kwaremont"), 50.7745, 3.5330,
            Collections.singletonList(new FamousClimb.Side("Kluisbergen", 50.7815, 3.5090)),
            Collections.singletonList("Langste kasseihelling"));

    private static final FamousClimb PATERBERG = new FamousClimb("paterberg", "Paterberg",
            Collections.singletonList("paterberg"), 50.7690, 3.5555,
            Collections.singletonList(new FamousClimb.Side("Kluisbergen", 50.7730, 3.5540)),
            Collections.singletonList("Laatste helling"));

    private static final FamousClimb MUUR = new FamousClimb("muur", "Muur van Geraardsbergen",
            Arrays.asList("muur van geraardsbergen", "kapelmuur"), 50.7669, 3.8735,
            Collections.singletonList(new FamousClimb.Side("Geraardsbergen", 50.7730, 3.8820)),
            Collections.singletonList("Kapel op de Oudenberg"));

    private static final List<FamousClimb> DATA =
            Arrays.asList(VENTOUX, OUDE_KWAREMONT, PATERBERG, MUUR);

    @Test
    public void startAndTopNearMatchesTheRightSide() {
        FamousClimbMatcher.Match m = FamousClimbMatcher.match(DATA,
                44.1260, 5.1800, 44.1730, 5.2780, (String) null);
        assertNotNull(m);
        assertEquals("mont-ventoux", m.climb.id);
        assertEquals(FamousClimbMatcher.Kind.START_AND_TOP, m.kind);
        assertEquals("Bédoin", m.sideLabel);
    }

    @Test
    public void otherSidePicksItsOwnStart() {
        FamousClimbMatcher.Match m = FamousClimbMatcher.match(DATA,
                44.1745, 5.1340, 44.1735, 5.2785);
        assertNotNull(m);
        assertEquals("Malaucène", m.sideLabel);
    }

    @Test
    public void topOnlyMatchWhenStartIsUnknownSide() {
        // Up from Sault, which is not in this test dataset: summit still identifies it.
        FamousClimbMatcher.Match m = FamousClimbMatcher.match(DATA,
                44.0910, 5.4090, 44.1737, 5.2787);
        assertNotNull(m);
        assertEquals("mont-ventoux", m.climb.id);
        assertEquals(FamousClimbMatcher.Kind.TOP, m.kind);
        assertNull(m.sideLabel);
    }

    @Test
    public void nearbyShortHillsAreNotConfused() {
        FamousClimbMatcher.Match kwaremont = FamousClimbMatcher.match(DATA,
                50.7812, 3.5100, 50.7748, 3.5320);
        assertEquals("oude-kwaremont", kwaremont.climb.id);
        FamousClimbMatcher.Match pater = FamousClimbMatcher.match(DATA,
                50.7728, 3.5541, 50.7691, 3.5553);
        assertEquals("paterberg", pater.climb.id);
    }

    @Test
    public void unrelatedClimbDoesNotMatch() {
        // A climb in the Ardennes, far from every entry, with a generic name.
        assertNull(FamousClimbMatcher.match(DATA, 50.40, 5.90, 50.41, 5.91, "Klim 3"));
    }

    @Test
    public void topTooFarFromSummitDoesNotMatchByLocation() {
        // Chalet Reynard (~6 km below the summit): a partial climb, not "the" Ventoux.
        assertNull(FamousClimbMatcher.match(DATA, 44.1247, 5.1780, 44.1370, 5.2440));
    }

    @Test
    public void nameFallbackWhenCoordinatesAreMissing() {
        FamousClimbMatcher.Match m = FamousClimbMatcher.match(DATA,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, "Mont Ventoux via Bédoin");
        assertNotNull(m);
        assertEquals("mont-ventoux", m.climb.id);
        assertEquals(FamousClimbMatcher.Kind.NAME, m.kind);
    }

    @Test
    public void nameFallbackIgnoresAccentsCaseAndPunctuation() {
        FamousClimbMatcher.Match m = FamousClimbMatcher.match(DATA,
                0, 0, 0, 0, null, "  KAPELMUUR!!  ");
        assertNotNull(m);
        assertEquals("muur", m.climb.id);
    }

    @Test
    public void nameFallbackNeedsWholeWords() {
        assertNull(FamousClimbMatcher.match(DATA, Double.NaN, Double.NaN, Double.NaN, Double.NaN,
                "Paterbergstraat"));
    }

    @Test
    public void nameFallbackRejectedWhenFarAway() {
        // A local climb the user happened to name "Ventoux" in the Netherlands.
        assertNull(FamousClimbMatcher.match(DATA, 50.85, 5.83, 50.86, 5.84, "Mini Ventoux"));
    }

    @Test
    public void nameFallbackAcceptedWhenNearButLocationTooLoose() {
        // Nearby (within 30 km), but neither top nor start matches: the name decides.
        FamousClimbMatcher.Match m = FamousClimbMatcher.match(DATA,
                44.12, 5.18, 44.14, 5.24, "Ventoux (Bédoin)");
        assertNotNull(m);
        assertEquals(FamousClimbMatcher.Kind.NAME, m.kind);
    }

    @Test
    public void longestAliasWins() {
        FamousClimb generic = new FamousClimb("muur-generic", "Muur", Collections.singletonList("muur"),
                50.77, 3.87, new ArrayList<>(), Collections.singletonList("x"));
        List<FamousClimb> data = Arrays.asList(generic, MUUR);
        FamousClimbMatcher.Match m = FamousClimbMatcher.match(data,
                Double.NaN, Double.NaN, Double.NaN, Double.NaN, "De Muur van Geraardsbergen");
        assertEquals("muur", m.climb.id);
    }

    @Test
    public void emptyOrNullDatasetMatchesNothing() {
        assertNull(FamousClimbMatcher.match(null, 44.12, 5.18, 44.17, 5.28, "Ventoux"));
        assertNull(FamousClimbMatcher.match(new ArrayList<>(), 44.12, 5.18, 44.17, 5.28));
    }

    @Test
    public void entryWithoutSidesMatchesOnTop() {
        FamousClimb noSides = new FamousClimb("x", "X", Collections.singletonList("x"),
                46.0, 7.0, new ArrayList<>(), Collections.singletonList("fact"));
        FamousClimbMatcher.Match m = FamousClimbMatcher.match(Collections.singletonList(noSides),
                45.95, 6.95, 46.003, 7.002);
        assertNotNull(m);
        assertEquals(FamousClimbMatcher.Kind.TOP, m.kind);
    }

    @Test
    public void normalizeStripsAccentsAndPunctuation() {
        assertEquals("col du telegraphe", FamousClimbMatcher.normalize("Col du Télégraphe"));
        assertEquals("alpe d huez", FamousClimbMatcher.normalize("Alpe d'Huez"));
        assertEquals("", FamousClimbMatcher.normalize(null));
    }
}

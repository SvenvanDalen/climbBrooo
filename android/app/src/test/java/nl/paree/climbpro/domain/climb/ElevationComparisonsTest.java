package nl.paree.climbpro.domain.climb;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

public class ElevationComparisonsTest {

    @Test public void nothingForZeroOrNegative() {
        assertNull(ElevationComparisons.describe(0));
        assertNull(ElevationComparisons.describe(-5));
    }

    @Test public void belowSmallestLandmarkIsPercentage() {
        assertEquals("36% van de Domtoren", ElevationComparisons.describe(40));   // 40/112
    }

    @Test public void picksLargestLandmarkNotExceedingTotal() {
        assertEquals("1,8× de Euromast", ElevationComparisons.describe(329));     // 329/185
        assertEquals("2,1× de Eiffeltoren", ElevationComparisons.describe(700));  // 700/330
        assertEquals("1,2× de Burj Khalifa", ElevationComparisons.describe(988)); // 988/828
    }

    @Test public void mountainsHaveNoArticle() {
        assertEquals("1,6× Mont Ventoux", ElevationComparisons.describe(2_500));  // 2500/1610
    }

    @Test public void halfOfABigClimb() {
        assertEquals("een halve Mont Ventoux", ElevationComparisons.describe(800)); // within 5% of 805
    }

    @Test public void oneDecimalRoundsUpToWholeValue() {
        assertEquals("3,0× Mont Ventoux", ElevationComparisons.describe(4_766));  // 2,96
    }

    @Test public void largeRatiosAreWholeNumbers() {
        assertEquals("11× Mount Everest", ElevationComparisons.describe(100_000)); // 11,3
    }
}

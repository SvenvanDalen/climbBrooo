package nl.paree.climbpro.domain.weather;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import nl.paree.climbpro.domain.weather.ClimateNormals.DayPart;

import org.junit.Test;

import java.util.Arrays;

public class BestTimeScorerTest {

    private static ClimateNormals.Cell cell(double temp, double wind, double rain) {
        return ClimateNormals.Cell.of(temp, wind, rain, 0, 0, 100);
    }

    /** Every month/day-part the same unless overridden. */
    private static ClimateNormals.Cell[][] grid(ClimateNormals.Cell fill) {
        ClimateNormals.Cell[][] g = new ClimateNormals.Cell[12][DayPart.values().length];
        for (ClimateNormals.Cell[] row : g) Arrays.fill(row, fill);
        return g;
    }

    @Test public void comfortableDryCalmCellScoresFull() {
        assertEquals(100.0, BestTimeScorer.score(cell(17, 8, 0), Double.NaN), 1e-9);
    }

    @Test public void coldWindyAndWetEachCostPoints() {
        double ideal = BestTimeScorer.score(cell(17, 8, 0), Double.NaN);
        assertTrue(BestTimeScorer.score(cell(4, 8, 0), Double.NaN) < ideal);
        assertTrue(BestTimeScorer.score(cell(30, 8, 0), Double.NaN) < ideal);
        assertTrue(BestTimeScorer.score(cell(17, 30, 0), Double.NaN) < ideal);
        assertTrue(BestTimeScorer.score(cell(17, 8, 0.5), Double.NaN) < ideal);
    }

    @Test public void comfortBandEdgesAreNotPenalised() {
        assertEquals(100.0, BestTimeScorer.score(cell(12, 8, 0), Double.NaN), 1e-9);
        assertEquals(100.0, BestTimeScorer.score(cell(22, 8, 0), Double.NaN), 1e-9);
    }

    @Test public void headwindCostsAndTailwindDoesNotHelp() {
        ClimateNormals.Cell east = ClimateNormals.Cell.of(17, 10, 0, 10, 0, 100); // from 90°
        assertTrue(BestTimeScorer.score(east, 90) < BestTimeScorer.score(east, Double.NaN));
        assertEquals(BestTimeScorer.score(east, Double.NaN), BestTimeScorer.score(east, 270), 1e-9);
    }

    @Test public void scoreNeverNegativeAndEmptyCellIsNaN() {
        assertEquals(0.0, BestTimeScorer.score(cell(-20, 80, 1), Double.NaN), 1e-9);
        assertTrue(Double.isNaN(BestTimeScorer.score(ClimateNormals.Cell.EMPTY, 0)));
    }

    @Test public void picksSummerMonthsAndTheMorning() {
        ClimateNormals.Cell[][] g = grid(cell(3, 20, 0.5));
        for (int m = 5; m <= 8; m++) { // June..September (0-based index)
            g[m][DayPart.OCHTEND.ordinal()] = cell(17, 8, 0.1);
            g[m][DayPart.MIDDAG.ordinal()] = cell(27, 12, 0.2);
        }
        BestTimeScorer.Result r = BestTimeScorer.evaluate(new ClimateNormals(g), Double.NaN);
        assertEquals(Arrays.asList(6, 7, 8, 9), r.bestMonths);
        assertEquals(DayPart.OCHTEND, r.bestDayPart);
        assertEquals("juni–september, ochtend", r.summary());
        assertEquals(DayPart.OCHTEND, r.months[5].bestPart);
    }

    @Test public void winterRangeWrapsAroundNewYear() {
        ClimateNormals.Cell[][] g = grid(cell(35, 10, 0));
        for (int m : new int[] {10, 11, 0, 1}) g[m][DayPart.MIDDAG.ordinal()] = cell(18, 8, 0);
        BestTimeScorer.Result r = BestTimeScorer.evaluate(new ClimateNormals(g), Double.NaN);
        assertEquals("november–februari, middag", r.summary());
    }

    @Test public void separateRangesAreListed() {
        ClimateNormals.Cell[][] g = grid(cell(0, 30, 0.6));
        for (int m : new int[] {3, 4, 8}) g[m][DayPart.NAMIDDAG.ordinal()] = cell(16, 8, 0);
        BestTimeScorer.Result r = BestTimeScorer.evaluate(new ClimateNormals(g), Double.NaN);
        assertEquals("april–mei en september, namiddag", r.summary());
    }

    @Test public void uniformClimateIsWholeYear() {
        BestTimeScorer.Result r =
                BestTimeScorer.evaluate(new ClimateNormals(grid(cell(17, 8, 0))), Double.NaN);
        assertEquals(12, r.bestMonths.size());
        assertTrue(r.summary().startsWith("het hele jaar, "));
    }

    @Test public void noDataGivesNoResult() {
        assertNull(BestTimeScorer.evaluate(
                new ClimateNormals(grid(ClimateNormals.Cell.EMPTY)), 0));
    }

    @Test public void monthTableHasOneLinePerMonthAndMarksBest() {
        ClimateNormals.Cell[][] g = grid(cell(3, 20, 0.5));
        g[6][DayPart.OCHTEND.ordinal()] = cell(17, 8, 0.25);
        BestTimeScorer.Result r = BestTimeScorer.evaluate(new ClimateNormals(g), Double.NaN);
        String[] lines = r.monthTable().split("\n");
        assertEquals(12, lines.length);
        assertTrue(lines[6], lines[6].startsWith("★ jul"));
        assertTrue(lines[6], lines[6].contains("17°"));
        assertTrue(lines[6], lines[6].contains("25%"));
        assertTrue(lines[0], lines[0].startsWith("  jan"));
    }
}

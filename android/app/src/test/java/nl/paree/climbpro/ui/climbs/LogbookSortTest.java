package nl.paree.climbpro.ui.climbs;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class LogbookSortTest {

    private static ClimbLogbookViewModel.LogbookRow row(String id, long last, Double rating) {
        return new ClimbLogbookViewModel.LogbookRow(id, id, 600, 1, last, "r", 0, rating);
    }

    private static String ids(List<ClimbLogbookViewModel.LogbookRow> rows) {
        StringBuilder sb = new StringBuilder();
        for (ClimbLogbookViewModel.LogbookRow r : rows) sb.append(r.climbId);
        return sb.toString();
    }

    private final List<ClimbLogbookViewModel.LogbookRow> rows = Arrays.asList(
            row("a", 100, null),
            row("b", 300, 3.0),
            row("c", 200, 4.5),
            row("d", 400, null),
            row("e", 500, 3.0));

    @Test public void defaultSortIsMostRecentFirst() {
        assertEquals("edbca", ids(ClimbLogbookViewModel.sortRows(rows, false)));
    }

    @Test public void ratingSortBestFirstUnratedLastTiesByRecency() {
        assertEquals("cebda", ids(ClimbLogbookViewModel.sortRows(rows, true)));
    }

    @Test public void sortDoesNotMutateInput() {
        ClimbLogbookViewModel.sortRows(rows, true);
        assertEquals("abcde", ids(rows));
    }
}

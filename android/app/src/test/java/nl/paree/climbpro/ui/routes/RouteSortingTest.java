package nl.paree.climbpro.ui.routes;

import static org.junit.Assert.assertEquals;

import nl.paree.climbpro.data.route.RouteCatalogEntry;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RouteSortingTest {

    private RouteCatalogEntry entry(String id, String name, long importedAt) {
        RouteCatalogEntry e = new RouteCatalogEntry();
        e.routeId = id;
        e.name = name;
        e.importedAtMs = importedAt;
        return e;
    }

    private List<String> ids(List<RouteCatalogEntry> in) {
        List<String> out = new ArrayList<>();
        for (RouteCatalogEntry e : in) out.add(e.routeId);
        return out;
    }

    private List<RouteCatalogEntry> sample() {
        return new ArrayList<>(Arrays.asList(
                entry("b", "Bravo", 200L),
                entry("a", "Alpha", 100L),
                entry("c", "Charlie", 300L)));
    }

    @Test
    public void importAsc_newestAtBottom() {
        assertEquals(Arrays.asList("a", "b", "c"),
                ids(RouteSorting.sort(sample(), RouteSorting.SORT_IMPORT_ASC)));
    }

    @Test
    public void importDesc_newestAtTop() {
        assertEquals(Arrays.asList("c", "b", "a"),
                ids(RouteSorting.sort(sample(), RouteSorting.SORT_IMPORT_DESC)));
    }

    @Test
    public void nameAsc_alphabetical() {
        assertEquals(Arrays.asList("a", "b", "c"),
                ids(RouteSorting.sort(sample(), RouteSorting.SORT_NAME_ASC)));
    }

    @Test
    public void nameAsc_prefersUserDisplayName() {
        List<RouteCatalogEntry> in = sample();
        in.get(0).userDisplayName = "Aaa"; // entry "b" gets a display name that sorts first
        assertEquals("b", ids(RouteSorting.sort(in, RouteSorting.SORT_NAME_ASC)).get(0));
    }

    @Test
    public void unknownMode_fallsBackToImportAsc() {
        assertEquals(Arrays.asList("a", "b", "c"),
                ids(RouteSorting.sort(sample(), 999)));
    }
}

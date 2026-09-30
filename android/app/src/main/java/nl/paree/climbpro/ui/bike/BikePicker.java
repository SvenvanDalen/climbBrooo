package nl.paree.climbpro.ui.bike;

import android.widget.ArrayAdapter;
import android.widget.Spinner;

import nl.paree.climbpro.data.bike.Bike;
import nl.paree.climbpro.data.bike.BikeCostLog;

import java.util.ArrayList;
import java.util.List;

/**
 * Spinner for linking something (a maintenance part, the tyre-pressure log) to a garage bike
 * (issue #187). Position 0 is "not linked"; the others are the garage's bikes in order.
 */
public final class BikePicker {

    private BikePicker() {}

    /** Fills {@code spinner} and selects {@code selectedId} (none when unknown or null). */
    public static void bind(Spinner spinner, BikeCostLog garage, String noneLabel,
                            String selectedId) {
        List<String> labels = new ArrayList<>();
        labels.add(noneLabel);
        int selected = 0;
        List<Bike> bikes = bikes(garage);
        for (int i = 0; i < bikes.size(); i++) {
            Bike b = bikes.get(i);
            labels.add(b.retiredEpochSec > 0 ? b.name + " (uit gebruik)" : b.name);
            if (b.id.equals(selectedId)) selected = i + 1;
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(spinner.getContext(),
                android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(selected);
    }

    /** The chosen bike's id, or null for "not linked". */
    public static String selectedId(Spinner spinner, BikeCostLog garage) {
        int pos = spinner.getSelectedItemPosition();
        List<Bike> bikes = bikes(garage);
        return pos >= 1 && pos <= bikes.size() ? bikes.get(pos - 1).id : null;
    }

    /** Name of the bike with {@code id}, or null when not linked / no longer in the garage. */
    public static String nameOf(BikeCostLog garage, String id) {
        if (id == null) return null;
        for (Bike b : bikes(garage)) {
            if (id.equals(b.id)) return b.name;
        }
        return null;
    }

    private static List<Bike> bikes(BikeCostLog garage) {
        List<Bike> out = new ArrayList<>();
        if (garage == null || garage.bikes == null) return out;
        for (Bike b : garage.bikes) {
            if (b != null && b.id != null) out.add(b);
        }
        return out;
    }
}

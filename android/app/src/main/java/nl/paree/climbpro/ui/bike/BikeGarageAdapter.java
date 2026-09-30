package nl.paree.climbpro.ui.bike;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.bike.Bike;
import nl.paree.climbpro.domain.bike.BikeCostCalculator;
import nl.paree.climbpro.domain.bike.BikeGarage;

import java.util.ArrayList;
import java.util.List;

/** One card per garage bike: badges (actief/binnenfiets), specs and its assigned rides. */
public final class BikeGarageAdapter extends RecyclerView.Adapter<BikeGarageAdapter.RowVH> {

    public interface Listener {
        void onBikeClicked(Bike bike);
    }

    private final List<Bike> rows = new ArrayList<>();
    private final Listener listener;
    private BikeGarageViewModel.State state;

    public BikeGarageAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(BikeGarageViewModel.State s) {
        state = s;
        rows.clear();
        for (Bike b : s.garage.bikes) {
            if (b != null) rows.add(b);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public RowVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_bike_garage, parent, false);
        return new RowVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RowVH h, int position) {
        Bike b = rows.get(position);
        h.name.setText(b.name);

        List<String> badges = new ArrayList<>();
        Bike active = BikeGarage.activeBike(state.garage);
        Bike indoor = BikeGarage.indoorBike(state.garage);
        if (active != null && active.id.equals(b.id)) badges.add("Actief");
        if (indoor != null && indoor.id.equals(b.id)) badges.add("Binnenfiets");
        if (b.retiredEpochSec > 0) badges.add("Uit gebruik");
        h.badges.setText(String.join("  •  ", badges));
        h.badges.setVisibility(badges.isEmpty() ? View.GONE : View.VISIBLE);

        h.spec.setText(BikeGarage.specLine(b));

        long[] t = state.totals.get(b.id);
        String rides = t != null
                ? t[0] + (t[0] == 1 ? " rit" : " ritten") + " · "
                        + BikeCostCalculator.kmText(t[1])
                : "Nog geen ritten";
        rides += b.stravaGearId != null ? " · Strava-fiets " + b.stravaGearId
                                        : " · niet aan Strava gekoppeld";
        h.rides.setText(rides);

        h.itemView.setOnClickListener(v -> listener.onBikeClicked(b));
    }

    @Override
    public int getItemCount() { return rows.size(); }

    static final class RowVH extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView badges;
        final TextView spec;
        final TextView rides;

        RowVH(View v) {
            super(v);
            name = v.findViewById(R.id.name);
            badges = v.findViewById(R.id.badges);
            spec = v.findViewById(R.id.spec);
            rides = v.findViewById(R.id.rides);
        }
    }
}

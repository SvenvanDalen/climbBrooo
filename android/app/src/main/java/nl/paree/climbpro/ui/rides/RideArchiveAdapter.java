package nl.paree.climbpro.ui.rides;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import nl.paree.climbpro.R;
import nl.paree.climbpro.data.ride.StoredRide;
import nl.paree.climbpro.domain.ride.RideCategoryLabel;
import nl.paree.climbpro.ui.rides.RideArchiveViewModel.Row;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Flat list of archived rides, newest first, each with its automatic category. */
public final class RideArchiveAdapter extends RecyclerView.Adapter<RideArchiveAdapter.RowVH> {

    private final List<Row> rows = new ArrayList<>();
    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("EEE d MMM yyyy", Locale.getDefault());

    public void submit(List<Row> newRows) {
        rows.clear();
        if (newRows != null) rows.addAll(newRows);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public RowVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_ride, parent, false);
        return new RowVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RowVH holder, int position) {
        Row row = rows.get(position);
        StoredRide r = row.ride;
        holder.name.setText(r.name != null && !r.name.isEmpty() ? r.name : "Rit");
        holder.category.setText(RideCategoryLabel.forCategory(row.category));

        String date = r.startEpochSec > 0
                ? dateFormat.format(new Date(r.startEpochSec * 1000L)) : "onbekende datum";
        holder.stats.setText(String.format(Locale.getDefault(),
                "%s  •  %.1f km  •  %d hm  •  %.1f km/u",
                date, r.distanceM / 1000f, Math.round(r.elevationGainM), r.avgSpeedMps * 3.6f));
    }

    @Override
    public int getItemCount() { return rows.size(); }

    static final class RowVH extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView category;
        final TextView stats;
        RowVH(@NonNull View v) {
            super(v);
            name = v.findViewById(R.id.name);
            category = v.findViewById(R.id.category);
            stats = v.findViewById(R.id.stats);
        }
    }
}

package nl.paree.climbpro.ui.climbs;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import nl.paree.climbpro.R;
import nl.paree.climbpro.domain.climb.RideFatigueCurveCalculator.FatiguePoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Simple list of {@link FatiguePoint} rows, backing the detail list under the chart. */
public final class RideFatiguePointAdapter extends RecyclerView.Adapter<RideFatiguePointAdapter.VH> {

    private final List<FatiguePoint> points = new ArrayList<>();

    public void submit(List<FatiguePoint> newPoints) {
        points.clear();
        if (newPoints != null) points.addAll(newPoints);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_ride_fatigue_point, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH holder, int position) {
        FatiguePoint p = points.get(position);
        holder.name.setText(String.format(Locale.getDefault(), "%d. %s", p.ordinal, p.label));
        holder.stats.setText(String.format(Locale.getDefault(),
                "%s  •  %d m/u VAM  •  %.0f%% van klim 1",
                formatTime(p.elapsedSec), Math.round(p.vamMPerHour), p.relativeToFirstPct));
    }

    @Override
    public int getItemCount() { return points.size(); }

    private static String formatTime(int sec) {
        int m = sec / 60, s = sec % 60;
        return String.format(Locale.getDefault(), "%d:%02d", m, s);
    }

    static final class VH extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView stats;
        VH(@NonNull View v) {
            super(v);
            name = v.findViewById(R.id.name);
            stats = v.findViewById(R.id.stats);
        }
    }
}

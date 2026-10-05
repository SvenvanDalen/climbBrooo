package nl.paree.climbpro.ui.rides;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import nl.paree.climbpro.R;
import nl.paree.climbpro.domain.ride.RideComparison;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One row per kilometre of the ride comparison (issue #199). */
final class RideCompareAdapter extends RecyclerView.Adapter<RideCompareAdapter.KmVH> {

    private final List<RideComparison.Km> rows = new ArrayList<>();

    void submit(List<RideComparison.Km> newRows) {
        rows.clear();
        if (newRows != null) rows.addAll(newRows);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public KmVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new KmVH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_ride_compare_km, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull KmVH h, int position) {
        RideComparison.Km k = rows.get(position);
        h.km.setText(k.lengthM < 999
                ? String.format(Locale.getDefault(), "%d (%.1f)", k.km, k.lengthM / 1000)
                : String.valueOf(k.km));
        h.time.setText(duration(k.secA) + " / " + duration(k.secB));
        h.speed.setText(String.format(Locale.getDefault(), "%.1f / %.1f", k.speedA, k.speedB));
        h.hr.setText(bpm(k.hrA) + " / " + bpm(k.hrB));
        h.watts.setText(bpm(k.wattsA) + " / " + bpm(k.wattsB));
        h.cadence.setText(bpm(k.cadenceA) + " / " + bpm(k.cadenceB));
        h.delta.setText(signedDuration(k.cumulativeDeltaSec));
        // Ahead/behind use the palette's status colors (blue/orange when colorblind, #258).
        h.delta.setTextColor(k.cumulativeDeltaSec < 0
                ? nl.paree.climbpro.ui.climbs.SegmentColorPalette.statusOk()
                : k.cumulativeDeltaSec > 0
                        ? nl.paree.climbpro.ui.climbs.SegmentColorPalette.statusBad()
                        : ContextCompat.getColor(h.delta.getContext(), R.color.color_text_secondary));
    }

    @Override
    public int getItemCount() { return rows.size(); }

    static String duration(int sec) {
        return String.format(Locale.getDefault(), "%d:%02d", sec / 60, Math.abs(sec % 60));
    }

    /** "+0:25" when B is behind A, "-1:10" when B is ahead, "0:00" when level. */
    static String signedDuration(int sec) {
        if (sec == 0) return "0:00";
        return (sec > 0 ? "+" : "-") + duration(Math.abs(sec));
    }

    private static String bpm(double hr) {
        return Double.isNaN(hr) ? "–" : String.valueOf(Math.round(hr));
    }

    static final class KmVH extends RecyclerView.ViewHolder {
        final TextView km;
        final TextView time;
        final TextView speed;
        final TextView hr;
        final TextView watts;
        final TextView cadence;
        final TextView delta;

        KmVH(@NonNull View v) {
            super(v);
            km = v.findViewById(R.id.km);
            time = v.findViewById(R.id.time);
            speed = v.findViewById(R.id.speed);
            hr = v.findViewById(R.id.hr);
            watts = v.findViewById(R.id.watts);
            cadence = v.findViewById(R.id.cadence);
            delta = v.findViewById(R.id.delta);
        }
    }
}

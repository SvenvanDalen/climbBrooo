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
import nl.paree.climbpro.domain.bike.BikeCostCalculator.Summary;
import nl.paree.climbpro.domain.bike.EuroAmount;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** One card per bike: cost per km, km + total, purchase/parts split and the km source. */
public final class BikeCostAdapter extends RecyclerView.Adapter<BikeCostAdapter.RowVH> {

    public interface Listener {
        void onBikeClicked(Bike bike);
    }

    private final List<Summary> rows = new ArrayList<>();
    private final Listener listener;
    private final SimpleDateFormat dateFormat =
            new SimpleDateFormat("d MMM yyyy", new Locale("nl", "NL"));

    public BikeCostAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submit(List<Summary> summaries) {
        rows.clear();
        if (summaries != null) rows.addAll(summaries);
        notifyDataSetChanged();
    }

    public String formatDate(long epochSec) {
        return dateFormat.format(new Date(epochSec * 1000L));
    }

    @NonNull
    @Override
    public RowVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_bike_cost, parent, false);
        return new RowVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RowVH holder, int position) {
        Summary s = rows.get(position);
        Bike b = s.bike;
        holder.name.setText(b.retiredEpochSec > 0 ? b.name + " (uit gebruik)" : b.name);
        holder.rate.setText(BikeCostCalculator.rateText(s));
        holder.totals.setText(BikeCostCalculator.kmText(s.totalMeters)
                + "  •  totaal " + EuroAmount.format(s.totalCents));
        holder.breakdown.setText(BikeCostCalculator.breakdownText(s));
        holder.detail.setText(kmSourceText(b));
        holder.itemView.setOnClickListener(v -> listener.onBikeClicked(b));
    }

    /** E.g. "Ritten vanaf 3 mrt. 2026  •  +500 km handmatig  •  incl. indoorritten". */
    private String kmSourceText(Bike b) {
        List<String> parts = new ArrayList<>(3);
        boolean archive = b.countArchiveRides && b.sinceEpochSec > 0;
        if (archive) {
            String text = "Ritten vanaf " + formatDate(b.sinceEpochSec);
            if (b.retiredEpochSec > 0) text += " tot " + formatDate(b.retiredEpochSec);
            parts.add(text);
        } else {
            parts.add("Geen ritten uit het archief");
        }
        if (b.extraKm > 0) parts.add("+" + EuroAmount.groupThousands(b.extraKm) + " km handmatig");
        if (archive && b.includeVirtualRides) parts.add("incl. indoorritten");
        return String.join("  •  ", parts);
    }

    @Override
    public int getItemCount() { return rows.size(); }

    static final class RowVH extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView rate;
        final TextView totals;
        final TextView breakdown;
        final TextView detail;
        RowVH(@NonNull View v) {
            super(v);
            name = v.findViewById(R.id.name);
            rate = v.findViewById(R.id.rate);
            totals = v.findViewById(R.id.totals);
            breakdown = v.findViewById(R.id.breakdown);
            detail = v.findViewById(R.id.detail);
        }
    }
}

package nl.paree.climbpro.ui.climbs;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import nl.paree.climbpro.R;
import nl.paree.climbpro.ui.climbs.UnfinishedClimbsViewModel.Row;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Flat list of never-completed climbs, most recently attempted first. */
public final class UnfinishedClimbsAdapter extends RecyclerView.Adapter<UnfinishedClimbsAdapter.RowVH> {

    public interface OnClick { void onRow(Row row); }

    private final List<Row> rows = new ArrayList<>();
    private final OnClick onClick;
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("d MMM yyyy", Locale.getDefault());

    public UnfinishedClimbsAdapter(OnClick onClick) { this.onClick = onClick; }

    public void submit(List<Row> newRows) {
        rows.clear();
        rows.addAll(newRows);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public RowVH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_unfinished_climb, parent, false);
        return new RowVH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RowVH holder, int position) {
        Row row = rows.get(position);
        holder.name.setText(row.displayName);

        String date = row.lastAttemptDateSec > 0
                ? dateFormat.format(new Date(row.lastAttemptDateSec * 1000L)) : "onbekende datum";
        String progress = row.lengthM > 0
                ? String.format(Locale.getDefault(), "%d%% van %d m",
                        Math.min(100, Math.round(100f * row.bestDistanceCoveredM / row.lengthM)),
                        row.lengthM)
                : row.bestDistanceCoveredM + " m afgelegd";
        holder.stats.setText(String.format(Locale.getDefault(),
                "%s  •  %s  •  %d poging(en)", date, progress, row.attemptCount));

        holder.itemView.setOnClickListener(v -> {
            if (onClick != null) onClick.onRow(row);
        });
    }

    @Override
    public int getItemCount() { return rows.size(); }

    static final class RowVH extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView stats;
        RowVH(@NonNull View v) {
            super(v);
            name = v.findViewById(R.id.name);
            stats = v.findViewById(R.id.stats);
        }
    }
}

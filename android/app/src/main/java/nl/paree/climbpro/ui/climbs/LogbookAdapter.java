package nl.paree.climbpro.ui.climbs;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import nl.paree.climbpro.R;
import nl.paree.climbpro.ui.climbs.ClimbLogbookViewModel.LogbookRow;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class LogbookAdapter extends RecyclerView.Adapter<LogbookAdapter.VH> {

    public interface OnClick { void onClimb(LogbookRow row); }

    private final List<LogbookRow> items = new ArrayList<>();
    private final OnClick onClick;

    public LogbookAdapter(OnClick onClick) { this.onClick = onClick; }

    public void submit(List<LogbookRow> rows) {
        items.clear();
        items.addAll(rows);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_logbook_climb, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        LogbookRow row = items.get(position);
        h.name.setText(row.displayName);
        String stats = String.format(Locale.getDefault(),
                "PR %s  •  %d pogingen", formatTime(row.prSec), row.attemptCount);
        String badge = nl.paree.climbpro.domain.climb.ClimbRating.badge(row.rating);
        if (!badge.isEmpty()) stats += "  •  " + badge;
        h.stats.setText(stats);
        h.itemView.setOnClickListener(v -> {
            if (onClick != null) onClick.onClimb(row);
        });
    }

    @Override
    public int getItemCount() { return items.size(); }

    static String formatTime(int sec) {
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
